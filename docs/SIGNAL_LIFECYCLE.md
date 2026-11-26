# Signal lifecycle

Each suggestion starts as `ACTIVE`. On fresh last-traded ticker updates:
- LONG becomes `TARGET_HIT` at/above target or `STOP_HIT` at/below structural stop.
- SHORT becomes `TARGET_HIT` at/below target or `STOP_HIT` at/above structural stop.
- `EXPIRED` after four hours if neither level was reached.

Lifecycle changes are emitted as `signal_update` WebSocket events and appended to the SQLite journal. The current in-memory snapshot retains the latest state per strategy/direction key.

**Limitations:** this is a last-traded-price observation, not an exchange fill. Gaps, spread, latency, partial fills, funding and market impact can make realized results different. A target/stop crossing is not independently verified realized P&L or a calibrated strategy win rate.
