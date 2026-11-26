"""Conservative candle-level target/stop ordering backtest.
CSV columns: timestamp, symbol, open, high, low, close, volume.
Uses a fixed risk/reward template solely for research; not live strategy validation.
"""
import argparse, json
import pandas as pd

def run(df, fee_side=.0005, slippage_round_trip=.001, rr=1.5, stop_atr=1.5):
    trades=[]
    for sym,g in df.sort_values('timestamp').groupby('symbol'):
        g=g.reset_index(drop=True)
        for i in range(30,len(g)-2):
            row=g.iloc[i]; hist=g.iloc[i-20:i]
            atr=((hist.high-hist.low).mean())
            if not atr or row.close<=0: continue
            # Minimal illustrative trigger: close breaks prior 20-bar high/low.
            hi=hist.high.max(); lo=hist.low.min()
            side=1 if row.close>hi else (-1 if row.close<lo else 0)
            if not side: continue
            entry=float(row.close); stop=entry-side*atr*stop_atr; target=entry+side*abs(entry-stop)*rr
            outcome=None; exit_px=None
            for j in range(i+1,min(i+61,len(g))):
                bar=g.iloc[j]
                stop_hit=(bar.low<=stop if side==1 else bar.high>=stop)
                target_hit=(bar.high>=target if side==1 else bar.low<=target)
                if stop_hit and target_hit: outcome='AMBIGUOUS_STOP_FIRST'; exit_px=stop; break
                if stop_hit: outcome='STOP'; exit_px=stop; break
                if target_hit: outcome='TARGET'; exit_px=target; break
            if exit_px is None: continue
            gross=side*(exit_px-entry)/entry
            net=gross-2*fee_side-slippage_round_trip
            trades.append({'symbol':sym,'timestamp':row.timestamp,'outcome':outcome,'gross_pct':gross*100,'net_pct':net*100})
    t=pd.DataFrame(trades)
    if t.empty: return {'trades':0,'warning':'No trades; cannot infer strategy performance'}
    wins=t.net_pct>0
    return {'trades':len(t),'win_rate_pct':float(wins.mean()*100),'mean_net_pct':float(t.net_pct.mean()),
      'profit_factor':float(t.loc[t.net_pct>0,'net_pct'].sum()/abs(t.loc[t.net_pct<0,'net_pct'].sum())) if (t.net_pct<0).any() else None,
      'ambiguous_stop_first_count':int((t.outcome=='AMBIGUOUS_STOP_FIRST').sum()),
      'warning':'Illustrative close-break strategy only; no funding, spread, market impact, latency, survivorship or purged walk-forward validation.'}

def main():
    p=argparse.ArgumentParser();p.add_argument('--csv',required=True);p.add_argument('--fee-side',type=float,default=.0005);p.add_argument('--slippage-round-trip',type=float,default=.001);a=p.parse_args()
    print(json.dumps(run(pd.read_csv(a.csv),a.fee_side,a.slippage_round_trip),indent=2))
if __name__=='__main__':main()
