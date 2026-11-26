from dataclasses import dataclass

@dataclass
class RiskPlan:
    quantity: float
    notional: float
    structural_price_risk_usdt: float
    estimated_stop_costs_usdt: float
    estimated_total_stop_risk_usdt: float
    gross_target_usdt: float
    estimated_round_trip_fee_usdt: float
    estimated_slippage_usdt: float
    estimated_net_target_usdt: float


def size_position(entry: float, stop: float, risk_budget_usdt: float,
                  max_notional_usdt: float, target: float | None = None,
                  fee_per_side: float = 0.0005,
                  slippage_round_trip: float = 0.001) -> RiskPlan:
    """Size from structural stop and include estimated round-trip costs in risk cap."""
    distance = abs(entry-stop)
    if entry <= 0 or distance <= 0 or risk_budget_usdt <= 0 or max_notional_usdt <= 0:
        raise ValueError('entry, stop, risk budget and max notional must be positive; stop must differ from entry')
    if fee_per_side < 0 or slippage_round_trip < 0:
        raise ValueError('fee and slippage assumptions cannot be negative')
    # Infer direction from the structural stop and reject impossible target geometry.
    is_long = stop < entry
    if target is not None and ((is_long and target <= entry) or (not is_long and target >= entry)):
        raise ValueError('target must be above entry for a long stop or below entry for a short stop')
    cost_per_unit = entry * (2*fee_per_side + slippage_round_trip)
    qty_by_risk = risk_budget_usdt / (distance + cost_per_unit)
    qty_by_notional = max_notional_usdt / entry
    qty = min(qty_by_risk, qty_by_notional)
    notional = qty*entry
    price_risk = qty*distance
    fees = notional * (2*fee_per_side)
    slip = notional * slippage_round_trip
    stop_costs = fees + slip
    gross_target = qty*abs(target-entry) if target is not None else 0.0
    net_target = gross_target - stop_costs
    return RiskPlan(qty, notional, price_risk, stop_costs, price_risk+stop_costs,
                    gross_target, fees, slip, net_target)
