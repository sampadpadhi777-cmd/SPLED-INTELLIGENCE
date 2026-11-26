# Android phone install and use

## Build
1. Replace the old source ZIP in the GitHub repository with the latest `SPLED_INTELLIGENCE_v0.9.0_LOCAL_RADAR_UI.zip`.
2. Keep exactly one `SPLED_INTELLIGENCE*.zip` file in the repository root because the extraction workflow expects one archive.
3. The existing `unpack-source.yml` workflow should trigger on the ZIP push. If not, open Actions and run it manually.
4. Wait for Python tests, Android unit tests and `assembleDebug` to pass.
5. Download the APK from the successful run's Artifacts section.
6. Install the debug APK on the Android phone. Android may ask permission to install unknown apps from the browser/files app.

## First launch
- Keep mobile data/Wi-Fi on.
- Open SPLED INTELLIGENCE and leave it open while scanning.
- Wait for Binance contracts to be discovered and candle warm-up to finish. The market list may populate before all strategy candles are ready.
- Radar displays up to ten distinct symbols that pass the current strategy and risk filters. It can legitimately show fewer than ten or no trade.
- Markets shows received ticker updates. The discovered contract count and received market count are different measures.
- Journal records generated ideas and subsequent target/stop outcomes.

## Reading a recommendation
- ENTRY is a bid/ask-based reference, not a guaranteed fill.
- TARGET is the nearest visible swing obstacle if there is enough room; otherwise a 0.7% reference or minimum 1.6R distance is used.
- STOP LOSS is a structural invalidation level beyond a recent swing with an ATR buffer.
- R:R is the gross planned reward/risk reference; the risk gate also estimates costs before accepting a candidate.
- Rule score is a ranking score, not a win probability.
- If the original structure is invalidated, respect the stop. Do not widen it because recovery seems possible.

## Operating limits
- Scanning pauses when the app process is stopped/killed, phone is off or internet is lost.
- Public Binance streams can disconnect or be delayed. Recheck the live order book before considering any manual trade.
- No trained AI model is bundled or enabled. Do not interpret the rule score as AI confidence.
- This build is for paper/shadow evaluation, not proven live-money performance.
