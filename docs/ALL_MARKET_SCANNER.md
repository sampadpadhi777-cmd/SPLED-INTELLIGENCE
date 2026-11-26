# All-market scanning behavior

## Contract universe
The scanner discovers every Binance USDⓈ-M Futures contract that exchange metadata reports as `TRADING`, `PERPETUAL`, and `quoteAsset=USDT`. This is not every coin on every exchange, and it does not include spot, delivery futures, COIN-M contracts, or symbols that Binance has suspended.

## Data subscriptions
- All-market 24h ticker and best-bid/ask streams.
- Per-symbol 1m, 1h and 4h closed-candle streams, split into bounded 100-symbol combined-stream shards.
- Historical 1m (500), 1h (100) and 4h (100) candle warm-up per eligible symbol, with bounded concurrency and pacing.
- All-discovered-symbol aggTrade subscriptions in bounded shards; adaptive large-print flags are not proof of whale identity.
- Bulk mark/index/funding data and rotating open-interest polling across all symbols currently present in the live market map.
- Exchange-wide forced-order liquidation stream.

## Decision processing
Every symbol has its own rolling candle buffers and event-driven evaluation. Ticker/book events are coalesced per symbol at 750ms to avoid repeated full evaluation on noisy quote bursts. Seven independent strategy candidate generators run against the same fresh state: breakout/retest, EMA trend pullback, momentum expansion, Bollinger mean reversion, VWAP reclaim, MACD momentum cross, and range expansion. Multiple strategies may produce distinct signals for the same symbol and direction. The signal is immediately published; high-volume market quote updates are coalesced into one WebSocket batch per second for Android efficiency.

## Data and speed limitations
- No software can promise zero latency. Exchange publication, network, cloud scheduling, rate limits and device connectivity add delay.
- If warm-up fails for a symbol, it needs enough live closed candles before strategies can qualify it.
- OI is polled rather than streamed; it can be older than price/book data.
- Full depth-of-book sequence reconstruction is not implemented. Best bid/ask is not full liquidity depth.
- A score is a heuristic ranking score, not a calibrated win probability. A signal is a candidate for human review, not an order instruction.
