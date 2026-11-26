from pydantic import BaseModel
import os
class Settings(BaseModel):
    rest_base: str = os.getenv('BINANCE_REST_BASE','https://fapi.binance.com')
    ws_base: str = os.getenv('BINANCE_WS_BASE','wss://fstream.binance.com/ws')
    stale_after_seconds: float = float(os.getenv('STALE_AFTER_SECONDS','15'))
    min_quote_volume_usdt: float = float(os.getenv('MIN_QUOTE_VOLUME_USDT','1000000'))
    max_spread_bps: float = float(os.getenv('MAX_SPREAD_BPS','15'))
    target_move_pct: float = float(os.getenv('REFERENCE_TARGET_MOVE_PCT','0.7'))
settings = Settings()
