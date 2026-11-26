# Changelog

## 0.9.0
- Connected the Android Compose UI directly to the phone-local Binance scanner.
- Added redesigned SPLED branding and Radar / Markets / Journal / System screens.
- Added top-10 distinct symbol ranking after independent risk checks.
- Improved target selection using visible swing structure with 0.7% reference and 1.6R minimum.
- Explicitly labels rule score as a rank, not AI win probability.
- Added release limitations and paper-testing requirements.
- Standardized Java and Kotlin JVM targets to 17.

## 0.8.1
- Fixed Java/Kotlin JVM target mismatch by setting both compile targets to Java 17.


## 1.0.0 — Local one-second radar + paper trading
- Added automatic virtual trade simulation and persistent local paper journal.
- Added 1-second scan heartbeat, live pair checks and paper position monitoring.
- Kept signal logic candle-confirmed on completed 1-minute candles.
- Added Paper tab and virtual account metrics.
