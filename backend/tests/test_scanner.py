import time
from app.scanner import Scanner
from app.models import MarketState

def test_no_candidate_without_candle_structure():
    s=Scanner()
    m=MarketState(symbol='ABCUSDT',price=100,change_24h_pct=3,quote_volume_24h=5_000_000,
      spread_bps=2,received_at=time.time(),source='test')
    assert s.update(m)==[]

def test_low_volume_rejected():
    s=Scanner()
    m=MarketState(symbol='THINUSDT',price=1,change_24h_pct=4,quote_volume_24h=100,
      spread_bps=2,received_at=time.time(),source='test')
    assert s.update(m)==[]

def test_durable_signal_store_roundtrip(tmp_path):
    from app.store import SignalStore
    store=SignalStore(str(tmp_path/'signals.sqlite3'))
    item={'symbol':'BTCUSDT','direction':'LONG','created_at':123.0,'score':77}
    store.append(item)
    assert store.recent(1)[0]['symbol']=='BTCUSDT'
    assert store.latest_by_symbol()['BTCUSDT']['score']==77

def test_scanner_keeps_multiple_strategy_setups_for_same_symbol():
    from app.scanner import Scanner
    from app.models import MarketState
    candles=[]
    for i in range(100):
        p=100+i*.01
        candles.append({'open_time':i*60000,'open':p,'high':p+.05,'low':p-.05,'close':p+.02,'volume':100})
    for j in range(3):
        p=101.2+j*.25
        candles[-3+j]={'open_time':(97+j)*60000,'open':p-.1,'high':p+.15,'low':p-.12,'close':p+.12,'volume':250}
    scanner=Scanner()
    for c in candles: scanner.candles['TESTUSDT'].append(c)
    market=MarketState(symbol='TESTUSDT',price=101.9,quote_volume_24h=10_000_000,
      spread_bps=1,received_at=time.time(),source='test')
    emitted=scanner.update(market)
    assert len(emitted)>=2
    assert len({x.mode for x in emitted})==len(emitted)


def test_long_signal_lifecycle_marks_target_hit():
    from app.models import Signal
    scanner=Scanner()
    signal=Signal(symbol='BTCUSDT',direction='LONG',mode='TEST',score=80,
      entry_reference=100,target_reference=105,structural_stop_reference=98,
      rationale=[],invalidation=[],created_at=time.time()-10,data_age_seconds=0)
    scanner.signals['BTCUSDT:TEST:LONG']=signal
    market=MarketState(symbol='BTCUSDT',price=105.2,quote_volume_24h=10_000_000,
      spread_bps=1,received_at=time.time(),source='ticker')
    updates=scanner.update(market)
    assert len(updates)==1
    assert updates[0].status=='TARGET_HIT'
    assert updates[0].exit_reference==105.2


def test_short_signal_lifecycle_marks_stop_hit():
    from app.models import Signal
    scanner=Scanner()
    signal=Signal(symbol='ETHUSDT',direction='SHORT',mode='TEST',score=80,
      entry_reference=100,target_reference=95,structural_stop_reference=102,
      rationale=[],invalidation=[],created_at=time.time()-10,data_age_seconds=0)
    scanner.signals['ETHUSDT:TEST:SHORT']=signal
    market=MarketState(symbol='ETHUSDT',price=102.5,quote_volume_24h=10_000_000,
      spread_bps=1,received_at=time.time(),source='ticker')
    updates=scanner.update(market)
    assert len(updates)==1
    assert updates[0].status=='STOP_HIT'


def test_snapshot_reports_seven_strategies():
    assert Scanner().snapshot()['strategy_count']==7
