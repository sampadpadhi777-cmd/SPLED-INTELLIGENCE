"""Purged chronological model research. Research output NEVER enables live trading by itself."""
import argparse, json
from pathlib import Path
import numpy as np
import pandas as pd
import joblib
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score, brier_score_loss, log_loss
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

EXCLUDED={'timestamp','label_end_timestamp','symbol','target_first','future_return','net_return_assumption'}

def _purge(frame, cutoff):
    if 'label_end_timestamp' not in frame: raise ValueError('label_end_timestamp is required for purged validation')
    return frame[pd.to_numeric(frame.label_end_timestamp,errors='coerce') < cutoff].copy()

def _fit_calibrator(raw, y):
    # Platt scaling on a strictly later calibration window.
    x=np.log(np.clip(np.asarray(raw),1e-6,1-1e-6)/(1-np.clip(np.asarray(raw),1e-6,1-1e-6))).reshape(-1,1)
    cal=LogisticRegression(C=1.0,max_iter=1000)
    cal.fit(x,np.asarray(y).astype(int))
    return cal

def _calibrate(cal, raw):
    p=np.clip(np.asarray(raw),1e-6,1-1e-6)
    logits=np.log(p/(1-p)).reshape(-1,1)
    return cal.predict_proba(logits)[:,1]

def _metrics(y, p, net, threshold=.60):
    y=np.asarray(y).astype(int); p=np.asarray(p); net=np.asarray(net)
    take=p>=threshold; selected=net[take]
    wins=selected>0; losses=selected<0
    gross_profit=float(selected[wins].sum()) if wins.any() else 0.0
    gross_loss=float(-selected[losses].sum()) if losses.any() else 0.0
    pf=gross_profit/gross_loss if gross_loss>0 else None
    equity=np.cumprod(1+selected) if len(selected) else np.array([1.0])
    peak=np.maximum.accumulate(equity); dd=float(np.min(equity/peak-1)) if len(equity)>1 else 0.0
    return {'rows':int(len(y)),'selected_at_probability_0_60':int(take.sum()),
      'win_rate_pct_selected':float(wins.mean()*100) if len(selected) else None,
      'mean_net_return_pct_selected':float(selected.mean()*100) if len(selected) else None,
      'profit_factor_selected':pf,'max_drawdown_pct_selected':dd*100,
      'brier':float(brier_score_loss(y,p)),
      'roc_auc':float(roc_auc_score(y,p)) if len(np.unique(y))==2 else None,
      'log_loss':float(log_loss(y,p,labels=[0,1]))}

