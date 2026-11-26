# SPLED INTELLIGENCE 0.8.0

**Hybrid AI research and manual-trading assistant for Binance USDⓈ-M Futures.** Android scanner is phone-local; Python handles historical research/training. The app only produces analysis and manual trade plans. It never places orders.

## What is in this build
- Public Binance Futures market-data scanner for eligible USDT perpetual contracts.
- Seven strategy candidate families and market-structure references.
- Independent risk gate with spread/freshness/liquidity checks and estimated fee/slippage-adjusted reward/risk.
- Local signal journal with conservative closed-candle target/stop resolution.
- Seven-strategy historical label generator, purged chronological model training, calibration, holdout metrics and portable Android model export.
- Kotlin on-device inference for a calibrated Logistic Regression model. The checked-in model asset is intentionally disabled until genuine historical research passes the screening gate.

## Important truth
This ZIP is a **source package**, not a verified APK or a proven profitable strategy. No real historical model was trained during packaging. The current model asset is disabled and the app must show no AI probability. A successful historical screening gate only enables a shadow estimate; it does not approve real-money use.

## Build APK
Open `android/` in Android Studio or run `gradle assembleDebug` with JDK 17, Android SDK 35 and Gradle 8.10.2. GitHub Actions workflow `.github/workflows/android-build.yml` runs tests then attempts the debug APK build. Download the APK only after the workflow reports success.

## Train and export a model
See `backend/research/README.md`. Short version:

```bash
cd backend
pip install -r research/requirements.txt
python research/download_binance_history.py --symbols BTCUSDT,ETHUSDT,SOLUSDT,BNBUSDT,XRPUSDT --days 180 --out research/data_1m.csv
python research/label_dataset.py --csv research/data_1m.csv --out research/candidates.csv --horizon 240 --fee-side 0.0005 --slippage-round-trip 0.001
python research/train_walkforward.py --csv research/candidates.csv --out research/artifacts/target_first.joblib --min-oos 200 --android-out ../android/app/src/main/assets/hybrid_model.json
```

If the historical gate fails, the exported Android artifact stays disabled. Rebuild the APK after exporting a passing shadow artifact. Review the metrics and test feature parity first.

## Operating limits
- Phone-local scanner runs only while Android keeps the app process alive and internet is available.
- No paid hosting, AI API, or exchange API key is required.
- No automatic trading, leverage changes, or order placement.
- Costs in the app are assumptions (0.05% fee per side + 0.10% round-trip slippage) and must be replaced with the actual account's costs before relying on net R:R.
- No win-rate or profit guarantee. Forward shadow validation and device tests are mandatory.

See `BUILD_STATUS.md`, `docs/RELEASE_GATES.md`, and `docs/FREE_LOCAL_MODE.md`.
