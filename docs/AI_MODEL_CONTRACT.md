# Hybrid model artifact contract v1

The app loads `android/app/src/main/assets/hybrid_model.json`. Missing, malformed, dimension-mismatched, or `enabled_for_shadow=false` artifacts are rejected and produce no AI probability.

## Feature order and meaning

| Feature | Definition at the closed signal candle |
|---|---|
| `side` | LONG = +1, SHORT = -1 |
| `ema_gap_pct` | (EMA9 / EMA21 - 1) × 100; EMA uses adjust=false recursive smoothing |
| `rsi14` | 14-period simple-average RSI; no-loss window maps to 100 |
| `atr_pct` | ATR(14) / entry reference × 100 |
| `volume_ratio20` | Mean volume of latest 3 closed bars / mean volume of preceding 20 bars |
| `return_5m_pct` | 5 one-minute-bar close-to-close percentage return |
| `return_15m_pct` | 15 one-minute-bar close-to-close percentage return |
| `range_position20` | (signal close - prior-20-bar low) / (prior-20-bar high - prior-20-bar low) |
| `strategy_*` | One-hot flag for the candidate strategy family; exactly one set to 1 |

The Python label generator and Android scanner use the same named feature set and strategy-family flags. Their floating-point calculations and data-window warm-up can still differ slightly; numerical parity must be tested on shared candle fixtures before enabling a trained artifact.

## JSON fields

- `schema_version`: 1
- `model_type`: `standardized_logistic_regression_platt`
- `enabled_for_shadow`: true only when the historical screening gate passes
- `features`, `imputer_medians`, `scaler_means`, `scaler_scales`, `coefficients`: same-length ordered arrays
- `intercept`, `calibration_slope`, `calibration_intercept`: model and Platt calibration parameters
- `threshold`: fixed 0.60 research threshold
- `metrics`: untouched holdout and walk-forward summary

Android computes imputation → standardization → logistic score → Platt calibration locally. It labels output as a shadow estimate. It does not use the threshold to automatically approve a live trade and cannot grant live-money approval.
