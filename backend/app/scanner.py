"""Event-driven per-symbol scanner. Every eligible USDT perpetual is evaluated independently."""
import time
from collections import defaultdict, deque
from .models import MarketState, Signal
from .config import settings
from .strategies import evaluate_all
from .analytics import aggregate_candles, timeframe_bias

class Scanner:
    def __init__(self):
        self.market={}; self.signals={}; self.candles=defaultdict(lambda:deque(maxlen=3000)); self.higher_candles=defaultdict(lambda:defaultdict(lambda:deque(maxlen=300))); self.last_eval_at={}
        self.eval_count=0; self.last_signal_at={}; self.max_signal_age_seconds=4*60*60
    def update(self,s:MarketState):
        self.market[s.symbol]=s
        return self.evaluate(s)
    def update_candle(self,symbol:str,candle:dict):
        q=self.candles[symbol]
        if q and q[-1].get('open_time')==candle.get('open_time'): q[-1]=candle
        else: q.append(candle)
        state=self.market.get(symbol)
        return self.evaluate(state) if state else []
    def update_higher_candle(self,symbol:str,interval:str,candle:dict):
        q=self.higher_candles[symbol][interval]
        if q and q[-1].get('open_time')==candle.get('open_time'): q[-1]=candle
        else: q.append(candle)
        return self.evaluate(self.market.get(symbol))
    def evaluate(self,s):
        if not s or not s.price or s.price<=0: return []
        now=time.time()
        lifecycle_updates=self._update_lifecycle(s, now) if s.source == 'ticker' else []
        # Coalesce ticker/book bursts per symbol, while every symbol is independently processed.
        if now-self.last_eval_at.get(s.symbol,0)<0.75: return lifecycle_updates
        self.last_eval_at[s.symbol]=now
        age=max(0,now-s.received_at)
        if age>settings.stale_after_seconds: return lifecycle_updates
        one=list(self.candles[s.symbol])
        if len(one)<80: return lifecycle_updates
        tfs={}
        for tf,minutes in (('5m',5),('15m',15)):
            tfs[tf]=timeframe_bias(aggregate_candles(one,minutes))
        tfs['1h']=timeframe_bias(list(self.higher_candles[s.symbol]['1h']))
        tfs['4h']=timeframe_bias(list(self.higher_candles[s.symbol]['4h']))
        setups=evaluate_all(one,s.price,s.spread_bps,s.quote_volume_24h,
          settings.min_quote_volume_usdt,settings.max_spread_bps,tfs)
        emitted=[]
        for d in setups:
            key=f"{s.symbol}:{d.strategy}:{d.direction}"
            old=self.signals.get(key)
            # Avoid flooding identical setups; refresh if level moved materially or after 3 min.
            if old and now-old.created_at<180 and abs(old.entry_reference-d.entry)/max(d.entry,1e-12)<.001:
                continue
            signal=Signal(symbol=s.symbol,direction=d.direction,mode=d.strategy,score=d.score,
              entry_reference=d.entry,target_reference=d.target,structural_stop_reference=d.stop,
              risk_reward=round(d.rr,2),rationale=d.reasons,invalidation=d.invalidation,
              target_before_stop_probability=None,probability_status=d.status,
              created_at=now,data_age_seconds=age)
            self.signals[key]=signal; emitted.append(signal)
        self.eval_count+=1
        return lifecycle_updates + emitted
    def _update_lifecycle(self, state, now):
        """Resolve suggestions when live last price reaches target/structural stop or expires."""
        changed=[]
        price=state.price
        for signal in self.signals.values():
            if signal.symbol != state.symbol or signal.status != 'ACTIVE':
                continue
            if now - signal.created_at >= self.max_signal_age_seconds:
                signal.status='EXPIRED'; signal.resolved_at=now; signal.exit_reference=price
                signal.outcome_note='Maximum 4-hour intraday idea lifetime reached; no target/stop outcome assumed.'
            elif signal.direction == 'LONG' and price >= signal.target_reference:
                signal.status='TARGET_HIT'; signal.resolved_at=now; signal.exit_reference=price
                signal.outcome_note='Last-traded price reached the suggested target; actual fills may differ.'
            elif signal.direction == 'LONG' and signal.structural_stop_reference is not None and price <= signal.structural_stop_reference:
                signal.status='STOP_HIT'; signal.resolved_at=now; signal.exit_reference=price
                signal.outcome_note='Last-traded price reached the structural stop; actual fills may differ.'
            elif signal.direction == 'SHORT' and price <= signal.target_reference:
                signal.status='TARGET_HIT'; signal.resolved_at=now; signal.exit_reference=price
                signal.outcome_note='Last-traded price reached the suggested target; actual fills may differ.'
            elif signal.direction == 'SHORT' and signal.structural_stop_reference is not None and price >= signal.structural_stop_reference:
                signal.status='STOP_HIT'; signal.resolved_at=now; signal.exit_reference=price
                signal.outcome_note='Last-traded price reached the structural stop; actual fills may differ.'
            else:
                continue
            changed.append(signal)
        return changed
    def snapshot(self):
        now=time.time(); markets=[]
        for s in self.market.values():
            d=s.model_dump(); d['data_age_seconds']=max(0,now-s.received_at); markets.append(d)
        markets.sort(key=lambda x:x.get('quote_volume_24h') or 0,reverse=True)
        signals=[s.model_dump() for s in self.signals.values()]
        signals.sort(key=lambda x:(x['score'],-x['data_age_seconds']),reverse=True)
        return {'markets':markets,'signals':signals[:500],
          'universe_count':len(self.market),'strategy_count':7,'evaluations':self.eval_count,
          'universe_note':'All discovered eligible Binance USD-M USDT perpetuals; market updates are event-driven per symbol.'}
