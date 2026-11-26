"""Build seven-strategy point-in-time candidates and conservative outcome labels.
Input CSV: timestamp,symbol,open,high,low,close,volume (UTC epoch milliseconds).
Signals are known at candle close; entry is next candle OPEN. Structural stop uses
prior 8-bar swing plus ATR buffer, target uses max(1.6R, 0.30% price). Same-bar
stop/target ambiguity is resolved as STOP_FIRST. This is research, not execution.
"""
import argparse
import pandas as pd

STRATEGIES = [
    'BREAKOUT_RETEST', 'EMA_TREND_PULLBACK', 'MOMENTUM_EXPANSION',
    'BOLLINGER_MEAN_REVERSION', 'VWAP_RECLAIM', 'MACD_MOMENTUM_CROSS',
    'RANGE_EXPANSION',
]
BASE_FEATURES = ['side','ema_gap_pct','rsi14','atr_pct','volume_ratio20',
                 'return_5m_pct','return_15m_pct','range_position20']
FEATURES = BASE_FEATURES + [f'strategy_{s}' for s in STRATEGIES]


def _ema(s, span):
    return s.ewm(span=span, adjust=False).mean()


def build(df, horizon=240, fee_side=.0005, slip_rt=.001, rr=None, stop_atr=None):
    if horizon < 1: raise ValueError('horizon must be positive')
    required={'timestamp','symbol','open','high','low','close','volume'}
    if not required.issubset(df.columns): raise ValueError(f'Missing columns: {sorted(required-set(df.columns))}')
    out=[]
    for symbol,g in df.sort_values(['symbol','timestamp']).groupby('symbol'):
        g=g.reset_index(drop=True).copy()
        o,h,l,c,v=[g[x].astype(float) for x in ('open','high','low','close','volume')]
        prev=c.shift(1)
        tr=pd.concat([(h-l),(h-prev).abs(),(l-prev).abs()],axis=1).max(axis=1)
        atr=tr.rolling(14).mean(); e9=_ema(c,9); e21=_ema(c,21)
        delta=c.diff(); gain=delta.clip(lower=0).rolling(14).mean(); loss=(-delta.clip(upper=0)).rolling(14).mean()
        rs=gain/loss.replace(0,float('nan')); rsi=(100-100/(1+rs)).fillna(100.0)
        vol_ratio=v.rolling(3).mean()/v.shift(3).rolling(20).mean()
        hi20=h.shift(1).rolling(20).max(); lo20=l.shift(1).rolling(20).min()
        hi30=h.shift(1).rolling(30).max(); lo30=l.shift(1).rolling(30).min()
        bb_mid=c.rolling(20).mean(); bb_sd=c.rolling(20).std(ddof=0)
        lower=bb_mid-2*bb_sd; upper=bb_mid+2*bb_sd
        typical=(h+l+c)/3; vwap=(typical*v).rolling(30).sum()/v.rolling(30).sum()
        macd=_ema(c,12)-_ema(c,26); macd_sig=_ema(macd,9)
        for i in range(40, len(g)-horizon-1):
            if pd.isna(atr.iloc[i]) or atr.iloc[i] <= 0 or pd.isna(rsi.iloc[i]) or pd.isna(vol_ratio.iloc[i]): continue
            price=float(c.iloc[i]); prev_c=float(c.iloc[i-1]); a=float(atr.iloc[i]); vr=float(vol_ratio.iloc[i])
            candidates=[]
            if price>hi20.iloc[i] and vr>=1.15: candidates.append(('BREAKOUT_RETEST',1))
            if price<lo20.iloc[i] and vr>=1.15: candidates.append(('BREAKOUT_RETEST',-1))
            near=abs(price-float(e9.iloc[i]))<=max(a*.45,price*.001)
            if near and price>float(o.iloc[i]) and prev_c<=float(e9.iloc[i]) and e9.iloc[i]>e21.iloc[i]: candidates.append(('EMA_TREND_PULLBACK',1))
            if near and price<float(o.iloc[i]) and prev_c>=float(e9.iloc[i]) and e9.iloc[i]<e21.iloc[i]: candidates.append(('EMA_TREND_PULLBACK',-1))
            ret5=(price/float(c.iloc[i-5])-1)*100
            if abs(ret5)>=.50 and vr>=1.4: candidates.append(('MOMENTUM_EXPANSION',1 if ret5>0 else -1))
            if prev_c<lower.iloc[i-1] and price>lower.iloc[i] and rsi.iloc[i]<38: candidates.append(('BOLLINGER_MEAN_REVERSION',1))
            if prev_c>upper.iloc[i-1] and price<upper.iloc[i] and rsi.iloc[i]>62: candidates.append(('BOLLINGER_MEAN_REVERSION',-1))
            if prev_c<=vwap.iloc[i-1] and price>vwap.iloc[i] and e9.iloc[i]>e21.iloc[i]: candidates.append(('VWAP_RECLAIM',1))
            if prev_c>=vwap.iloc[i-1] and price<vwap.iloc[i] and e9.iloc[i]<e21.iloc[i]: candidates.append(('VWAP_RECLAIM',-1))
            if macd.iloc[i-1]<=macd_sig.iloc[i-1] and macd.iloc[i]>macd_sig.iloc[i]: candidates.append(('MACD_MOMENTUM_CROSS',1))
            if macd.iloc[i-1]>=macd_sig.iloc[i-1] and macd.iloc[i]<macd_sig.iloc[i]: candidates.append(('MACD_MOMENTUM_CROSS',-1))
            if price>hi30.iloc[i] and vr>=1.25: candidates.append(('RANGE_EXPANSION',1))
            if price<lo30.iloc[i] and vr>=1.25: candidates.append(('RANGE_EXPANSION',-1))
            # One sample per strategy and side per timestamp.
            for strategy,side in dict.fromkeys(candidates):
                entry=float(o.iloc[i+1]); buffer=max(a*.15,entry*.0002)
                swing_low=float(l.iloc[max(0,i-7):i+1].min()); swing_high=float(h.iloc[max(0,i-7):i+1].max())
                stop=swing_low-buffer if side==1 else swing_high+buffer
                risk=abs(entry-stop)
                if risk<=0 or risk/entry>.05: continue
                target=entry+(1 if side==1 else -1)*max(risk*1.6,entry*.003)
                label=None; resolved=None
                exit_price=None
                for j in range(i+1,min(i+1+horizon,len(g))):
                    op=float(o.iloc[j])
                    # Opening gaps are filled at the open, not magically at the stop/target.
                    if (op<=stop if side==1 else op>=stop): label=0; resolved=j; exit_price=op; break
                    if (op>=target if side==1 else op<=target): label=1; resolved=j; exit_price=op; break
                    stop_hit=(l.iloc[j]<=stop if side==1 else h.iloc[j]>=stop)
                    target_hit=(h.iloc[j]>=target if side==1 else l.iloc[j]<=target)
                    if stop_hit: label=0; resolved=j; exit_price=stop; break
                    if target_hit: label=1; resolved=j; exit_price=target; break
                if label is None: continue
                gross=side*(float(exit_price)-entry)/entry
                net=gross-2*fee_side-slip_rt
                span=float(hi20.iloc[i]-lo20.iloc[i])
                row={'timestamp':int(g.timestamp.iloc[i]),'label_end_timestamp':int(g.timestamp.iloc[resolved]),'symbol':symbol,
                     'side':side,'ema_gap_pct':(float(e9.iloc[i])/float(e21.iloc[i])-1)*100,
                     'rsi14':float(rsi.iloc[i]),'atr_pct':a/entry*100,'volume_ratio20':vr,
                     'return_5m_pct':ret5,'return_15m_pct':(price/float(c.iloc[i-15])-1)*100,
                     'range_position20':(price-float(lo20.iloc[i]))/span if span else .5,
                     'target_first':int(label),'net_return_assumption':net,'strategy':strategy}
                row.update({f'strategy_{s}':1.0 if s==strategy else 0.0 for s in STRATEGIES})
                out.append(row)
    return pd.DataFrame(out)


def main():
    p=argparse.ArgumentParser(); p.add_argument('--csv',required=True); p.add_argument('--out',default='research_dataset.csv')
    p.add_argument('--horizon',type=int,default=240); p.add_argument('--fee-side',type=float,default=.0005)
    p.add_argument('--slippage-round-trip',type=float,default=.001); a=p.parse_args()
    result=build(pd.read_csv(a.csv),a.horizon,a.fee_side,a.slippage_round_trip)
    result.to_csv(a.out,index=False); print(f'Wrote {len(result)} seven-strategy labelled candidates to {a.out}')
if __name__=='__main__': main()
