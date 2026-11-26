# Free local Android mode

The Android app connects directly to Binance public Futures endpoints. No backend host, API key, paid database, paid AI API, or Play Store registration is required for private APK installation.

## Lifecycle
- App launched: discovers eligible USDT perpetuals, opens ticker/book/kline WebSockets, warms candle history.
- App active: local strategy evaluation runs on closed 1m candles; independent risk checks gate candidates.
- If a validated research artifact is installed, the app performs calibrated Logistic Regression inference locally and labels it as a shadow estimate.
- Missing, malformed or disabled model artifact: app shows no AI probability and remains rule-ranked.
- App closed/force-stopped/process killed/phone off: no continuous scan.
- App reopened: reconnects, refreshes contracts, warms history and resumes.

## Scope and limitations
Ticker, top-of-book and closed 1m candle data feed seven transparent strategy families. This is not a full-depth order-book system. Open interest/funding/liquidation intelligence is not yet part of the Android local model. Fee and slippage values are assumptions and need replacement with the user's actual costs. No win-rate or profit is guaranteed.

## Free distribution
Build a debug APK using the included GitHub Actions workflow or Android Studio. Install privately; Play Store publication is not required.
