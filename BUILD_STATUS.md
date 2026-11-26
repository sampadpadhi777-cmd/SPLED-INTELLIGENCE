# SPLED INTELLIGENCE 0.8.0 — build and verification status

## Implemented in this source package
- Android phone-local Binance USDⓈ-M public market-data scanner; no exchange API key and no order placement.
- Dynamic USDT perpetual discovery, ticker/book ticker feeds, sharded closed 1m kline streams, candle warm-up and reconnect handling.
- Seven rule-based candidate families: breakout, EMA pullback, momentum expansion, Bollinger mean reversion, VWAP reclaim, MACD cross and range expansion.
- Structural swing-based stop and adaptive target references; independent deterministic risk gate for feed freshness, spread, 24h quote volume, stop distance, gross R:R and estimated net R:R.
- Closed-candle signal lifecycle; stop-first rule when stop and target are both touched in one candle; local signal history.
- Seven-strategy historical candidate/label generator with next-candle-open entries, structural stop/target, opening-gap fills, conservative same-candle ambiguity and explicit fee/slippage assumptions.
- Chronological train/calibration/holdout workflow, purged overlap handling, walk-forward folds, metrics and portable JSON export.
- Dependency-free Kotlin on-device inference for an exported calibrated Logistic Regression artifact. The checked-in placeholder is intentionally disabled; without a passing historical research gate Android displays no AI probability.
- UI labels model output as a shadow estimate and explicitly blocks any implication of live approval.
- GitHub Actions workflow runs Python tests before attempting the Android debug APK build.

## Verified in this packaging environment
- Python source compilation: passed.
- Backend + research tests: 25 passed.
- Research trainer export test: passed; verifies a disabled portable Android artifact is emitted when the gate is forced to fail.
- ZIP integrity and SHA-256 manifest: checked during packaging.

## Not verified / still release blockers
- Android/Kotlin compilation and APK assembly: no Android SDK/Gradle toolchain is installed in this runtime. GitHub Actions must complete successfully.
- Real historical dataset collection and a trained model: no genuine Binance history was downloaded or trained in this packaging session. `android/app/src/main/assets/hybrid_model.json` is deliberately disabled.
- Python-to-Kotlin numerical feature parity on real candle fixtures: must be run and reviewed before enabling a model artifact.
- Binance live network behaviour, rate limits, WebSocket stability and physical Android device operation.
- Actual user-specific trading fees, slippage, funding, exchange filters, account margin and liquidation constraints.
- Forward shadow validation and independent release/security review.

## Operating model
Strictly free, phone-local Android scanner using public Binance USDⓈ-M market data. Scanning pauses if Android kills/stops the process, the app is force-stopped, the phone is off or connectivity is lost. Research/training is a separate free Python workflow. The app never sends exchange orders.

## Release decision
**NOT APPROVED FOR REAL-MONEY RELIANCE.** Source implementation is substantially complete for research and shadow testing, but no trained model or APK has been verified here, and profitability has not been established. Do not treat the app as a trusted live trading system until all release blockers and forward validation pass.
