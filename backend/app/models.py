from pydantic import BaseModel
from typing import Optional
class MarketState(BaseModel):
    symbol: str
    price: Optional[float] = None
    change_24h_pct: Optional[float] = None
    quote_volume_24h: Optional[float] = None
    bid: Optional[float] = None
    ask: Optional[float] = None
    spread_bps: Optional[float] = None
    mark_price: Optional[float] = None
    index_price: Optional[float] = None
    funding_rate: Optional[float] = None
    next_funding_time_ms: Optional[int] = None
    open_interest: Optional[float] = None
    event_time_ms: Optional[int] = None
    received_at: float
    source: str
class Signal(BaseModel):
    symbol: str
    direction: str
    mode: str
    score: float
    entry_reference: float
    target_reference: float
    structural_stop_reference: Optional[float] = None
    risk_reward: Optional[float] = None
    rationale: list[str]
    invalidation: list[str]
    target_before_stop_probability: Optional[float] = None
    probability_status: str = "NOT_VALIDATED"
    created_at: float
    data_age_seconds: float
    status: str = 'ACTIVE'
    resolved_at: Optional[float] = None
    exit_reference: Optional[float] = None
    outcome_note: Optional[str] = None
