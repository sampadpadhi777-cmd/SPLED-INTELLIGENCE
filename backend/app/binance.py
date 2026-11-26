import asyncio, json, time, logging
import httpx, websockets
from .config import settings
from .models import MarketState
log=logging.getLogger(__name__)
def num(v):
    try: return float(v)
    except (TypeError,ValueError): return None
class BinanceFeed:
    def __init__(self,scanner,publish):
        self.scanner=scanner; self.publish=publish; self.symbols=set()
        self.connected=False; self.book_connected=False; self.kline_shards_connected=0; self.last_event=None; self.reconnects=0
    async def discover(self):
        async with httpx.AsyncClient(timeout=20) as c:
            r=await c.get(settings.rest_base+"/fapi/v1/exchangeInfo"); r.raise_for_status()
            info=r.json()
        self.symbols={x["symbol"] for x in info.get("symbols",[])
          if x.get("status")=="TRADING" and x.get("contractType")=="PERPETUAL" and x.get("quoteAsset")=="USDT"}
        return len(self.symbols)
    async def ticker(self):
        delay=1
        while True:
            try:
                async with websockets.connect(settings.ws_base+"/!ticker@arr",ping_interval=20,ping_timeout=20) as ws:
                    self.connected=True; delay=1
                    async for raw in ws:
                        now=time.time(); self.last_event=now
                        events=json.loads(raw); events=events if isinstance(events,list) else [events]
                        for e in events:
                            sym=e.get("s")
                            if sym not in self.symbols: continue
                            old=self.scanner.market.get(sym)
                            s=MarketState(symbol=sym,price=num(e.get("c")),change_24h_pct=num(e.get("P")),
                              quote_volume_24h=num(e.get("q")),bid=old.bid if old else None,ask=old.ask if old else None,
                              spread_bps=old.spread_bps if old else None,event_time_ms=e.get("E"),
                              received_at=now,source="ticker")
                            for sig in self.scanner.update(s):
                                await self.publish({"type":"signal_update","signal":sig.model_dump()})
                            await self.publish({"type":"market_update","market":s.model_dump()})
            except asyncio.CancelledError: raise
            except Exception as ex:
                self.connected=False; self.reconnects+=1; log.warning("ticker feed reconnect: %s",ex)
                await asyncio.sleep(delay); delay=min(delay*2,60)
    async def book(self):
        delay=1
        while True:
            try:
                async with websockets.connect(settings.ws_base+"/!bookTicker",ping_interval=20,ping_timeout=20) as ws:
                    self.book_connected=True; delay=1
                    async for raw in ws:
                        now=time.time(); e=json.loads(raw); sym=e.get("s")
                        if sym not in self.symbols: continue
                        old=self.scanner.market.get(sym)
                        if not old: continue
                        bid,ask=num(e.get("b")),num(e.get("a")); mid=(bid+ask)/2 if bid and ask else None
                        spread=((ask-bid)/mid*10000) if mid and ask>=bid else None
                        d=old.model_dump(); d.update(bid=bid,ask=ask,spread_bps=spread,received_at=now,source="bookTicker")
                        s=MarketState(**d); self.scanner.update(s)
                        await self.publish({"type":"market_update","market":s.model_dump()})
            except asyncio.CancelledError: raise
            except Exception as ex:
                self.book_connected=False; self.reconnects+=1; log.warning("book feed reconnect: %s",ex)
                await asyncio.sleep(delay); delay=min(delay*2,60)
    async def klines(self, symbols, interval='1m'):
        streams='/'.join(f"{sym.lower()}@kline_{interval}" for sym in symbols)
        url=settings.ws_base.replace('/ws','/stream')+f"?streams={streams}"
        delay=1
        while True:
            try:
                async with websockets.connect(url,ping_interval=20,ping_timeout=20,max_size=2**22) as ws:
                    self.kline_shards_connected+=1; delay=1
                    try:
                        async for raw in ws:
                            msg=json.loads(raw); data=msg.get('data',{}); k=data.get('k')
                            if not k or not k.get('x'): continue
                            sym=data.get('s')
                            candle={'open_time':k.get('t'),'open':num(k.get('o')),'high':num(k.get('h')),
                              'low':num(k.get('l')),'close':num(k.get('c')),'volume':num(k.get('v'))}
                            if not all(candle[x] is not None for x in ('open','high','low','close','volume')): continue
                            if interval=='1m':
                                for sig in self.scanner.update_candle(sym,candle):
                                    await self.publish({'type':'signal_update','signal':sig.model_dump()})
                            else:
                                for sig in self.scanner.update_higher_candle(sym,interval,candle):
                                    await self.publish({'type':'signal_update','signal':sig.model_dump()})
                    finally:
                        self.kline_shards_connected=max(0,self.kline_shards_connected-1)
            except asyncio.CancelledError: raise
            except Exception as ex:
                self.reconnects+=1; log.warning('%s kline shard reconnect: %s',interval,ex)
                await asyncio.sleep(delay); delay=min(delay*2,60)
    async def warmup(self):
        # Historical candles for each contract: 1m strategy context plus 1h/4h regime context.
        sem=asyncio.Semaphore(4)
        async with httpx.AsyncClient(timeout=25) as client:
            async def load(sym,interval,limit):
                async with sem:
                    try:
                        r=await client.get(settings.rest_base+'/fapi/v1/klines',params={'symbol':sym,'interval':interval,'limit':limit})
                        r.raise_for_status()
                        for row in r.json()[:-1]:
                            candle={'open_time':row[0],'open':num(row[1]),'high':num(row[2]),'low':num(row[3]),'close':num(row[4]),'volume':num(row[5])}
                            if interval=='1m': self.scanner.update_candle(sym,candle)
                            else: self.scanner.update_higher_candle(sym,interval,candle)
                    except Exception as ex: log.warning('%s candle warmup failed for %s: %s',interval,sym,ex)
                    await asyncio.sleep(.25)  # keep REST request weight below a sustained burst
            jobs=[]
            for sym in self.symbols:
                jobs.extend((load(sym,'1m',500),load(sym,'1h',100),load(sym,'4h',100)))
            await asyncio.gather(*jobs)
    async def dynamic_klines(self):
        active_symbols=set(self.symbols); workers=[]
        def start_workers(symbols):
            tasks=[]
            for interval in ('1m','1h','4h'):
                tasks.extend(asyncio.create_task(self.klines(symbols[i:i+100],interval)) for i in range(0,len(symbols),100))
            return tasks
        workers=start_workers(list(active_symbols))
        while True:
            try:
                await self.discover(); current=set(self.symbols)
                if current!=active_symbols:
                    added=current-active_symbols
                    if added:
                        sem=asyncio.Semaphore(4)
                        async with httpx.AsyncClient(timeout=25) as client:
                            async def warm_one(sym,interval,limit):
                                async with sem:
                                    try:
                                        r=await client.get(settings.rest_base+'/fapi/v1/klines',params={'symbol':sym,'interval':interval,'limit':limit})
                                        r.raise_for_status()
                                        for row in r.json()[:-1]:
                                            candle={'open_time':row[0],'open':num(row[1]),'high':num(row[2]),'low':num(row[3]),'close':num(row[4]),'volume':num(row[5])}
                                            if interval=='1m': self.scanner.update_candle(sym,candle)
                                            else: self.scanner.update_higher_candle(sym,interval,candle)
                                    except Exception as ex: log.warning('new-symbol %s warmup failed %s: %s',interval,sym,ex)
                                    await asyncio.sleep(.25)
                            await asyncio.gather(*(warm_one(sym,tf,lim) for sym in added for tf,lim in (('1m',500),('1h',100),('4h',100))))
                    for worker in workers: worker.cancel()
                    await asyncio.gather(*workers,return_exceptions=True)
                    workers=start_workers(list(current)); active_symbols=current
                    log.info('dynamic symbol universe refreshed: %d contracts (%d added)',len(current),len(added))
            except asyncio.CancelledError: raise
            except Exception as ex: log.warning('symbol refresh failed; keeping prior subscriptions: %s',ex)
            await asyncio.sleep(300)

    async def run(self):
        delay=2
        while True:
            try:
                count=await self.discover()
                if count: break
            except asyncio.CancelledError: raise
            except Exception as ex: log.warning('initial exchangeInfo discovery failed: %s',ex)
            await asyncio.sleep(delay); delay=min(delay*2,60)
        await asyncio.gather(self.ticker(),self.book(),self.dynamic_klines(),self.warmup())
