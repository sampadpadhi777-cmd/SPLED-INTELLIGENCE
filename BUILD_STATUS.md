# SPLED INTELLIGENCE 1.0.0 — build status

## Changes in 1.0.0
- Added a dedicated Paper Trading tab.
- Automatically opens virtual positions when a new scanner idea passes the existing market/risk filters.
- Virtual account starts at 1,000 USDT, unlevered 1x notional, maximum five open positions.
- Position risk budget 0.5% of virtual equity and per-position notional capped at 20% of equity.
- Paper net P&L estimates 0.05% fee and 0.02% slippage per side.
- Virtual positions monitor live prices once per second and close at structural stop, target or four-hour time limit.
- Paper history persists locally across normal app restarts.
- Added one-second local scan cycles and UI scan counters.
- All live pairs are checked each second; strategy calculations use completed 1-minute candles and recalculate when a new candle closes, preventing repeated signals and unnecessary CPU use.
- App version is 1.0.0 (versionCode 10).

## Existing scanner
- Binance USDⓈ-M USDT perpetual universe discovery.
- All-market ticker and book ticker streams.
- Sharded 1-minute kline streams.
- Seven rule-based strategies, structural stop and target logic, and risk gates.
- No exchange order execution and no API key.

## Verification in packaging environment
- Python backend tests: 25 passed.
- Python compilation: passed.
- Source archive integrity: to be checked after final packaging.
- Android Kotlin/Compose compilation: not run here; Android SDK/Gradle are not installed in this environment.
- Physical Android device, Binance WebSocket stability and paper fill behaviour: not tested on device.
- No trained/validated AI model or live profitability claim is included.

## Release status
Research and paper-trading test build only. GitHub Actions must compile the APK, then the APK must be installed and tested on the user's Android phone. Do not use for real-money decisions until independent forward validation is complete.
