# SPLED INTELLIGENCE 1.0.0 — Local scan and paper trading

## Startup
- The Android app starts `LocalMarketEngine` automatically when the main screen enters composition.
- It discovers currently trading Binance USDⓈ-M USDT perpetual contracts using public endpoints.
- It consumes all-market ticker and book ticker streams and sharded 1-minute kline streams.
- Eligible contracts are refreshed every five minutes; newly listed eligible contracts are warmed up and subscribed.

## One-second refresh semantics
- A local scan cycle runs once per second over the latest cached market state.
- Each cycle checks fresh markets, updates paper positions using current ticker/bid/ask data, and refreshes the UI scan timestamp/cycle counter.
- Strategy conditions use completed 1-minute candles only. Strategy calculations are repeated when a new completed candle arrives, not every second, to avoid duplicate signals and unnecessary phone CPU load.
- Therefore the screen updates every second, while candle-confirmed signal decisions normally change on a new 1-minute close. Market feed updates can arrive between UI cycles.

## Paper account
- Paper account starts with a virtual 1,000 USDT balance. This is simulated equity only, not a deposit or exchange balance.
- Maximum five concurrent paper positions.
- Position risk budget is 0.5% of current virtual equity, with a 20% equity notional cap per position.
- Simulation is unlevered (1x notional). No liquidation engine is simulated.
- Entry references the ask for LONG and bid for SHORT. Exit references bid for LONG and ask for SHORT, falling back to last traded price if a quote is temporarily missing.
- Estimated fee assumption: 0.05% per side. Estimated slippage assumption: 0.05% per side. These are fixed modelling assumptions, not a promise of actual exchange costs.
- Open paper positions are evaluated once per second against live last-traded price. Stop, target or four-hour time exit closes the virtual position. Net P&L deducts assumed fees and slippage.
- Open and closed paper trade records persist locally in SharedPreferences across normal app restarts.

## Important limitations
- Paper trading never submits exchange orders and requires no Binance API key.
- No trained/validated AI model is installed in this build. Strategy score is not a win probability.
- Paper fills are approximations, not actual order-book execution. Funding payments, liquidation, partial fills, queue position and exchange outages are not simulated.
- Scanning requires the app process to remain alive and internet access. Force-stop, process termination, phone shutdown or network loss pauses the scan. Android battery management may also suspend background activity.
- Not approved for real-money reliance. Validate the APK on-device and forward-test paper results before considering any live trading.
