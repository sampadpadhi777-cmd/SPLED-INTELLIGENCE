"""Explainable market metrics; not a predictive probability model."""
from math import sqrt

def aggregate_candles(candles: list[dict], minutes: int) -> list[dict]:
    """Aggregate closed 1m candles into complete higher-timeframe candles only."""
    if minutes <= 1:
        return list(candles)
    buckets = {}
    width = minutes * 60_000
    for candle in candles:
        stamp = int(candle['open_time'])
        key = stamp // width
        buckets.setdefault(key, []).append(candle)
    result = []
    for key in sorted(buckets):
        rows = sorted(buckets[key], key=lambda x: int(x['open_time']))
        if len(rows) != minutes:
            continue
        result.append({
            'open_time': key * width, 'open': float(rows[0]['open']),
            'high': max(float(x['high']) for x in rows),
            'low': min(float(x['low']) for x in rows),
            'close': float(rows[-1]['close']),
            'volume': sum(float(x['volume']) for x in rows),
        })
    return result

def timeframe_bias(candles: list[dict]) -> str | None:
    """EMA 9/21 direction; returns None until enough closed candles exist."""
    if len(candles) < 25:
        return None
    closes = [float(x['close']) for x in candles]
    def calc(period):
        alpha = 2 / (period + 1)
        value = sum(closes[:period]) / period
        for price in closes[period:]:
            value = alpha * price + (1-alpha) * value
        return value
    fast, slow = calc(9), calc(21)
    if fast > slow: return 'LONG'
    if fast < slow: return 'SHORT'
    return 'NEUTRAL'

def summarize_candles(candles: list[dict]) -> dict:
    if len(candles)<20: return {'ready':False,'reason':'Need at least 20 closed candles'}
    c=[float(x['close']) for x in candles]; h=[float(x['high']) for x in candles]; l=[float(x['low']) for x in candles]; v=[float(x['volume']) for x in candles]
    tr=[]
    for i in range(1,len(c)):
        tr.append(max(h[i]-l[i],abs(h[i]-c[i-1]),abs(l[i]-c[i-1])))
    atr=sum(tr[-14:])/min(14,len(tr)) if tr else 0
    mean=sum(c[-20:])/20
    variance=sum((x-mean)**2 for x in c[-20:])/20
    vol_mean=sum(v[-20:])/20
    return {'ready':True,'atr_14':atr,'atr_pct':atr/c[-1]*100 if c[-1] else None,
            'realized_vol_20':sqrt(variance)/mean*100 if mean else None,
            'volume_ratio_last_3':(sum(v[-3:])/3/vol_mean if vol_mean else None),
            'range_high_20':max(h[-20:]),'range_low_20':min(l[-20:]),
            'trend_20_pct':(c[-1]/c[-20]-1)*100 if c[-20] else None}
