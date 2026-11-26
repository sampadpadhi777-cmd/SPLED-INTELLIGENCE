"""Transparent rule-based decision engine. No untrained ML probabilities are fabricated."""
from __future__ import annotations
from dataclasses import dataclass
from typing import Optional

@dataclass
class Decision:
    direction: str
    score: float
    entry: float
    target: float
    stop: float
    risk_reward: float
    reasons: list[str]
    invalidation: list[str]
    status: str = "RULE_BASED_CANDIDATE_NOT_BACKTEST_VALIDATED"


def ema(values: list[float], period: int) -> Optional[float]:
    if len(values) < period: return None
    alpha = 2 / (period + 1)
    out = sum(values[:period]) / period
    for v in values[period:]: out = alpha * v + (1-alpha) * out
    return out


def decide(candles: list[dict], price: float, spread_bps: Optional[float],
           volume_24h: Optional[float], min_volume: float = 1_000_000,
           max_spread_bps: float = 15, risk_reward_floor: float = 1.5,
           bias_5m: Optional[str] = None, bias_15m: Optional[str] = None) -> Optional[Decision]:
    """15m-style structure from 1m candles; returns None unless a breakout/retest is visible."""
    if len(candles) < 60 or price <= 0: return None
    if volume_24h is None or volume_24h < min_volume: return None
    if spread_bps is None or spread_bps > max_spread_bps: return None
    closes = [float(c['close']) for c in candles]
    highs = [float(c['high']) for c in candles]
    lows = [float(c['low']) for c in candles]
    vols = [float(c['volume']) for c in candles]
    fast, slow = ema(closes, 9), ema(closes, 21)
    if fast is None or slow is None: return None
    recent_high = max(highs[-21:-3]); recent_low = min(lows[-21:-3])
    recent_vol = sum(vols[-3:]) / 3
    base_vol = sum(vols[-23:-3]) / 20
    if base_vol <= 0: return None
    vol_ratio = recent_vol / base_vol
    last = candles[-1]
    # A retest requires a recent level break, followed by a close back on the correct side.
    long_retest = fast > slow and closes[-2] > recent_high and float(last['low']) <= recent_high * 1.0015 and closes[-1] > recent_high
    short_retest = fast < slow and closes[-2] < recent_low and float(last['high']) >= recent_low * .9985 and closes[-1] < recent_low
    if not (long_retest or short_retest) or vol_ratio < 1.15: return None
    direction = 'LONG' if long_retest else 'SHORT'
    # When higher-timeframe context is supplied, require both 5m and 15m alignment.
    # This avoids treating a 1m counter-trend bounce as a full setup.
    if bias_5m is not None and bias_5m != direction: return None
    if bias_15m is not None and bias_15m != direction: return None
    entry = price
    # Volatility-aware buffer beyond the recent swing: avoid placing the stop exactly
    # on the obvious wick extreme. This is still a heuristic, not an AI recovery model.
    true_ranges=[]
    for i in range(1,len(candles)):
        prev=closes[i-1]
        true_ranges.append(max(highs[i]-lows[i],abs(highs[i]-prev),abs(lows[i]-prev)))
    atr=sum(true_ranges[-14:])/min(14,len(true_ranges)) if true_ranges else 0.0
    buffer=max(atr*0.15, entry*0.0002)
    if direction == 'LONG':
        stop = min(lows[-8:]) - buffer
        risk = entry - stop
        target = entry + max(risk * risk_reward_floor, entry * .003)
    else:
        stop = max(highs[-8:]) + buffer
        risk = stop - entry
        target = entry - max(risk * risk_reward_floor, entry * .003)
    if risk <= 0 or target <= 0: return None
    rr = abs(target-entry)/risk
    if rr < risk_reward_floor: return None
    score = min(95.0, 55 + min(15, (vol_ratio-1)*10) + (8 if abs(fast/slow-1) > .001 else 0) + min(10, rr*2))
    return Decision(direction, round(score,1), entry, target, stop, round(rr,2),
      [f"{direction} 1m breakout/retest condition detected", f"EMA 9/21 alignment supports direction", f"Recent volume is {vol_ratio:.2f}x its 20-candle baseline", f"Volatility buffer uses ATR(14): {atr:.6g}", f"Estimated structural risk/reward is {rr:.2f}:1"] + ([f"5m and 15m trend bias confirm {direction}"] if bias_5m is not None and bias_15m is not None else []),
      ["Cancel if price closes back through the retest level", "Reassess if spread widens or market data becomes stale", "This rule-based candidate is not a validated win-probability forecast"])
