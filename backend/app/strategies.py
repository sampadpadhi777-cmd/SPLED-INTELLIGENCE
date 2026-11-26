"""Concurrent, explainable strategy library for Binance USD-M USDT perpetuals.

All strategies consume the same closed-candle history and current executable top-of-book
reference. These are candidate generators, not validated forecasts. No strategy may emit
without sufficient candles, liquidity, a current quote, and directionally valid risk levels.
"""
from __future__ import annotations
from dataclasses import dataclass
from typing import Optional
from .decision_engine import ema

@dataclass
class Setup:
    strategy: str
    direction: str
    score: float
    entry: float
    target: float
    stop: float
    rr: float
    reasons: list[str]
    invalidation: list[str]
    status: str = "RULE_BASED_CANDIDATE_NOT_BACKTEST_VALIDATED"


def _atr(candles: list[dict], n: int = 14) -> float:
    if len(candles) < n + 1: return 0.0
    trs=[]
    for i in range(1,len(candles)):
        prev=float(candles[i-1]['close']); h=float(candles[i]['high']); l=float(candles[i]['low'])
        trs.append(max(h-l,abs(h-prev),abs(l-prev)))
    return sum(trs[-n:])/n

def _rsi(closes: list[float], n: int = 14) -> Optional[float]:
    if len(closes) < n+1: return None
    changes=[closes[i]-closes[i-1] for i in range(len(closes)-n,len(closes))]
    gains=sum(max(x,0) for x in changes)/n; losses=sum(max(-x,0) for x in changes)/n
    if losses == 0: return 100.0
    return 100-(100/(1+gains/losses))

def _build(strategy: str, direction: str, candles: list[dict], price: float,
           score: float, reasons: list[str], rr_floor: float=1.5) -> Optional[Setup]:
    atr=_atr(candles)
    if atr <= 0: return None
    recent=candles[-12:]
    buffer=max(atr*.2,price*.00025)
    if direction=='LONG':
        stop=min(float(x['low']) for x in recent)-buffer
        risk=price-stop
        target=price+max(risk*rr_floor,atr*1.8)
    else:
        stop=max(float(x['high']) for x in recent)+buffer
        risk=stop-price
        target=price-max(risk*rr_floor,atr*1.8)
    if risk<=0 or target<=0: return None
    rr=abs(target-price)/risk
    if rr<rr_floor: return None
    return Setup(strategy,direction,round(min(98,max(1,score)),1),price,target,stop,rr,
      reasons+[f"ATR(14) volatility buffer: {buffer:.8g}",f"Structural risk/reward: {rr:.2f}:1"],
      ["Cancel if a closed candle invalidates the setup structure","Recheck spread, data freshness and funding before execution","Candidate score is not a calibrated probability"])

