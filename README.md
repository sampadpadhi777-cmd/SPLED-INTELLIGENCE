# SPLED INTELLIGENCE 1.0.0 — One-Second Radar + Paper Trading

**Android-first Binance USDⓈ-M Futures market-intelligence scanner.** It reads public market data, ranks candidate setups and produces manual trade plans. It never places exchange orders.

## What this build actually does

- Discovers eligible `TRADING` USDⓈ-M `PERPETUAL` contracts with `USDT` as quote asset from Binance `exchangeInfo` at runtime. The count is dynamic; it is not hard-coded to 500.
- Reads Binance public 24-hour ticker and best-bid/ask streams, plus sharded closed 1-minute kline streams (80 symbols per stream connection).
- Warms up candle history and evaluates seven rule-based candidate families: breakout, EMA pullback, momentum expansion, Bollinger mean reversion, VWAP reclaim, MACD cross and range expansion.
- Applies spread, liquidity, feed freshness, structural stop distance, gross R:R and estimated net R:R filters.
- Prefers the nearest visible swing obstacle as target when it provides enough room; otherwise uses a 0.7% reference target or a minimum 1.6R target distance.
- Places a structural invalidation stop beyond recent swing structure with an ATR buffer. It does not widen stops to wait for a hoped-for recovery.
- Shows the top 10 distinct symbols by rule score after risk filters, with entry reference, target, stop, R:R and rationale.
- Stores the latest signal journal locally and resolves target/stop on subsequent closed candles. If target and stop are both touched in one candle, the conservative rule counts the stop first.
- Uses a custom dark UI and SPLED mark, with Radar, Markets, Paper, Journal and System screens.
- Runs a one-second local scan cycle across fresh cached markets, updates the dashboard and monitors paper positions. Strategy decisions are recalculated on completed 1-minute candles to avoid duplicate signals and excessive phone CPU use.
- Automatically opens virtual paper positions when eligible ideas pass the existing scanner and risk filters. The paper account starts with 1,000 virtual USDT, uses 1x notional, caps at five positions, sizes to 0.5% equity risk and 20% equity notional per position, and records assumed fees/slippage. Positions exit at stop, target or four-hour time limit.

## Critical limitations

- **This is a rule-based decision-support build, not a trained AI product.** The checked-in model asset is intentionally disabled. A score such as 82 points is a rule rank, not an 82% win probability.
- No verified win rate or profitability claim is made. Historical training code is research tooling; a genuine trained model must pass chronological out-of-sample validation and forward shadow testing before it is enabled.
- The scanner reads 1-minute candles. It does not claim a fully verified multi-timeframe 15m/1h/4h signal model.
- Public data and WebSocket delivery can be delayed or disconnected. A live-looking UI is not an execution guarantee.
- Phone-local scanning runs only while Android keeps the app process alive and internet is available. Force-stop, process death, battery restrictions, phone shutdown or network loss pauses scanning.
- The stop is a pre-defined structural invalidation level, not a probability that price will recover. Never widen it after entry to avoid realizing a loss.
- Estimated costs use the risk engine assumptions: 0.05% fee per side plus 0.10% round-trip slippage. Replace these with actual account costs before relying on net R:R. Funding is not currently included in the Android estimate.
- Manual execution only. No exchange API key, order placement, order modification or cancellation.

## Build

Use JDK 17, Android SDK 35 and Gradle 8.10.2. The GitHub Actions workflow in the repository runs Python tests, Android unit tests and assembles a debug APK. Download the APK only after a successful workflow and install it on a test device first.

## Real-money release gates

1. Android compilation and physical-device installation pass.
2. Live Binance stream stability, reconnection, universe count and rate-limit behaviour are observed on the actual phone.
3. Historical walk-forward testing includes fees, slippage, funding, conservative candle ambiguity and multiple market regimes.
4. Forward shadow testing records at least 200 resolved signals over at least 30 calendar days, extending the period if the sample is insufficient.
5. Positive net expectancy, acceptable drawdown and reliable calibration are demonstrated out of sample.

Until those gates pass, use paper/shadow mode only. Paper fills are approximations and do not include funding, liquidation, partial fills or queue position. No system can guarantee a winning trade.


## 1.0.0 Paper Trading
The Android app starts the phone-local scanner when opened. A one-second scan cycle checks cached live market data and updates the dashboard; strategy decisions remain confirmed on completed 1-minute candles. A local simulated paper account automatically records eligible signals with virtual position sizing, assumed fees/slippage, target/stop/time exits, and persistent history. It never submits exchange orders. See `docs/PAPER_TRADING_AND_REFRESH.md` for assumptions and limitations.
