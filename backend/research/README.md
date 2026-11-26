# SPLED Hybrid AI research pipeline

This pipeline builds seven-strategy candidate labels, trains a calibrated Logistic Regression baseline, evaluates it chronologically, and exports a dependency-free JSON artifact for Kotlin inference. It never places orders and never grants real-money approval.

## 1. Download historical Binance USD-M 1m candles

```bash
cd backend
pip install -r research/requirements.txt
python research/download_binance_history.py --symbols BTCUSDT,ETHUSDT,SOLUSDT,BNBUSDT,XRPUSDT --days 180 --out research/data_1m.csv
```

Start with liquid contracts and expand the universe. Record point-in-time eligible contracts to reduce survivorship bias. The public endpoint may have rate limits; download in batches and verify timestamp continuity.

## 2. Generate candidates for all seven Android strategy families

```bash
python research/label_dataset.py --csv research/data_1m.csv --out research/candidates.csv --horizon 240 --fee-side 0.0005 --slippage-round-trip 0.001
```

The seven strategy families are breakout, EMA pullback, momentum expansion, Bollinger mean reversion, VWAP reclaim, MACD cross and range expansion. Signals use closed candles; entry is the next candle OPEN. Stop is the previous 8-bar swing plus an ATR buffer. Target is max(1.6R, 0.30% of entry). Same-candle stop/target ambiguity is STOP_FIRST. Opening gaps are filled at the opening price. Fee and slippage inputs are assumptions: replace them with actual account tier and observed execution data.

## 3. Train, calibrate and validate

```bash
python research/train_walkforward.py --csv research/candidates.csv --out research/artifacts/target_first.joblib --min-oos 200 --android-out ../android/app/src/main/assets/hybrid_model.json
```

The trainer uses chronological train/calibration/holdout partitions, purges overlapping label horizons, and computes expanding walk-forward folds. The portable Android JSON is enabled for SHADOW inference only if the historical screening gate passes: at least 500 holdout candidates, at least 200 selected at the fixed 0.60 threshold, positive net mean return, profit factor above 1.2, ROC-AUC above 0.52, Brier below 0.25, drawdown better than -15%, and at least three walk-forward folds. These are screening gates, not proof of future profitability.

If the gate fails, the exported artifact has `enabled_for_shadow=false`; Android deliberately shows no model probability. The app's checked-in placeholder is also disabled. Replace it only with a real generated artifact and rebuild the APK.

## 4. Android feature contract

The exported feature names and order are embedded in the JSON. Android calculates the same point-in-time feature vector, performs imputation/scaling/logistic inference and Platt calibration locally. The model probability is labelled `SHADOW MODEL ESTIMATE`, never guaranteed win probability. Feature-parity tests and device validation are required before use.

## 5. Required before any live use

- Independently audit data continuity, candidate/label parity and model feature parity.
- Model spread, actual taker fees, funding, latency, partial/missed fills and exchange precision/min-notional rules.
- Evaluate multiple market regimes and out-of-sample symbols; report expectancy, PF, drawdown, calibration and confidence intervals.
- Complete forward shadow testing (minimum 30 days and 200 resolved signals, longer if needed).
- Test on the actual Android phone, inspect logs, battery/network recovery and APK integrity.
- Keep manual execution and independent risk controls. Historical gate pass is not live approval.
