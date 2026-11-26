# SPLED INTELLIGENCE — production release gates

This checklist deliberately separates a working engineering baseline from a production trading-decision product. A release gate is complete only when evidence is attached (test report, deployment record, device test, or review), not merely because code exists.

## Market data integrity
- [ ] Validate every subscribed stream against Binance USD-M Futures documentation and current exchange filters.
- [ ] Track per-stream connection state and freshness independently (ticker, book, klines, derivatives, trades, liquidations).
- [ ] Persist raw or reproducible market data sufficient to audit every historical decision.
- [ ] Detect missing candles, duplicate events, clock skew, sequence gaps, stale book snapshots and REST/WebSocket divergence.
- [ ] Add exchange request-weight tracking, adaptive throttling and recovery tests under disconnects.
- [ ] Reconstruct depth from snapshot + diff-depth sequence before using order-book imbalance as a signal feature.

## Decision quality
- [x] Load completed 1m/1h/4h candles and derive 5m/15m context for strategy gating (implementation present; validation remains open).
- [x] Implement target/stop event labels with next-candle-open entry, structural stop/target, opening-gap fills and conservative same-candle ordering (latency/partial fills remain open).
- [x] Add purged chronological training/calibration/test research pipeline and expanding walk-forward reporting (code present; dataset-specific validation still open).
- [ ] Complete cross-regime validation on a sufficiently large point-in-time dataset; report calibration, Brier score, log loss, precision/recall, expectancy, drawdown and sample size.
- [ ] Include fees, spread, slippage, funding, market impact, partial fills and missed fills in every performance report.
- [x] Implement Platt calibration on a separate chronological calibration period and suppress Android output for disabled/malformed artifacts (real dataset validation remains open).
- [x] Implement active/target/stop/expired lifecycle with local signal history; invalidation audit remains incomplete.
- [ ] Add drift monitoring and automatic no-signal mode when live inputs leave validated ranges.

## Risk and execution guidance
- [ ] Validate exchange tick size, step size, min quantity and min notional before showing executable quantity.
- [ ] Include funding and expected holding duration in net PnL estimates.
- [ ] Add account-level risk caps, aggregate correlated exposure, daily loss guard and max concurrent ideas (current risk gate is per-idea and market-data based only).
- [ ] Add liquidation-distance estimates using leverage and maintenance-margin tiers; clearly mark approximations.
- [ ] Never represent a heuristic score as win probability or a candidate as an instruction to trade.

## Product and operations
- [ ] Complete Android navigation, detail screen, charts, filters, settings and accessible error states.
- [ ] Add FCM alerts with user-configurable severity and quiet hours.
- [ ] Deploy behind TLS, authentication, authorization, rate limiting and secrets management.
- [ ] Move multi-instance persistence/fanout to managed PostgreSQL/TimescaleDB and Redis where justified.
- [ ] Add metrics, logs, alerting, backups, restore drills, deployment rollback and load tests.
- [ ] Build signed release APK/AAB and test on physical Android devices across reconnect, background, offline and battery scenarios.
- [ ] Complete independent security review and paper/live-shadow acceptance period before real-money use.

## Current release decision

**Not production-ready.** The 0.8.0 ZIP is source code for a phone-local scanner, seven strategy families, an independent per-idea risk gate, a chronological research/training pipeline, and Kotlin inference for a portable calibrated model artifact. The checked-in model artifact is intentionally disabled because no genuine historical dataset was trained and validated in this packaging session. Cloud hosting, production authentication, push notifications, calibrated live ML inference, full-depth order-book reconstruction, signed APK, physical-device testing and independent forward validation remain release blockers.
