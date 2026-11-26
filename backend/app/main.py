import asyncio,json,time,logging
from contextlib import asynccontextmanager
from fastapi import FastAPI,WebSocket,WebSocketDisconnect
from .scanner import Scanner
from .binance import BinanceFeed
from .risk import size_position
from .store import SignalStore
from .derivatives import DerivativesPoller
from .analytics import summarize_candles
from .models import Signal
from .flow import FlowMonitor
logging.basicConfig(level=logging.INFO)
scanner=Scanner(); clients=set(); feed=None; task=None; derivative_task=None; flow=None; flow_tasks=[]; batch_task=None; market_buffer={}
store=SignalStore()
async def broadcast(msg):
    dead=[]
    payload=json.dumps(msg,separators=(',',':'))
    for ws in list(clients):
        try: await ws.send_text(payload)
        except Exception: dead.append(ws)
    for ws in dead: clients.discard(ws)

async def publish(msg):
    # Coalesce high-frequency quote updates into one per-second batch. Signals and
    # critical events bypass the batch and are pushed immediately to connected apps.
    if msg.get('type')=='market_update':
        market=msg.get('market') or {}
        if market.get('symbol'): market_buffer[market['symbol']]=market
        return
    await broadcast(msg)

async def market_batch_loop():
    while True:
        await asyncio.sleep(1)
        if market_buffer:
            rows=list(market_buffer.values()); market_buffer.clear()
            await broadcast({'type':'market_batch','markets':rows})
@asynccontextmanager
async def lifespan(app):
    global feed,task,derivative_task,flow,flow_tasks,batch_task
    scanner.signals.update({k: Signal(**v) for k,v in store.latest_by_signal().items()})
    async def durable_publish(msg):
        if msg.get('type')=='signal_update': store.append(msg['signal'])
        await publish(msg)
    feed=BinanceFeed(scanner,durable_publish)
    task=asyncio.create_task(feed.run())
    batch_task=asyncio.create_task(market_batch_loop())
    derivative_task=asyncio.create_task(DerivativesPoller(scanner,durable_publish).run())
    flow=FlowMonitor(scanner,durable_publish)
    flow_tasks=[asyncio.create_task(flow.liquidations_stream()),asyncio.create_task(flow.large_trades_stream())]
    yield
    all_tasks=[task,derivative_task,batch_task,*flow_tasks]
    for t in all_tasks:
        if t: t.cancel()
    for t in all_tasks:
        if t:
            try: await t
            except asyncio.CancelledError: pass
app=FastAPI(title='SPLED INTELLIGENCE API',version='1.0.0',lifespan=lifespan)
@app.get('/health')
async def health():
    return {'status':'connected' if feed and feed.connected else 'degraded','eligible_symbols':len(feed.symbols) if feed else 0,
      'tracked_symbols':len(scanner.market),'last_event_age_seconds':time.time()-feed.last_event if feed and feed.last_event else None,
      'reconnects':feed.reconnects if feed else 0,'book_feed_connected':feed.book_connected if feed else False,'kline_shards_connected':feed.kline_shards_connected if feed else 0,'decision_engine':'7-strategy explainable ensemble; not ML validated', 'strategies':['BREAKOUT_RETEST','EMA_TREND_PULLBACK','MOMENTUM_EXPANSION','BOLLINGER_MEAN_REVERSION','VWAP_RECLAIM','MACD_MOMENTUM_CROSS','RANGE_EXPANSION'], 'evaluations':scanner.eval_count,'signal_history':'sqlite durable journal','derivatives_metrics':'bulk mark/index/funding plus rotating open-interest polling across all tracked symbols','flow_metrics':'all-symbol aggTrade shards with adaptive large-print flags plus global force-order stream'}
@app.get('/snapshot')
async def snapshot(): return scanner.snapshot()

@app.get('/strategies')
async def strategies():
    return {'active':['BREAKOUT_RETEST','EMA_TREND_PULLBACK','MOMENTUM_EXPANSION','BOLLINGER_MEAN_REVERSION','VWAP_RECLAIM','MACD_MOMENTUM_CROSS','RANGE_EXPANSION'], 'universe':'all eligible Binance USD-M USDT perpetuals', 'evaluation':'event-driven per symbol', 'probabilities':'not calibrated; no win-rate claims'}
@app.get('/signals')
async def signals(limit: int = 250): return store.recent(max(1,min(limit,1000)))

@app.get('/analytics/{symbol}')
async def analytics(symbol: str):
    return summarize_candles(list(scanner.candles[symbol.upper()]))

@app.get('/flow/{symbol}')
async def trade_flow(symbol: str):
    if not flow: return {'status':'starting'}
    return flow.summary(symbol.upper())

@app.get('/liquidations')
async def liquidations(limit: int = 100):
    if not flow: return []
    return list(flow.liquidations)[-max(1,min(limit,500)):][::-1]

@app.get('/whale-alerts')
async def whale_alerts(limit: int = 100):
    if not flow: return []
    return list(flow.whale_alerts)[-max(1,min(limit,250)):][::-1]
@app.get('/market/{symbol}')
async def market(symbol: str):
    item=scanner.market.get(symbol.upper())
    if not item: return {'error':'symbol_not_yet_seen'}
    return item.model_dump()
@app.post('/risk/size')
async def risk_size(payload: dict):
    try:
        plan=size_position(float(payload['entry']),float(payload['stop']),float(payload['risk_budget_usdt']),float(payload['max_notional_usdt']),float(payload['target']) if payload.get('target') is not None else None,float(payload.get('fee_per_side',0.0005)),float(payload.get('slippage_round_trip',0.001)))
        return plan.__dict__
    except (KeyError,TypeError,ValueError) as exc: return {'error':str(exc)}
@app.websocket('/ws')
async def socket(ws:WebSocket):
    await ws.accept(); clients.add(ws)
    snap=scanner.snapshot(); snap['history']=store.recent(250)
    await ws.send_text(json.dumps({'type':'snapshot','data':snap}))
    try:
        while True: await ws.receive_text()
    except WebSocketDisconnect: clients.discard(ws)
    except Exception: clients.discard(ws)
