"""Trade-flow and forced-liquidation monitor using public USD-M streams."""
import asyncio, json, logging, time
from collections import defaultdict, deque
import websockets
from .config import settings
log=logging.getLogger(__name__)

def f(x):
    try: return float(x)
    except (TypeError,ValueError): return None

class FlowMonitor:
    def __init__(self, scanner, publish):
        self.scanner=scanner; self.publish=publish
        self.trades=defaultdict(lambda:deque(maxlen=5000)); self.liquidations=deque(maxlen=1000)
        self.whale_alerts=deque(maxlen=250)

    def on_trade(self, event):
        sym=event.get('s'); price=f(event.get('p')); qty=f(event.get('q'))
        if not sym or not price or not qty: return None
        notional=price*qty; buyer_is_maker=bool(event.get('m'))
        item={'time_ms':int(event.get('T') or time.time()*1000),'price':price,'notional':notional,
              'taker_side':'SELL' if buyer_is_maker else 'BUY'}
        q=self.trades[sym]; q.append(item)
        cutoff=item['time_ms']-300_000
        while q and q[0]['time_ms']<cutoff: q.popleft()
        # Conservative notional threshold; this is a large-trade flag, not proof of whale identity.
        market=self.scanner.market.get(sym) if self.scanner else None
        threshold=max(25_000.0, (market.quote_volume_24h or 0)*0.0002) if market else 100_000.0
        if notional>=threshold:
            alert={'type':'large_trade','symbol':sym,**item}
            self.whale_alerts.append(alert); return alert
        return None

    def on_liquidation(self, event):
        o=event.get('o') or {}; sym=o.get('s'); price=f(o.get('ap')) or f(o.get('p')); qty=f(o.get('q'))
        if not sym or not price or not qty: return None
        item={'type':'liquidation','symbol':sym,'time_ms':int(event.get('E') or time.time()*1000),
              'side':'SELL_LIQUIDATION' if o.get('S')=='SELL' else 'BUY_LIQUIDATION',
              'price':price,'quantity':qty,'notional_usdt':price*qty}
        self.liquidations.append(item); return item

    def summary(self, symbol):
        q=self.trades[symbol]; now=int(time.time()*1000); rows=[x for x in q if x['time_ms']>=now-300_000]
        buy=sum(x['notional'] for x in rows if x['taker_side']=='BUY'); sell=sum(x['notional'] for x in rows if x['taker_side']=='SELL')
        return {'symbol':symbol,'window_seconds':300,'taker_buy_notional':buy,'taker_sell_notional':sell,
                'buy_sell_imbalance':(buy-sell)/(buy+sell) if buy+sell else None,
                'large_trade_count':sum(1 for x in rows if x['notional']>=max(25_000.0,((self.scanner.market.get(symbol).quote_volume_24h or 0)*0.0002) if self.scanner and self.scanner.market.get(symbol) else 100_000.0))}

    async def liquidations_stream(self):
        delay=1
        while True:
            try:
                async with websockets.connect(settings.ws_base+'/!forceOrder@arr',ping_interval=20,ping_timeout=20) as ws:
                    delay=1
                    async for raw in ws:
                        data=json.loads(raw); item=self.on_liquidation(data)
                        if item: await self.publish({'type':'liquidation_update','event':item})
            except asyncio.CancelledError: raise
            except Exception as ex:
                log.warning('liquidation stream reconnect: %s',ex); await asyncio.sleep(delay); delay=min(delay*2,60)

    async def large_trades_stream(self):
        delay=1
        while True:
            try:
                symbols=sorted(set(self.scanner.market))
                if not symbols: await asyncio.sleep(10); continue
                # Subscribe all currently tracked eligible contracts, split into bounded URLs.
                shards=[symbols[i:i+100] for i in range(0,len(symbols),100)]
                async def consume(shard):
                    streams='/'.join(x.lower()+'@aggTrade' for x in shard)
                    url=settings.ws_base.replace('/ws','/stream')+'?streams='+streams
                    async with websockets.connect(url,ping_interval=20,ping_timeout=20,max_size=2**22) as ws:
                        async for raw in ws:
                            msg=json.loads(raw); data=msg.get('data',msg); alert=self.on_trade(data)
                            if alert: await self.publish({'type':'whale_alert','event':alert})
                # One task per shard; Binance stream limit remains comfortably below 1024.
                tasks=[asyncio.create_task(consume(shard)) for shard in shards]
                try:
                    await asyncio.sleep(300)
                finally:
                    for task in tasks: task.cancel()
                    await asyncio.gather(*tasks,return_exceptions=True)
                delay=1
            except asyncio.CancelledError: raise
            except Exception as ex:
                log.warning('all-symbol aggTrade stream reconnect: %s',ex)
                await asyncio.sleep(delay); delay=min(delay*2,60)
