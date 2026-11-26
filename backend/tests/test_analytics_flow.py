from app.analytics import summarize_candles
from app.flow import FlowMonitor

def test_analytics_requires_history():
    assert summarize_candles([])['ready'] is False

def test_analytics_returns_atr_and_range():
    candles=[{'close':100+i*.1,'high':101+i*.1,'low':99+i*.1,'volume':10} for i in range(25)]
    out=summarize_candles(candles)
    assert out['ready'] is True and out['atr_14']>0 and out['range_high_20']>out['range_low_20']

def test_flow_identifies_large_taker_trade_and_side():
    flow=FlowMonitor(None,None)
    alert=flow.on_trade({'s':'BTCUSDT','p':'100000','q':'2','m':False,'T':int(__import__('time').time()*1000)})
    assert alert['type']=='large_trade' and alert['taker_side']=='BUY'
    assert flow.summary('BTCUSDT')['taker_buy_notional']==200000


def test_aggregate_candles_only_emits_complete_buckets():
    from app.analytics import aggregate_candles
    candles = [
        {'open_time': i * 60_000, 'open': 10+i, 'high': 11+i, 'low': 9+i,
         'close': 10.5+i, 'volume': 1}
        for i in range(6)
    ]
    result = aggregate_candles(candles, 5)
    assert len(result) == 1
    assert result[0]['open_time'] == 0
    assert result[0]['open'] == 10
    assert result[0]['close'] == 14.5
    assert result[0]['volume'] == 5


def test_timeframe_bias_needs_enough_candles_and_detects_direction():
    from app.analytics import timeframe_bias
    assert timeframe_bias([]) is None
    rising = [{'close': 100+i, 'open': 100+i, 'high': 101+i, 'low': 99+i, 'volume': 1}
              for i in range(30)]
    falling = [{'close': 130-i, 'open': 130-i, 'high': 131-i, 'low': 129-i, 'volume': 1}
               for i in range(30)]
    assert timeframe_bias(rising) == 'LONG'
    assert timeframe_bias(falling) == 'SHORT'
