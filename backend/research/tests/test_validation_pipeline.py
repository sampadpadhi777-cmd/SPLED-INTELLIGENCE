import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import numpy as np
import pandas as pd
from label_dataset import build, STRATEGIES
from train_walkforward import _purge, _metrics


def test_purge_excludes_overlapping_labels():
    frame=pd.DataFrame({'timestamp':[1,2,3],'label_end_timestamp':[5,9,10]})
    kept=_purge(frame,9)
    assert kept.timestamp.tolist()==[1]


def test_label_builder_emits_strategy_features_and_point_in_time_rows():
    rows=[]
    for i in range(500):
        # deterministic trending data with periodic impulses and volume bursts
        base=100 + i*.012 + (1.1 if i%73>64 else 0)
        op=base-.015; close=base+.02
        rows.append({'timestamp':i*60000,'symbol':'TESTUSDT','open':op,'high':close+.12,'low':op-.12,'close':close,'volume':1500 if i%73>64 else 1000})
    out=build(pd.DataFrame(rows),horizon=30,fee_side=0,slip_rt=0)
    assert set(f'strategy_{s}' for s in STRATEGIES).issubset(out.columns)
    if not out.empty:
        assert (out.label_end_timestamp >= out.timestamp).all()
        assert set(out.target_first.unique()).issubset({0,1})


def test_metrics_never_counts_flat_returns_as_wins():
    m=_metrics([1,0,1],[.8,.2,.7],[.01,0,-.01],threshold=.6)
    assert m['selected_at_probability_0_60']==2
    assert m['win_rate_pct_selected']==50.0


def test_trainer_exports_disabled_portable_android_artifact(tmp_path):
    from train_walkforward import run
    rng=np.random.default_rng(7); n=1500
    x=rng.normal(size=n); y=(x+rng.normal(scale=.8,size=n)>0).astype(int)
    frame=pd.DataFrame({
        'timestamp':np.arange(n,dtype=np.int64)*60000,
        'label_end_timestamp':np.arange(n,dtype=np.int64)*60000+30000,
        'target_first':y,
        'net_return_assumption':np.where(y==1,.004,-.003),
        'feature_signal':x,
        'side':rng.choice([-1,1],size=n),
        'strategy_BREAKOUT_RETEST':np.ones(n),
    })
    csv=tmp_path/'dataset.csv'; frame.to_csv(csv,index=False)
    artifact=tmp_path/'android'/'hybrid_model.json'
    metrics=run(csv,tmp_path/'model.joblib',min_oos=999999,android_out=artifact)
    import json
    payload=json.loads(artifact.read_text())
    assert payload['schema_version']==1
    assert payload['enabled_for_shadow'] is False
    assert len(payload['features'])==len(payload['coefficients'])
    assert metrics['rows_test']>=100
