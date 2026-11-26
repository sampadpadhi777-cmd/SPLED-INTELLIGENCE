# Decision engine and signal interpretation

## Universe
At startup the Android app fetches Binance USDⓈ-M exchange information and includes only symbols where status is TRADING, contractType is PERPETUAL and quoteAsset is USDT. This is a dynamic universe; the exact number changes as Binance lists or delists contracts. The app refreshes the universe every five minutes and adds new eligible symbols.

## Data feeds
- All-market 24-hour ticker array for price, change and quote volume.
- All-market best bid/ask stream for spread estimation.
- Closed 1-minute candles, sharded into WebSocket combined streams.
- REST warm-up of recent 1-minute candles for each discovered symbol.

## Candidate strategies
Breakout/retest, EMA 9/21 pullback, momentum expansion, Bollinger mean reversion, VWAP reclaim, MACD momentum cross and range expansion. Candidate conditions are rule-based and evaluated only on closed 1-minute candles.

## Entry / target / stop
Entry reference uses best ask for LONG and best bid for SHORT where available. Stop is beyond the recent 8-candle swing with an ATR buffer. Target prefers the nearest visible swing obstacle within the recent 60-candle window when it offers at least the greater of 1.6R or 0.7%; otherwise the target uses that minimum distance. These are reference levels, not guaranteed fills.

## Risk rejection
Reject if feed is stale, spread unavailable or above 15 bps, 24-hour quote volume below 1,000,000 USDT, stop distance above 5%, gross R:R below 1.5, or estimated net R:R below 1.2 after configured costs. Costs currently assume 0.05% fee per side plus 0.10% round-trip slippage. Funding is not included in this Android estimate.

## Top 10
The Radar tab deduplicates active candidate signals by symbol, keeps the highest rule score for each symbol, ranks them by rule score and then R:R, and displays up to ten. Rule score is a heuristic ranking score; it is not a calibrated probability of winning.

## Recovery and stop policy
No reliable recovery probability can be inferred from a single price snapshot. The stop represents the point where the original trade thesis is invalidated. The app does not widen the stop because a position might recover. If price reaches structural invalidation, the planned risk has been reached.

## Safety
Manual execution only. Validate all levels against the live order book and actual account costs. This software is not a guarantee of profit and is not approved for real-money reliance until the documented validation gates pass.
