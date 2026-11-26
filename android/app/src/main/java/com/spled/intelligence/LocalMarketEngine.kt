package com.spled.intelligence

import android.os.Handler
import android.os.Looper
import android.content.Context
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Zero-cost, phone-local Binance USD-M Futures scanner.
 * Uses public market-data endpoints only; never submits orders.
 * Scanning is active only while this Android process is alive and connected.
 */
class LocalMarketEngine(private val context: Context, private val onEvent: (String, String) -> Unit) {
    private data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double)
    private data class Market(
        val symbol: String, var price: Double? = null, var change: Double? = null,
        var volume: Double? = null, var bid: Double? = null, var ask: Double? = null,
        var spread: Double? = null, var eventTime: Long = 0L, var received: Long = System.currentTimeMillis()
    )
    private data class Idea(
        val symbol: String, val direction: String, val mode: String, val score: Double,
        val entry: Double, val target: Double, val stop: Double, val rr: Double,
        val reasons: List<String>, val created: Long, var status: String = "ACTIVE",
        val aiProbability: Double? = null, val aiStatus: String = "MODEL_NOT_VALIDATED", val createdCandleTime: Long = 0L
    )
    private data class PaperTrade(
        val id: String, val symbol: String, val direction: String, val mode: String,
        val entry: Double, val target: Double, val stop: Double, val quantity: Double,
        val riskUsd: Double, val openedAt: Long, var status: String = "OPEN",
        var exitPrice: Double? = null, var netPnl: Double? = null, var closedAt: Long? = null
    )

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val markets = ConcurrentHashMap<String, Market>()
    private val candles = ConcurrentHashMap<String, ArrayDeque<Candle>>()
    private val ideas = ConcurrentHashMap<String, Idea>()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val knownSymbols = ConcurrentHashMap.newKeySet<String>()
    private val pendingMarkets = ConcurrentHashMap<String, JSONObject>()
    private val prefs = context.getSharedPreferences("spled_local_scanner", Context.MODE_PRIVATE)
    private val hybridModel = HybridModel(context)
    private val riskEngine = TradeRiskEngine()
    private val scanExecutor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var scanTask: ScheduledFuture<*>? = null
    @Volatile private var scanCycles = 0L
    private val lastSignalCandle = ConcurrentHashMap<String, Long>()
    private val lastEvaluatedCandle = ConcurrentHashMap<String, Long>()
    private val paperLock = Any()
    private val paperTrades = mutableListOf<PaperTrade>()
    private val paperPrefs = context.getSharedPreferences("spled_paper_account", Context.MODE_PRIVATE)
    private val paperInitialBalance = 1000.0
    private val paperFeePerSide = 0.0005
    private val paperSlippagePerSide = 0.0005
    private val paperMaxOpenPositions = 5
    private val paperRiskFraction = 0.005
    private val paperMaxNotionalFraction = 0.20
    private val flushMarkets = Runnable {
        if (pendingMarkets.isNotEmpty()) {
            val rows = JSONArray()
            pendingMarkets.values.forEach { rows.put(it) }
            pendingMarkets.clear()
            send("message", JSONObject().put("type","market_batch").put("markets",rows).toString())
        }
    }
    @Volatile private var running = false
    @Volatile private var eligibleCount = 0
    @Volatile private var connectedFeeds = 0
    private val rest = "https://fapi.binance.com"
    private val ws = "wss://fstream.binance.com"

    fun start() {
        if (running) return
        running = true
        restoreHistory()
        restorePaperTrades()
        emitStatus("DISCOVERING_BINANCE_CONTRACTS")
        scanTask?.cancel(false)
        scanTask = scanExecutor.scheduleAtFixedRate({
            if (running) runOneSecondScan()
        }, 0, 1, TimeUnit.SECONDS)
        Thread {
            try {
                val req = Request.Builder().url("$rest/fapi/v1/exchangeInfo").build()
                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Binance exchangeInfo HTTP ${response.code}")
                    val root = JSONObject(response.body?.string() ?: "{}")
                    val arr = root.optJSONArray("symbols") ?: JSONArray()
                    val symbols = ArrayList<String>()
                    for (i in 0 until arr.length()) {
                        val x = arr.optJSONObject(i) ?: continue
                        if (x.optString("status") == "TRADING" &&
                            x.optString("contractType") == "PERPETUAL" &&
                            x.optString("quoteAsset") == "USDT") {
                            symbols.add(x.optString("symbol"))
                        }
                    }
                    eligibleCount = symbols.size
                    knownSymbols.addAll(symbols)
                    emitStatus("DISCOVERED_${symbols.size}_USDT_PERPETUALS")
                    connect("ticker", "$ws/ws/!ticker@arr")
                    connect("book", "$ws/ws/!bookTicker")
                    Thread {
                        warmup(symbols)
                        if (running) subscribeKlines(symbols)
                        emitSnapshot()
                    }.start()
                    Thread { refreshUniverseLoop() }.start()
                }
            } catch (e: Exception) {
                emitStatus("CONNECTION_ERROR: ${e.message ?: "unknown"}")
                scheduleRestart()
            }
        }.start()
    }

    private fun subscribeKlines(symbols: List<String>) {
        symbols.chunked(80).forEach { chunk ->
            if (!running) return
            val id = "kline_${System.nanoTime()}"
            connect(id, "$ws/stream?streams=" + chunk.joinToString("/") { "${it.lowercase()}@kline_1m" })
        }
    }

    private fun refreshUniverseLoop() {
        while (running) {
            try { Thread.sleep(300_000) } catch (_: InterruptedException) { return }
            if (!running) return
            try {
                val req=Request.Builder().url("$rest/fapi/v1/exchangeInfo").build()
                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val arr=JSONObject(response.body?.string() ?: "{}").optJSONArray("symbols") ?: JSONArray()
                    val fresh=mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val x=arr.optJSONObject(i) ?: continue
                        val sym=x.optString("symbol")
                        if (x.optString("status")=="TRADING" && x.optString("contractType")=="PERPETUAL" &&
                            x.optString("quoteAsset")=="USDT" && knownSymbols.add(sym)) fresh.add(sym)
                    }
                    if (fresh.isNotEmpty()) {
                        eligibleCount=knownSymbols.size
                        emitStatus("UNIVERSE_UPDATED_${eligibleCount}")
                        Thread {
                            warmup(fresh)
                            if (running) subscribeKlines(fresh)
                            emitSnapshot()
                        }.start()
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun warmup(symbols: List<String>) {
        // Gentle sequential warm-up: 80 x 1m candles per contract. Public REST only.
        for (symbol in symbols) {
            if (!running) return
            try {
                val url = "$rest/fapi/v1/klines?symbol=$symbol&interval=1m&limit=80"
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { response ->
                    if (response.isSuccessful) {
                        val a = JSONArray(response.body?.string() ?: "[]")
                        val q = ArrayDeque<Candle>()
                        for (i in 0 until max(0, a.length() - 1)) {
                            val row = a.optJSONArray(i) ?: continue
                            if (row.length() < 6) continue
                            q.add(Candle(row.optLong(0), row.optString(1).toDoubleOrNull() ?: 0.0,
                                row.optString(2).toDoubleOrNull() ?: 0.0, row.optString(3).toDoubleOrNull() ?: 0.0,
                                row.optString(4).toDoubleOrNull() ?: 0.0, row.optString(5).toDoubleOrNull() ?: 0.0))
                        }
                        if (q.isNotEmpty()) candles[symbol] = q
                    }
                }
            } catch (_: Exception) { /* symbol will become ready after enough live candles */ }
            try { Thread.sleep(120) } catch (_: InterruptedException) { return }
        }
    }

    private fun connect(key: String, url: String) {
        if (!running) return
        val request = Request.Builder().url(url).build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sockets[key] = webSocket
                connectedFeeds = sockets.size
                emitStatus("LIVE_${connectedFeeds}_FEEDS")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    if (key == "ticker") handleTicker(JSONArray(text))
                    else if (key == "book") handleBook(text)
                    else handleKline(JSONObject(text).optJSONObject("data") ?: JSONObject())
                } catch (_: Exception) { }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                sockets.remove(key); connectedFeeds = sockets.size
                emitStatus("RECONNECTING_$key")
                if (running) main.postDelayed({ connect(key, url) }, 3000)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                sockets.remove(key); connectedFeeds = sockets.size
                if (running) main.postDelayed({ connect(key, url) }, 3000)
            }
        })
        sockets[key] = socket
    }

    private fun handleTicker(arr: JSONArray) {
        for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            val symbol = x.optString("s")
            if (!symbol.endsWith("USDT") || !knownSymbols.contains(symbol)) continue
            val m = markets.getOrPut(symbol) { Market(symbol) }
            m.price = x.optString("c").toDoubleOrNull()
            m.change = x.optString("P").toDoubleOrNull()
            m.volume = x.optString("q").toDoubleOrNull()
            m.eventTime = x.optLong("E")
            m.received = System.currentTimeMillis()
            emitMarket(m)
        }
    }

    private fun handleBook(raw: String) {
        val rows = if (raw.trimStart().startsWith("[")) JSONArray(raw) else {
            val root = JSONObject(raw)
            if (root.has("data")) JSONArray().put(root.optJSONObject("data")) else JSONArray().put(root)
        }
        for (i in 0 until rows.length()) {
            val x = rows.optJSONObject(i) ?: continue
            val symbol = x.optString("s")
            if (!knownSymbols.contains(symbol)) continue
            val m = markets.getOrPut(symbol) { Market(symbol) }
            m.bid = x.optString("b").toDoubleOrNull()
            m.ask = x.optString("a").toDoubleOrNull()
            val bid = m.bid ?: continue
            val ask = m.ask ?: continue
            val mid = (bid + ask) / 2.0
            if (mid > 0 && ask >= bid) m.spread = (ask - bid) / mid * 10000.0
        }
    }

    private fun handleKline(data: JSONObject) {
        val k = data.optJSONObject("k") ?: return
        if (!k.optBoolean("x")) return // closed 1m candles only
        val symbol = data.optString("s")
        if (symbol.isBlank()) return
        val candle = Candle(k.optLong("t"), k.optString("o").toDoubleOrNull() ?: return,
            k.optString("h").toDoubleOrNull() ?: return, k.optString("l").toDoubleOrNull() ?: return,
            k.optString("c").toDoubleOrNull() ?: return, k.optString("v").toDoubleOrNull() ?: return)
        val q = candles.getOrPut(symbol) { ArrayDeque() }
        synchronized(q) {
            if (q.isNotEmpty() && q.last().t == candle.t) q.removeLast()
            q.addLast(candle)
            while (q.size > 300) q.removeFirst()
        }
        val market = markets[symbol]
        if (market != null) {
            val snapshot = synchronized(q) { q.toList() }
            resolveIdeasOnClosedCandle(symbol, candle)
        }
    }

    private fun evaluate(symbol: String, c: List<Candle>, m: Market) {
        if (c.size < 60) return
        val price = m.price ?: c.last().c
        if (price <= 0) return
        val close = c.map { it.c }; val high = c.map { it.h }; val low = c.map { it.l }; val vol = c.map { it.v }
        val fast = ema(close, 9); val slow = ema(close, 21)
        val direction = if (fast > slow) "LONG" else "SHORT"
        val atr = atr(c)
        val volBase = vol.takeLast(23).dropLast(3).average().coerceAtLeast(1e-12)
        val volRatio = vol.takeLast(3).average() / volBase
        val recentHigh = high.dropLast(1).takeLast(20).maxOrNull() ?: return
        val recentLow = low.dropLast(1).takeLast(20).minOrNull() ?: return
        val last = c.last(); val prev = c[c.size - 2]
        val setups = mutableListOf<Triple<String, String, Double>>()
        if (last.c > recentHigh && volRatio >= 1.15)
            setups.add(Triple("BREAKOUT_RETEST", "LONG", 72.0 + min(15.0, (volRatio-1)*8)))
        if (last.c < recentLow && volRatio >= 1.15)
            setups.add(Triple("BREAKOUT_RETEST", "SHORT", 72.0 + min(15.0, (volRatio-1)*8)))
        val nearEma = abs(last.c-fast) <= max(atr*.45, price*.001)
        if (nearEma && ((direction=="LONG" && last.c>last.o && prev.c<=fast) || (direction=="SHORT" && last.c<last.o && prev.c>=fast)))
            setups.add(Triple("EMA_TREND_PULLBACK", direction, 68.0))
        val momentum = if (close[close.size-6] != 0.0) (last.c/close[close.size-6]-1)*100 else 0.0
        if (abs(momentum)>=.50 && volRatio>=1.4 && (momentum>0)==(direction=="LONG"))
            setups.add(Triple("MOMENTUM_EXPANSION", direction, 70.0+min(15.0,(abs(momentum)-.5)*8)))
        val rsi = rsi(close)
        val bandNow = close.takeLast(20); val mean = bandNow.average()
        val sd = sqrt(bandNow.map { (it-mean)*(it-mean) }.average())
        val bandPrev = close.dropLast(1).takeLast(20); val prevMean = bandPrev.average()
        val prevSd = sqrt(bandPrev.map { (it-prevMean)*(it-prevMean) }.average())
        if (rsi != null && close.size>=22) {
            if (prev.c < prevMean-2*prevSd && last.c > mean-2*sd && rsi<38)
                setups.add(Triple("BOLLINGER_MEAN_REVERSION","LONG",63.0))
            if (prev.c > prevMean+2*prevSd && last.c < mean+2*sd && rsi>62)
                setups.add(Triple("BOLLINGER_MEAN_REVERSION","SHORT",63.0))
        }
        val tail = c.takeLast(30); val denom = tail.sumOf { it.v }
        if (denom>0) {
            val vwap = tail.sumOf { ((it.h+it.l+it.c)/3)*it.v }/denom
            val prevTail = c.dropLast(1).takeLast(30)
            val prevDenom = prevTail.sumOf { it.v }
            val prevVwap = if (prevDenom>0) prevTail.sumOf { ((it.h+it.l+it.c)/3)*it.v }/prevDenom else vwap
            if (prev.c<=prevVwap && last.c>vwap && direction=="LONG") setups.add(Triple("VWAP_RECLAIM","LONG",67.0))
            if (prev.c>=prevVwap && last.c<vwap && direction=="SHORT") setups.add(Triple("VWAP_RECLAIM","SHORT",67.0))
        }
        val macdLine = (1..close.size).map { end ->
            ema(close.take(end),12)-ema(close.take(end),26)
        }
        if (macdLine.size >= 10) {
            val signalNow = ema(macdLine,9)
            val signalPrev = ema(macdLine.dropLast(1),9)
            val crossUp = macdLine.last() > signalNow && macdLine[macdLine.size-2] <= signalPrev
            val crossDown = macdLine.last() < signalNow && macdLine[macdLine.size-2] >= signalPrev
            if (crossUp && direction=="LONG") setups.add(Triple("MACD_MOMENTUM_CROSS","LONG",67.0))
            if (crossDown && direction=="SHORT") setups.add(Triple("MACD_MOMENTUM_CROSS","SHORT",67.0))
        }
        val rangeHi = high.takeLast(31).dropLast(1).maxOrNull() ?: 0.0
        val rangeLo = low.takeLast(31).dropLast(1).minOrNull() ?: 0.0
        if (last.c>rangeHi && volRatio>=1.25) setups.add(Triple("RANGE_EXPANSION","LONG",69.0))
        if (last.c<rangeLo && volRatio>=1.25) setups.add(Triple("RANGE_EXPANSION","SHORT",69.0))
        for ((mode, side, score) in setups) {
            val now = System.currentTimeMillis()
            val setupKey = "$symbol:$mode:$side"
            if (lastSignalCandle[setupKey] == last.t) continue
            val duplicateActive = ideas.values.any { it.symbol==symbol && it.mode==mode && it.direction==side && it.status=="ACTIVE" && now-it.created<180_000 }
            if (duplicateActive) continue
            val entry = if (side=="LONG") (m.ask ?: price) else (m.bid ?: price)
            val buffer = max(atr*.15, entry*.0002)
            val stop = if (side=="LONG") low.takeLast(8).minOrNull()!!-buffer else high.takeLast(8).maxOrNull()!!+buffer
            val risk = abs(entry-stop)
            if (risk<=0) continue
            // Prefer the nearest visible structural obstacle when it offers enough room.
            // Otherwise use the project's 0.7% reference target, but never below 1.6R.
            val minimumReward = max(risk * 1.6, entry * .007)
            val structuralTarget = if (side == "LONG") {
                high.dropLast(1).takeLast(60).filter { it > entry }.minOrNull()
            } else {
                low.dropLast(1).takeLast(60).filter { it < entry }.maxOrNull()
            }
            val target = if (structuralTarget != null && abs(structuralTarget - entry) >= minimumReward) {
                structuralTarget
            } else if (side == "LONG") {
                entry + minimumReward
            } else {
                entry - minimumReward
            }
            if (target <= 0.0) continue
            val assessment = riskEngine.assess(entry,stop,target,m.spread ?: Double.POSITIVE_INFINITY,
                m.volume ?: 0.0,System.currentTimeMillis()-m.received)
            if (!assessment.allowed) continue
            val rr = assessment.grossRiskReward
            val netRr = assessment.estimatedNetRiskReward
            val featureHigh = high.dropLast(1).takeLast(20).maxOrNull() ?: last.h
            val featureLow = low.dropLast(1).takeLast(20).minOrNull() ?: last.l
            val featureSpan = (featureHigh-featureLow).takeIf { it>0.0 } ?: 1.0
            val featureMap = mutableMapOf<String,Double>(
                "side" to if (side=="LONG") 1.0 else -1.0,
                "ema_gap_pct" to ((fast/slow)-1.0)*100.0,
                "rsi14" to (rsi ?: 50.0), "atr_pct" to (atr/entry*100.0),
                "volume_ratio20" to volRatio,
                "return_5m_pct" to ((last.c/close[close.size-6])-1.0)*100.0,
                "return_15m_pct" to ((last.c/close[close.size-16])-1.0)*100.0,
                "range_position20" to ((last.c-featureLow)/featureSpan)
            )
            listOf("BREAKOUT_RETEST","EMA_TREND_PULLBACK","MOMENTUM_EXPANSION","BOLLINGER_MEAN_REVERSION","VWAP_RECLAIM","MACD_MOMENTUM_CROSS","RANGE_EXPANSION").forEach { featureMap["strategy_$it"] = if (it==mode) 1.0 else 0.0 }
            val aiProbability = hybridModel.probability(featureMap)
            val targetReason = if (structuralTarget != null && target == structuralTarget) "Target references nearest visible swing obstacle" else "Target uses 0.7% reference or 1.6R minimum"
            val reasons = mutableListOf("$mode condition detected on a closed 1m candle","EMA 9/21 direction: $direction","Volume ratio: %.2fx".format(volRatio),"Structural swing stop with ATR buffer","$targetReason","Net R:R %.2f after assumed %.2f%% round-trip costs".format(netRr,assessment.estimatedRoundTripCostPct*100))
            if (aiProbability != null) reasons.add("On-device calibrated shadow-model estimate: %.1f%%".format(aiProbability*100))
            else reasons.add("AI model unavailable or not validated; rule rank is not win probability")
            val idea = Idea(symbol,side,mode,score.coerceAtMost(95.0),entry,target,stop,rr,
                reasons, System.currentTimeMillis(), aiProbability=aiProbability, aiStatus=hybridModel.status(), createdCandleTime=last.t)
            ideas["$symbol:$mode:$side:${last.t}"]=idea
            lastSignalCandle[setupKey] = last.t
            if (lastSignalCandle.size > 12000) lastSignalCandle.clear()
            emitSignal(idea)
            openPaperTrade(idea, m)
        }
    }

    private fun resolveIdeasOnClosedCandle(symbol: String, candle: Candle) {
        val now = System.currentTimeMillis()
        for (idea in ideas.values) {
            if (idea.symbol != symbol || idea.status != "ACTIVE") continue
            // Do not score the signal candle itself: its OHLC was already known at signal time.
            if (candle.t <= idea.createdCandleTime) continue
            if (now-idea.created >= 4*60*60*1000L) {
                idea.status="EXPIRED"; emitSignal(idea); continue
            }
            val stopHit = if (idea.direction=="LONG") candle.l<=idea.stop else candle.h>=idea.stop
            val targetHit = if (idea.direction=="LONG") candle.h>=idea.target else candle.l<=idea.target
            // Conservative OHLC ambiguity rule: if both touched in one candle, stop wins.
            if (stopHit) idea.status="STOP_HIT"
            else if (targetHit) idea.status="TARGET_HIT"
            else continue
            emitSignal(idea)
        }
    }

    /** Re-evaluates cached live market state every second; strategy signals remain candle-confirmed. */
    private fun runOneSecondScan() {
        if (!running) return
        var evaluated = 0
        var checked = 0
        for (m in markets.values) {
            if (!running) return
            try {
                val q = candles[m.symbol]
                if (q != null && (System.currentTimeMillis() - m.received) <= 10_000L) {
                    checked++
                    val snapshot = synchronized(q) { q.toList() }
                    val latestClosed = snapshot.lastOrNull()?.t
                    if (snapshot.size >= 60 && latestClosed != null && lastEvaluatedCandle[m.symbol] != latestClosed) {
                        evaluate(m.symbol, snapshot, m)
                        lastEvaluatedCandle[m.symbol] = latestClosed
                        evaluated++
                    }
                }
                updatePaperPositions(m)
            } catch (_: Exception) {
                // A malformed/stale symbol must not stop the one-second scanner loop.
            }
        }
        scanCycles++
        send("message", JSONObject().put("type", "scan_tick")
            .put("scan_cycles", scanCycles).put("evaluated_pairs", evaluated)
            .put("checked_pairs", checked).put("eligible_pairs", eligibleCount).put("received_markets", markets.size)
            .put("timestamp", System.currentTimeMillis()).toString())
        emitPaperSnapshot()
    }

    private fun openPaperTrade(idea: Idea, market: Market) {
        synchronized(paperLock) {
            if (paperTrades.count { it.status == "OPEN" } >= paperMaxOpenPositions) return
            if (paperTrades.any { it.status == "OPEN" && it.symbol == idea.symbol }) return
            val entry = if (idea.direction == "LONG") market.ask ?: return else market.bid ?: return
            val stopDistance = abs(entry - idea.stop)
            if (entry <= 0.0 || stopDistance <= 0.0) return
            val equity = paperEquityLocked()
            if (equity <= 0.0) return
            val riskBudget = equity * paperRiskFraction
            val estimatedRoundTripCostPerUnit = entry * 2.0 * (paperFeePerSide + paperSlippagePerSide)
            val quantityByRisk = riskBudget / (stopDistance + estimatedRoundTripCostPerUnit)
            val quantityByNotional = (equity * paperMaxNotionalFraction) / entry
            val quantity = min(quantityByRisk, quantityByNotional)
            if (!quantity.isFinite() || quantity <= 0.0) return
            val trade = PaperTrade(
                id = "${idea.symbol}:${idea.created}", symbol = idea.symbol,
                direction = idea.direction, mode = idea.mode, entry = entry,
                target = idea.target, stop = idea.stop, quantity = quantity,
                riskUsd = (stopDistance + estimatedRoundTripCostPerUnit) * quantity, openedAt = System.currentTimeMillis()
            )
            paperTrades.add(0, trade)
            persistPaperLocked()
        }
        emitPaperSnapshot()
    }

    private fun updatePaperPositions(market: Market) {
        var changed = false
        synchronized(paperLock) {
            val now = System.currentTimeMillis()
            for (trade in paperTrades.filter { it.status == "OPEN" && it.symbol == market.symbol }) {
                if (now - market.received > 10_000L) continue
                val last = market.price ?: continue
                val long = trade.direction == "LONG"
                val stopHit = if (long) last <= trade.stop else last >= trade.stop
                val targetHit = if (long) last >= trade.target else last <= trade.target
                val timeHit = now - trade.openedAt >= 4L * 60L * 60L * 1000L
                if (stopHit || targetHit || timeHit) {
                    val exit = if (long) (market.bid ?: last) else (market.ask ?: last)
                    closePaperTradeLocked(trade, exit, when {
                        stopHit -> "STOP_HIT"
                        targetHit -> "TARGET_HIT"
                        else -> "TIME_EXIT"
                    }, now)
                    changed = true
                }
            }
            if (changed) persistPaperLocked()
        }
        if (changed) emitPaperSnapshot()
    }

    private fun closePaperTradeLocked(trade: PaperTrade, exit: Double, status: String, now: Long) {
        val direction = if (trade.direction == "LONG") 1.0 else -1.0
        val gross = (exit - trade.entry) * trade.quantity * direction
        val fees = (trade.entry + exit) * trade.quantity * paperFeePerSide
        val slippage = (trade.entry + exit) * trade.quantity * paperSlippagePerSide
        trade.exitPrice = exit
        trade.netPnl = gross - fees - slippage
        trade.status = status
        trade.closedAt = now
    }

    private fun paperUnrealizedLocked(): Double {
        var total = 0.0
        for (trade in paperTrades.filter { it.status == "OPEN" }) {
            val market = markets[trade.symbol] ?: continue
            if (System.currentTimeMillis() - market.received > 10_000L) continue
            val mark = if (trade.direction == "LONG") (market.bid ?: market.price) else (market.ask ?: market.price)
            if (mark != null) {
                val dir = if (trade.direction == "LONG") 1.0 else -1.0
                val gross = (mark - trade.entry) * trade.quantity * dir
                val estimatedExitCosts = (trade.entry + mark) * trade.quantity * (paperFeePerSide + paperSlippagePerSide)
                total += gross - estimatedExitCosts
            }
        }
        return total
    }

    private fun paperRealizedLocked(): Double = paperTrades.filter { it.status != "OPEN" }.sumOf { it.netPnl ?: 0.0 }
    private fun paperEquityLocked(): Double = paperInitialBalance + paperRealizedLocked() + paperUnrealizedLocked()

    private fun paperTradeJson(t: PaperTrade): JSONObject = JSONObject()
        .put("id", t.id).put("symbol", t.symbol).put("direction", t.direction).put("mode", t.mode)
        .put("entry", t.entry).put("target", t.target).put("stop", t.stop).put("quantity", t.quantity)
        .put("risk_usdt", t.riskUsd).put("opened_at", t.openedAt).put("status", t.status)
        .put("exit_price", t.exitPrice).put("net_pnl", t.netPnl).put("closed_at", t.closedAt)

    private fun emitPaperSnapshot() {
        val payload = synchronized(paperLock) {
            val open = JSONArray(); val closed = JSONArray()
            paperTrades.filter { it.status == "OPEN" }.forEach { open.put(paperTradeJson(it)) }
            paperTrades.filter { it.status != "OPEN" }.take(100).forEach { closed.put(paperTradeJson(it)) }
            JSONObject().put("type", "paper_update")
                .put("initial_balance", paperInitialBalance)
                .put("realized_pnl", paperRealizedLocked())
                .put("unrealized_pnl", paperUnrealizedLocked())
                .put("equity", paperEquityLocked())
                .put("open_positions", open).put("closed_trades", closed)
                .put("max_positions", paperMaxOpenPositions)
                .put("fee_per_side_pct", paperFeePerSide * 100.0)
                .put("slippage_per_side_pct", paperSlippagePerSide * 100.0)
                .put("leverage", 1)
        }
        send("message", payload.toString())
    }

    private fun restorePaperTrades() {
        synchronized(paperLock) {
            try {
                val arr = JSONArray(paperPrefs.getString("trades", "[]") ?: "[]")
                for (i in 0 until arr.length()) {
                    val x = arr.optJSONObject(i) ?: continue
                    paperTrades.add(PaperTrade(
                        id=x.optString("id"), symbol=x.optString("symbol"), direction=x.optString("direction"),
                        mode=x.optString("mode"), entry=x.optDouble("entry"), target=x.optDouble("target"),
                        stop=x.optDouble("stop"), quantity=x.optDouble("quantity"), riskUsd=x.optDouble("risk_usdt"),
                        openedAt=x.optLong("opened_at"), status=x.optString("status", "OPEN"),
                        exitPrice=if (x.isNull("exit_price")) null else x.optDouble("exit_price"),
                        netPnl=if (x.isNull("net_pnl")) null else x.optDouble("net_pnl"),
                        closedAt=if (x.isNull("closed_at")) null else x.optLong("closed_at")
                    ))
                }
            } catch (_: Exception) { paperTrades.clear() }
        }
    }

    private fun persistPaperLocked() {
        val arr = JSONArray()
        paperTrades.take(300).forEach { arr.put(paperTradeJson(it)) }
        paperPrefs.edit().putString("trades", arr.toString()).apply()
    }

    private fun restoreHistory() {
        try {
            val saved=JSONArray(prefs.getString("history","[]") ?: "[]")
            for (i in 0 until saved.length()) {
                val x=saved.optJSONObject(i) ?: continue
                val idea=Idea(x.optString("symbol"),x.optString("direction"),x.optString("mode"),
                    x.optDouble("score"),x.optDouble("entry_reference"),x.optDouble("target_reference"),
                    x.optDouble("structural_stop_reference"),x.optDouble("risk_reward"),
                    (0 until (x.optJSONArray("rationale")?.length() ?: 0)).map { x.getJSONArray("rationale").optString(it) },
                    (x.optDouble("created_at")*1000).toLong(),x.optString("status","ACTIVE"),
                    if (x.isNull("ai_probability")) null else x.optDouble("ai_probability"),x.optString("ai_status","MODEL_NOT_VALIDATED"),x.optLong("created_candle_time"))
                ideas["${idea.symbol}:${idea.mode}:${idea.direction}:${idea.createdCandleTime}"]=idea
            }
        } catch (_: Exception) { }
    }

    private fun emitSnapshot() {
        val data=JSONObject()
        val ma=JSONArray(); markets.values.sortedByDescending { it.volume ?: 0.0 }.forEach { ma.put(marketJson(it)) }
        val si=JSONArray(); ideas.values.sortedWith(compareByDescending<Idea> { it.aiProbability ?: -1.0 }.thenByDescending { it.score }).forEach { si.put(ideaJson(it)) }
        data.put("markets",ma); data.put("signals",si); data.put("history",loadHistory(si))
        data.put("universe_count",eligibleCount); data.put("local_mode",true); data.put("ai_model_status",hybridModel.status())
        send("message",JSONObject().put("type","snapshot").put("data",data).toString())
        emitPaperSnapshot()
    }
    private fun emitMarket(m: Market) {
        pendingMarkets[m.symbol]=marketJson(m)
        main.removeCallbacks(flushMarkets)
        main.postDelayed(flushMarkets,500)
    }
    private fun emitSignal(i: Idea) {
        val obj=ideaJson(i)
        saveHistory(obj)
        send("message",JSONObject().put("type","signal_update").put("signal",obj).toString())
    }
    private fun saveHistory(signal: JSONObject) {
        try {
            val old=JSONArray(prefs.getString("history","[]") ?: "[]")
            val next=JSONArray().put(signal)
            for (i in 0 until min(old.length(),499)) next.put(old.optJSONObject(i))
            prefs.edit().putString("history",next.toString()).apply()
        } catch (_: Exception) { }
    }
    private fun loadHistory(current: JSONArray): JSONArray {
        return try {
            val saved=JSONArray(prefs.getString("history","[]") ?: "[]")
            val merged=JSONArray(); val seen=HashSet<String>()
            for (i in 0 until min(saved.length(),500)) {
                val item=saved.optJSONObject(i) ?: continue
                val key="${item.optString("symbol")}:${item.optString("mode")}:${item.optString("direction")}:${item.optLong("created_at")}"
                if (seen.add(key)) merged.put(item)
            }
            for (i in 0 until current.length()) {
                val item=current.optJSONObject(i) ?: continue
                val key="${item.optString("symbol")}:${item.optString("mode")}:${item.optString("direction")}:${item.optLong("created_at")}"
                if (seen.add(key)) merged.put(item)
            }
            merged
        } catch (_: Exception) { current }
    }
    private fun marketJson(m: Market)=JSONObject().put("symbol",m.symbol).put("price",m.price)
        .put("change_24h_pct",m.change).put("quote_volume_24h",m.volume).put("spread_bps",m.spread)
        .put("data_age_seconds",max(0L,System.currentTimeMillis()-m.received)/1000.0)
    private fun ideaJson(i: Idea)=JSONObject().put("symbol",i.symbol).put("direction",i.direction).put("mode",i.mode)
        .put("score",i.score).put("entry_reference",i.entry).put("target_reference",i.target)
        .put("structural_stop_reference",i.stop).put("risk_reward",i.rr).put("rationale",JSONArray(i.reasons))
        .put("invalidation",JSONArray(listOf("Cancel if spread widens above 15 bps","Reassess if market feed becomes stale")))
        .put("probability_status",if(i.aiProbability!=null) "SHADOW_MODEL_ESTIMATE_NOT_LIVE_APPROVED" else "RULE_BASED_NOT_VALIDATED")
        .put("ai_probability",i.aiProbability).put("ai_status",i.aiStatus).put("created_at",i.created/1000.0)
        .put("created_candle_time",i.createdCandleTime)
        .put("data_age_seconds",0).put("status",i.status)
    private fun emitStatus(s: String) = send("status",s)
    private fun send(type: String, value: String) { main.post { if (running || type=="status") onEvent(type,value) } }
    private fun scheduleRestart() { if (running) main.postDelayed({ if (running) startAfterFailure() },5000) }
    private fun startAfterFailure() { running=false; start() }
    fun stop() {
        running=false
        scanTask?.cancel(true)
        scanTask = null
        scanExecutor.shutdownNow()
        main.removeCallbacks(flushMarkets)
        sockets.values.forEach { it.close(1000,"user stopped local scanner") }
        sockets.clear()
        client.dispatcher.executorService.shutdown()
        emitStatus("STOPPED")
    }

    private fun ema(values: List<Double>, period: Int): Double {
        if (values.isEmpty()) return 0.0
        val a=2.0/(period+1); var e=values.first()
        for (v in values.drop(1)) e=a*v+(1-a)*e
        return e
    }
    private fun atr(c: List<Candle>): Double {
        if (c.isEmpty()) return 0.0
        val tr=mutableListOf(c.first().h-c.first().l)
        c.zipWithNext().forEach { (a,b)->tr.add(max(b.h-b.l,max(abs(b.h-a.c),abs(b.l-a.c)))) }
        return tr.takeLast(14).average()
    }
    private fun rsi(c: List<Double>): Double? {
        if (c.size<15) return null
        val d=c.zipWithNext().takeLast(14).map { it.second-it.first }
        val gain=d.filter { it>0 }.sum()/14
        val loss=-d.filter { it<0 }.sum()/14
        if (loss==0.0) return 100.0
        return 100-100/(1+gain/loss)
    }
}
