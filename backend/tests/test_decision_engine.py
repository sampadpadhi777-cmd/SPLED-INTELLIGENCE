from app.decision_engine import decide, ema
from app.risk import size_position

def test_ema_requires_enough_values():
    assert ema([1,2,3], 5) is None

def test_no_signal_without_enough_candles():
    assert decide([], 100, 1, 10_000_000) is None

def test_position_size_respects_risk_budget():
    plan=size_position(100,98,25,10000,target=103,fee_per_side=.0005,slippage_round_trip=.001)
    assert plan.estimated_total_stop_risk_usdt <= 25.000001
    assert plan.notional <= 10000.0001
    assert plan.estimated_net_target_usdt > 0

def test_invalid_risk_inputs_rejected():
    try: size_position(100,100,25,10000)
    except ValueError: pass
    else: assert False

def test_breakout_retest_candidate_is_explicitly_not_validated():
    candles=[]
    for i in range(60):
        candles.append({'open_time':i,'open':100,'high':100.1,'low':99.9,'close':100,'volume':1})
    candles[-2]={'open_time':58,'open':100,'high':100.6,'low':100.3,'close':100.5,'volume':2}
    candles[-1]={'open_time':59,'open':100.5,'high':100.55,'low':100.05,'close':100.4,'volume':2}
    result=decide(candles,100.4,2,5_000_000)
    assert result is not None
    assert result.direction=='LONG'
    assert result.status=='RULE_BASED_CANDIDATE_NOT_BACKTEST_VALIDATED'
    assert result.stop < result.entry < result.target


def test_risk_plan_rejects_target_on_wrong_side():
    try:
        size_position(100, 98, 25, 10000, target=97)
    except ValueError as exc:
        assert 'target must be above entry' in str(exc)
    else:
        assert False

def test_strategy_ensemble_returns_list_and_rejects_unwarmed_history():
    from app.strategies import evaluate_all
    assert evaluate_all([],100,1,10_000_000,1_000_000,15,{}) == []

def test_strategy_setup_levels_are_directionally_valid():
    from app.strategies import _build
    candles=[]
    for i in range(100):
        base=100+i*.03
        candles.append({'open_time':i*60000,'open':base,'high':base+.15,'low':base-.15,'close':base+.05,'volume':100})
    long=_build('TEST','LONG',candles,103,75,['test'])
    short=_build('TEST','SHORT',candles,103,75,['test'])
    assert long and long.stop<long.entry<long.target
    assert short and short.target<short.entry<short.stop

def test_ensemble_can_emit_multiple_independent_strategy_matches():
    from app.strategies import evaluate_all
    candles=[]
    for i in range(100):
        p=100+i*.01
        candles.append({'open_time':i*60000,'open':p,'high':p+.05,'low':p-.05,'close':p+.02,'volume':100})
    for j in range(3):
        p=101.2+j*.25
        candles[-3+j]={'open_time':(97+j)*60000,'open':p-.1,'high':p+.15,'low':p-.12,'close':p+.12,'volume':250}
    found=evaluate_all(candles,101.9,1,10_000_000,1_000_000,15,
      {'5m':'LONG','15m':'LONG','1h':'LONG','4h':'LONG'})
    assert len(found)>=2
    assert len({x.strategy for x in found})==len(found)
    assert all(x.stop<x.entry<x.target for x in found if x.direction=='LONG')