def evaluate_all(candles: list[dict], price: float, spread_bps: Optional[float],
                 quote_volume: Optional[float], min_volume: float, max_spread: float,
                 biases: dict[str, Optional[str]]) -> list[Setup]:
    if len(candles)<80 or price<=0 or quote_volume is None or quote_volume<min_volume:
        return []
    if spread_bps is None or spread_bps>max_spread: return []
    c=[float(x['close']) for x in candles]; h=[float(x['high']) for x in candles]
    l=[float(x['low']) for x in candles]; v=[float(x['volume']) for x in candles]
    fast,slow=ema(c,9),ema(c,21)
    if fast is None or slow is None: return []
    direction='LONG' if fast>slow else 'SHORT' if fast<slow else None
    if not direction: return []
    # Higher timeframes are confirmation filters only after enough history exists.
    # 5m and 15m are mandatory; 1h/4h are extra context and do not block initial warm-up.
    for tf in ('5m','15m'):
        if biases.get(tf) is not None and biases[tf] not in (direction,'NEUTRAL'):
            return []
    out=[]
    # 1) Breakout + retest. The level is defined only by prior closed candles.
    hi=max(h[-24:-3]); lo=min(l[-24:-3])
    vol_base=sum(v[-23:-3])/20; vol_now=sum(v[-3:])/3
    vr=vol_now/vol_base if vol_base>0 else 0
    last=candles[-1]
    long_retest=direction=='LONG' and c[-2]>hi and float(last['low'])<=hi*1.0015 and c[-1]>hi
    short_retest=direction=='SHORT' and c[-2]<lo and float(last['high'])>=lo*.9985 and c[-1]<lo
    if (long_retest or short_retest) and vr>=1.15:
        d='LONG' if long_retest else 'SHORT'
        s=_build('BREAKOUT_RETEST',d,candles,price,72+min(15,(vr-1)*10),
          [f"Prior range break and retest confirmed on closed candles",f"Volume is {vr:.2f}x its 20-candle baseline",f"EMA 9/21 supports {d}"])
        if s: out.append(s)
    # 2) Trend pullback: directional EMA stack + candle reclaim near fast EMA.
    atr=_atr(candles); near=abs(c[-1]-fast)<=max(atr*.45,price*.001)
    bullish=float(last['close'])>float(last['open']); bearish=float(last['close'])<float(last['open'])
    if near and ((direction=='LONG' and bullish and c[-2]<=fast) or (direction=='SHORT' and bearish and c[-2]>=fast)):
        s=_build('EMA_TREND_PULLBACK',direction,candles,price,67+(7 if biases.get('1h')==direction else 0),
          [f"EMA 9/21 trend structure supports {direction}","Price pulled back toward EMA 9 and closed with directional candle"])
        if s: out.append(s)
    # 3) Momentum expansion: short-term impulse + volume expansion, multi-timeframe aligned.
    move=(c[-1]/c[-6]-1)*100 if c[-6] else 0
    aligned=(move>=0.50 if direction=='LONG' else move<=-.50)
    if aligned and vr>=1.4 and biases.get('15m') in (None,direction) and biases.get('1h') in (None,direction):
        s=_build('MOMENTUM_EXPANSION',direction,candles,price,70+min(12,(abs(move)-.50)*8)+min(8,(vr-1.4)*5),
          [f"Five-candle momentum expansion: {move:.3f}% (minimum 0.50%)",f"Volume expansion: {vr:.2f}x",f"15m/1h context does not oppose {direction}"])
        if s: out.append(s)
    # 4) Bollinger-style mean reversion: only take stretched move back inside band
    # when higher timeframe does not strongly oppose the reversal.
    rsi=_rsi(c)
    if rsi is not None and len(c)>=20:
        mean=sum(c[-20:])/20; sd=(sum((x-mean)**2 for x in c[-20:])/20)**.5
        upper,lower=mean+2*sd,mean-2*sd
        rev_long=c[-2]<lower and c[-1]>lower and rsi<38
        rev_short=c[-2]>upper and c[-1]<upper and rsi>62
        rev='LONG' if rev_long else 'SHORT' if rev_short else None
        if rev and biases.get('1h') not in ({'SHORT'} if rev=='LONG' else {'LONG'}):
            s=_build('BOLLINGER_MEAN_REVERSION',rev,candles,price,62,
              [f"Price re-entered 20-period 2σ band",f"RSI(14)={rsi:.1f}","Countertrend setup; use smaller risk until validated"],rr_floor=1.6)
            if s: out.append(s)
    # 5) VWAP reclaim: reclaim of rolling 30-candle volume-weighted typical price.
    vwap_vol=sum(v[-30:])
    if vwap_vol>0:
        vwap=sum((((h[i]+l[i]+c[i])/3)*v[i]) for i in range(len(c)-30,len(c)))/vwap_vol
        prev_vwap_num=sum((((h[i]+l[i]+c[i])/3)*v[i]) for i in range(len(c)-31,len(c)-1))
        prev_vwap_den=sum(v[-31:-1]); prev_vwap=prev_vwap_num/prev_vwap_den if prev_vwap_den>0 else vwap
        reclaim_long=c[-2]<=prev_vwap and c[-1]>vwap and direction=='LONG'
        reclaim_short=c[-2]>=prev_vwap and c[-1]<vwap and direction=='SHORT'
        if reclaim_long or reclaim_short:
            d='LONG' if reclaim_long else 'SHORT'
            s=_build('VWAP_RECLAIM',d,candles,price,66+(6 if biases.get('15m')==d else 0),
              [f"Price reclaimed rolling 30-candle VWAP ({vwap:.8g})",f"EMA 9/21 context supports {d}"])
            if s: out.append(s)
    # 6) MACD histogram cross: fast/slow momentum crossover with trend alignment.
    if len(c)>=40:
        macd_line=[]
        for end in range(25,len(c)+1):
            ef,es=ema(c[:end],12),ema(c[:end],26)
            if ef is not None and es is not None: macd_line.append(ef-es)
        if len(macd_line)>=12:
            signal_line=ema(macd_line,9)
            prev_signal=ema(macd_line[:-1],9)
            cross_long=prev_signal is not None and macd_line[-2]<=prev_signal and macd_line[-1]>signal_line and direction=='LONG'
            cross_short=prev_signal is not None and macd_line[-2]>=prev_signal and macd_line[-1]<signal_line and direction=='SHORT'
            if cross_long or cross_short:
                d='LONG' if cross_long else 'SHORT'
                s=_build('MACD_MOMENTUM_CROSS',d,candles,price,65+(7 if biases.get('1h')==d else 0),
                  [f"MACD(12,26,9) momentum cross supports {d}","EMA 9/21 direction agrees with the MACD cross"])
                if s: out.append(s)
    # 5) Range expansion: close beyond 30-candle range with ATR-normalized displacement.
    range_hi=max(h[-31:-1]); range_lo=min(l[-31:-1])
    if c[-1]>range_hi and vr>=1.25 and direction=='LONG':
        s=_build('RANGE_EXPANSION', 'LONG',candles,price,68+min(10,(vr-1.25)*6),
          ["Closed candle broke the 30-candle range high",f"Volume expansion: {vr:.2f}x"])
        if s: out.append(s)
    elif c[-1]<range_lo and vr>=1.25 and direction=='SHORT':
        s=_build('RANGE_EXPANSION','SHORT',candles,price,68+min(10,(vr-1.25)*6),
          ["Closed candle broke the 30-candle range low",f"Volume expansion: {vr:.2f}x"])
        if s: out.append(s)
    return sorted(out,key=lambda x:x.score,reverse=True)