def _walk_forward_cv(df, cols, folds=4):
    # Expanding-window folds; each test block is strictly later than its training block.
    n=len(df); first_test=int(n*.50); block=max(1,(n-first_test)//folds); reports=[]
    for k in range(folds):
        a=first_test+k*block; b=n if k==folds-1 else min(n,a+block)
        if b-a<50 or a<100: continue
        test_start=float(df.timestamp.iloc[a]); train=_purge(df.iloc[:a],test_start); test=df.iloc[a:b]
        if len(train)<100 or train.target_first.nunique()<2 or test.target_first.nunique()<2: continue
        model=make_pipeline(SimpleImputer(strategy='median'),StandardScaler(),LogisticRegression(max_iter=2000,class_weight='balanced'))
        model.fit(train[cols],train.target_first.astype(int)); probs=model.predict_proba(test[cols])[:,1]
        reports.append({'fold':k+1,'train_rows':len(train),'test_rows':len(test),
          'roc_auc':float(roc_auc_score(test.target_first,probs)),
          'brier':float(brier_score_loss(test.target_first,probs))})
    return reports

def run(csv_path, out='artifacts/target_first.joblib', min_oos=200, android_out='../android/app/src/main/assets/hybrid_model.json'):
    df=pd.read_csv(csv_path).sort_values('timestamp').reset_index(drop=True)
    required={'timestamp','label_end_timestamp','target_first','net_return_assumption'}
    if not required.issubset(df.columns): raise ValueError(f'Missing columns: {sorted(required-set(df.columns))}')
    cols=[c for c in df.select_dtypes(include='number').columns if c not in EXCLUDED]
    if len(cols)<2: raise ValueError('Need at least two numeric point-in-time features')
    df=df.dropna(subset=['target_first','net_return_assumption']).copy()
    df['timestamp']=pd.to_numeric(df.timestamp); df['label_end_timestamp']=pd.to_numeric(df.label_end_timestamp)
    n=len(df)
    if n<1000: raise ValueError('Need at least 1,000 labelled candidates before research; more is strongly preferred')
    i_train=int(n*.60); i_cal=int(n*.80)
    cal_start=float(df.timestamp.iloc[i_train]); test_start=float(df.timestamp.iloc[i_cal])
    train=_purge(df.iloc[:i_train],cal_start); calib=_purge(df.iloc[i_train:i_cal],test_start); test=df.iloc[i_cal:].copy()
    if min(len(train),len(calib),len(test))<100: raise ValueError('Insufficient rows after chronological split and purge')
    if train.target_first.nunique()<2 or calib.target_first.nunique()<2: raise ValueError('Train and calibration windows must each contain both classes')
    model=make_pipeline(SimpleImputer(strategy='median'),StandardScaler(),LogisticRegression(max_iter=2000,class_weight='balanced'))
    model.fit(train[cols],train.target_first.astype(int))
    calibrator=_fit_calibrator(model.predict_proba(calib[cols])[:,1],calib.target_first)
    probs=_calibrate(calibrator,model.predict_proba(test[cols])[:,1])
    metrics=_metrics(test.target_first,probs,test.net_return_assumption)
    wf=_walk_forward_cv(df,cols)
    gate = bool(len(test)>=500 and metrics['selected_at_probability_0_60']>=min_oos and
        metrics['mean_net_return_pct_selected'] is not None and metrics['mean_net_return_pct_selected']>0 and
        metrics['profit_factor_selected'] is not None and metrics['profit_factor_selected']>1.2 and
        metrics['roc_auc'] is not None and metrics['roc_auc']>0.52 and
        metrics['brier']<0.25 and metrics['max_drawdown_pct_selected']>-15.0 and len(wf)>=3)
    metrics.update({'walk_forward_folds':wf,'walk_forward_fold_count':len(wf),'rows_total':n,'rows_train':len(train),'rows_calibration':len(calib),'rows_test':len(test),
      'features':cols,'calibration_start':cal_start,'test_start':test_start,'test_end':float(df.timestamp.iloc[-1]),
      'purge_rule':'training label_end_timestamp must be strictly earlier than next window start',
      'model':'standardized_logistic_regression_plus_platt_calibration',
      'research_gate_passed':gate,
      'warning':'Research-only. A passed historical gate is not permission for real-money trading; forward paper validation, execution-cost reconciliation and device tests remain mandatory.'})
    outp=Path(out); outp.parent.mkdir(parents=True,exist_ok=True)
    joblib.dump({'model':model,'calibrator':calibrator,'features':cols,'metrics':metrics},outp)
    outp.with_suffix(outp.suffix+'.metrics.json').write_text(json.dumps(metrics,indent=2))
    # Portable dependency-free Kotlin inference artifact. Disabled models cannot be used by Android.
    imputer=model.named_steps['simpleimputer']; scaler=model.named_steps['standardscaler']; clf=model.named_steps['logisticregression']
    android_artifact={
      'schema_version':1,'model_type':'standardized_logistic_regression_platt',
      'enabled_for_shadow':gate,'features':cols,
      'imputer_medians':[float(x) for x in imputer.statistics_],
      'scaler_means':[float(x) for x in scaler.mean_],
      'scaler_scales':[float(x) if float(x)!=0 else 1.0 for x in scaler.scale_],
      'coefficients':[float(x) for x in clf.coef_[0]],'intercept':float(clf.intercept_[0]),
      'calibration_slope':float(calibrator.coef_[0][0]),'calibration_intercept':float(calibrator.intercept_[0]),
      'threshold':0.60,'training_rows':len(train),'calibration_rows':len(calib),'holdout_rows':len(test),
      'metrics':metrics,'warning':'Shadow research model only. Never interpret as guaranteed win probability or automatic live approval.'}
    ap=Path(android_out); ap.parent.mkdir(parents=True,exist_ok=True); ap.write_text(json.dumps(android_artifact,indent=2))
    metrics['android_artifact_path']=str(ap)
    return metrics

def main():
    p=argparse.ArgumentParser();p.add_argument('--csv',required=True);p.add_argument('--out',default='artifacts/target_first.joblib');p.add_argument('--min-oos',type=int,default=200);p.add_argument('--android-out',default='../android/app/src/main/assets/hybrid_model.json')
    a=p.parse_args(); print(json.dumps(run(a.csv,a.out,a.min_oos,a.android_out),indent=2))
if __name__=='__main__': main()
