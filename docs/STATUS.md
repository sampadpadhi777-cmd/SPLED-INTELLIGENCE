# SPLED INTELLIGENCE 0.7.0 status

- Android: local Binance USD-M public market scanner; manual execution only; seven transparent rule-based strategies.
- Research: public historical-data downloader, conservative target-before-stop labels, purged chronological train/calibration/holdout evaluation, Platt calibration and expanding walk-forward reports.
- Research model: experimental/offline only; not loaded by Android and not represented as a live win probability.
- Safety UI: explicit NOT APPROVED FOR REAL-MONEY RELIANCE banner; displayed number is labelled RULE RANK.
- Python verification: 24 backend + research tests passed in the packaging environment.
- Android build: not executed in this environment; GitHub Actions workflow included but a successful CI run is still required.
- Real-money release: NOT APPROVED until dataset-specific out-of-sample results, realistic costs/fills, forward paper/shadow testing, physical-device testing and review are complete.
