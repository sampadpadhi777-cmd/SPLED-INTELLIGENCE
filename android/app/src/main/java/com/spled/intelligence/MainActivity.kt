package com.spled.intelligence

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private val Bg = Color(0xFF080D16)
private val Panel = Color(0xFF111B2A)
private val Panel2 = Color(0xFF172438)
private val Mint = Color(0xFF59DDB2)
private val Red = Color(0xFFFF7185)
private val Amber = Color(0xFFFFC46B)
private val Muted = Color(0xFF91A3BA)
private val White = Color(0xFFF4F7FB)

private data class MarketRow(
    val symbol: String,
    val price: Double?,
    val change: Double?,
    val volume: Double?,
    val spread: Double?,
    val age: Double?
)

private data class FiveMinuteSignal(
    val symbol: String, val direction: String, val entry: Double, val target: Double,
    val maxPrice: Double, val expectedMovePct: Double, val maxMovePct: Double,
    val confluence: Int, val return5mPct: Double, val expiresAt: Long
)

private data class PaperTradeRow(
    val id: String, val symbol: String, val direction: String, val mode: String,
    val entry: Double, val target: Double, val stop: Double, val quantity: Double,
    val riskUsdt: Double, val status: String, val netPnl: Double?, val openedAt: Long
)

private data class PaperAccount(
    val initialBalance: Double = 1000.0,
    val realizedPnl: Double = 0.0,
    val unrealizedPnl: Double = 0.0,
    val equity: Double = 1000.0,
    val maxPositions: Int = 5,
    val open: List<PaperTradeRow> = emptyList(),
    val closed: List<PaperTradeRow> = emptyList()
)

private data class Opportunity(
    val symbol: String,
    val direction: String,
    val score: Double,
    val entry: Double,
    val target: Double,
    val stop: Double?,
    val rr: Double?,
    val mode: String,
    val status: String,
    val aiStatus: String,
    val aiProbability: Double?,
    val createdAt: Double,
    val reasons: List<String>,
    val invalidation: List<String>
) {
    val key: String get() = "$symbol:$direction:$mode:$createdAt"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SpledApp() }
    }
}

@Composable
private fun SpledApp() {
    val context = LocalContext.current.applicationContext
    var scannerStatus by remember { mutableStateOf("STARTING") }
    var universeCount by remember { mutableIntStateOf(0) }
    var modelStatus by remember { mutableStateOf("MODEL_NOT_VALIDATED") }
    var markets by remember { mutableStateOf(emptyList<MarketRow>()) }
    var signals by remember { mutableStateOf(emptyList<Opportunity>()) }
    var history by remember { mutableStateOf(emptyList<Opportunity>()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var paper by remember { mutableStateOf(PaperAccount()) }
    var scanCycles by remember { mutableStateOf(0L) }
    var evaluatedPairs by remember { mutableIntStateOf(0) }
    var checkedPairs by remember { mutableIntStateOf(0) }
    var lastScanAt by remember { mutableStateOf(0L) }
    var fiveMinuteWinRate by remember { mutableStateOf<Double?>(null) }
    var fiveMinuteWins by remember { mutableIntStateOf(0) }
    var fiveMinuteLosses by remember { mutableIntStateOf(0) }
    var fiveMinuteResolved by remember { mutableIntStateOf(0) }
    var fiveMinutePending by remember { mutableIntStateOf(0) }
    var fiveMinuteSignals by remember { mutableStateOf(emptyList<FiveMinuteSignal>()) }
    var fiveMinuteStatus by remember { mutableStateOf("Tap Scan to evaluate the next 5 minutes") }
    var fiveMinuteChecked by remember { mutableIntStateOf(0) }
    var fiveMinuteScanAt by remember { mutableStateOf(0L) }

    val engine = remember(context) {
        LocalMarketEngine(context) { type, value ->
            if (type == "status") {
                scannerStatus = value
                Regex("(?:DISCOVERED_|UNIVERSE_UPDATED_)(\\d+)").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { universeCount = it }
            } else {
                runCatching {
                    val root = JSONObject(value)
                    when (root.optString("type")) {
                        "snapshot" -> {
                            val data = root.optJSONObject("data") ?: JSONObject()
                            markets = parseMarkets(data.optJSONArray("markets"))
                            signals = parseSignals(data.optJSONArray("signals"))
                            history = parseSignals(data.optJSONArray("history"))
                            universeCount = data.optInt("universe_count", universeCount)
                            modelStatus = data.optString("ai_model_status", modelStatus)
                        }
                        "market_batch" -> {
                            val incoming = parseMarkets(root.optJSONArray("markets"))
                            markets = (markets + incoming).associateBy { it.symbol }.values
                                .sortedByDescending { it.volume ?: 0.0 }.take(1200)
                        }
                        "signal_update" -> {
                            val signal = root.optJSONObject("signal")?.let { parseSignal(it) }
                            if (signal != null) {
                                signals = (listOf(signal) + signals.filterNot { it.key == signal.key }).take(1500)
                                history = (listOf(signal) + history.filterNot { it.key == signal.key }).take(500)
                            }
                        }
                        "scan_tick" -> {
                            scanCycles = root.optLong("scan_cycles", scanCycles)
                            evaluatedPairs = root.optInt("evaluated_pairs", evaluatedPairs)
                            checkedPairs = root.optInt("checked_pairs", checkedPairs)
                            lastScanAt = root.optLong("timestamp", lastScanAt)
                        }
                        "five_min_scan", "five_min_stats" -> {
                            if (root.has("status")) fiveMinuteStatus = root.optString("status", "NO_TRADE")
                            if (root.has("signals")) fiveMinuteSignals = parseFiveMinuteSignals(root.optJSONArray("signals"))
                            fiveMinuteChecked = root.optInt("checked", fiveMinuteChecked)
                            fiveMinuteScanAt = root.optLong("scanned_at", fiveMinuteScanAt)
                            fiveMinuteWinRate = root.optDouble("win_rate", -1.0).takeIf { it >= 0.0 }
                            fiveMinuteWins = root.optInt("wins", fiveMinuteWins)
                            fiveMinuteLosses = root.optInt("losses", fiveMinuteLosses)
                            fiveMinuteResolved = root.optInt("resolved", fiveMinuteResolved)
                            fiveMinutePending = root.optInt("pending", fiveMinutePending)
                        }
                        "paper_update" -> paper = parsePaperAccount(root)
                    }
                }
            }
        }
    }

    DisposableEffect(engine) {
        engine.start()
        onDispose { engine.stop() }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Mint, secondary = Amber, background = Bg, surface = Panel,
        onPrimary = Bg, onBackground = White, onSurface = White
    )) {
        Scaffold(
            containerColor = Bg,
            topBar = {
                Column(Modifier.fillMaxWidth().background(Bg).padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(42.dp)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text("SPLED", color = White, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                            Text("INTELLIGENCE", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.4.sp)
                        }
                        StatusPill(scannerStatus)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("BINANCE USDⓈ-M FUTURES", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(Modifier.weight(1f))
                        Text(if (universeCount > 0) "$universeCount CONTRACTS" else "DISCOVERING PAIRS", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = Panel, contentColor = White) {
                    listOf("Radar", "Markets", "Paper", "Journal", "System").forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Text(listOf("◈", "⌁", "▣", "▤", "⚙")[index], fontSize = 18.sp) },
                            label = { Text(label, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Mint, selectedTextColor = Mint, indicatorColor = Panel2, unselectedIconColor = Muted, unselectedTextColor = Muted)
                        )
                    }
                }
            }
        ) { padding ->
            when (selectedTab) {
                0 -> RadarScreen(Modifier.padding(padding), universeCount, markets.size, scannerStatus, modelStatus, signals, scanCycles, evaluatedPairs, checkedPairs, lastScanAt, fiveMinuteSignals, fiveMinuteStatus, fiveMinuteChecked, fiveMinuteScanAt, fiveMinuteWinRate, fiveMinuteWins, fiveMinuteLosses, fiveMinuteResolved, fiveMinutePending, { engine.requestFiveMinuteScan() })
                1 -> MarketsScreen(Modifier.padding(padding), markets, search, { search = it })
                2 -> PaperScreen(Modifier.padding(padding), paper)
                3 -> JournalScreen(Modifier.padding(padding), history)
                else -> SystemScreen(Modifier.padding(padding), scannerStatus, universeCount, modelStatus)
            }
        }
    }
}

@Composable
private fun RadarScreen(
    modifier: Modifier, universeCount: Int, marketCount: Int, status: String,
    modelStatus: String, allSignals: List<Opportunity>, scanCycles: Long, evaluatedPairs: Int, checkedPairs: Int, lastScanAt: Long,
    fiveMinuteSignals: List<FiveMinuteSignal>, fiveMinuteStatus: String, fiveMinuteChecked: Int, fiveMinuteScanAt: Long,
    fiveMinuteWinRate: Double?, fiveMinuteWins: Int, fiveMinuteLosses: Int, fiveMinuteResolved: Int, fiveMinutePending: Int,
    onFiveMinuteScan: () -> Unit
) {
    val active = allSignals.filter { it.status == "ACTIVE" }
        .groupBy { it.symbol }
        .mapNotNull { (_, rows) -> rows.maxByOrNull { it.score } }
        .sortedWith(compareByDescending<Opportunity> { it.score }.thenByDescending { it.rr ?: 0.0 })
        .take(10)
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 18.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("MARKET RADAR", color = White, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                            Text("Live universe scan · manual trade ideas", color = Muted, fontSize = 11.sp)
                        }
                        Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0x1F59DDB2)), contentAlignment = Alignment.Center) {
                            Text(if (universeCount >= 500) "500+" else if (universeCount > 0) universeCount.toString() else "LIVE", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Black)
                        }
                    }
                    Spacer(Modifier.height(15.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        StatTile("ELIGIBLE", if (universeCount > 0) universeCount.toString() else "—", Modifier.weight(1f))
                        StatTile("LIVE", marketCount.toString(), Modifier.weight(1f))
                        StatTile("TOP 10", active.size.toString(), Modifier.weight(1f))
                        StatTile("1S SCANS", scanCycles.toString(), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Scanner: ${humanStatus(status)} · Checked $checkedPairs live pairs · $evaluatedPairs new 1m candle setups", color = if (status.startsWith("LIVE")) Mint else Amber, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (lastScanAt > 0) "Last scan: ${java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date(lastScanAt))}" else "Waiting for first scan cycle", color = Muted, fontSize = 9.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onFiveMinuteScan, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Bg),
                        shape = RoundedCornerShape(12.dp)) {
                        Text("SCAN NEXT 5 MINUTES", fontWeight = FontWeight.Black, letterSpacing = .8.sp)
                    }
                    Text("One-shot scan · fresh quotes + closed 1m candles · all eligible USDT pairs", color = Muted, fontSize = 9.sp)

                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("TOP 10 OPPORTUNITIES", color = White, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = .8.sp)
                    Text("Ranked by rule score + risk filters", color = Muted, fontSize = 10.sp)
                }
                Text("LIVE CANDIDATES", color = Mint, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (active.isEmpty()) {
            item { EmptyPanel("No trade is the right decision", "SPLED is scanning eligible USDT perpetuals. A pair appears here only after the strategy, liquidity, spread and risk checks pass.") }
        } else {
            items(active, key = { it.symbol }) { SignalCard(it, active.indexOf(it) + 1) }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("NEXT 5-MINUTE OUTLOOK", color = White, fontSize = 14.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                    Text(if (fiveMinuteScanAt > 0) "CHECKED $fiveMinuteChecked" else "ON DEMAND", color = Mint, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Text(fiveMinuteStatus.replace('_', ' '), color = if (fiveMinuteSignals.isEmpty()) Amber else Muted, fontSize = 10.sp)
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("ACTUAL 5-MINUTE WIN RATE", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text(fiveMinuteWinRate?.let { "%.1f%%".format(Locale.US, it) } ?: "COLLECTING RESULTS",
                            color = if ((fiveMinuteWinRate ?: 0.0) >= 50.0) Mint else Amber,
                            fontSize = 24.sp, fontWeight = FontWeight.Black)
                        Text("$fiveMinuteWins wins · $fiveMinuteLosses losses · $fiveMinuteResolved resolved · $fiveMinutePending pending",
                            color = Muted, fontSize = 10.sp)
                        Text("Win rate = wins ÷ (wins + losses). Costs included; pending signals excluded.",
                            color = Muted, fontSize = 9.sp)
                    }
                }
                if (fiveMinuteSignals.isEmpty()) {
                    EmptyPanel("No confirmed directional setup", "Tap Scan after live data is ready. Weak or conflicting direction is filtered as NO TRADE.")
                } else {
                    fiveMinuteSignals.forEachIndexed { index, s ->
                        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("#${index + 1}  ${s.symbol}", color = White, fontWeight = FontWeight.Black, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    Text(s.direction, color = if (s.direction == "LONG") Mint else Red, fontWeight = FontWeight.Black, fontSize = 11.sp)
                                }
                                Text("${if (s.direction == "LONG") "+" else "-"}%.2f%% expected · max estimate %.2f%%".format(Locale.US, s.expectedMovePct, s.maxMovePct), color = if (s.direction == "LONG") Mint else Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PriceTile("ENTRY", s.entry, Modifier.weight(1f), White)
                                    PriceTile("TARGET", s.target, Modifier.weight(1f), Mint)
                                    PriceTile("MAX EST.", s.maxPrice, Modifier.weight(1f), Amber)
                                }
                                Text("5m momentum ${"%+.3f".format(Locale.US, s.return5mPct)}% · forecast horizon 5 min", color = Muted, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
        }
        item {
            Text("Historical five-minute hit rate is based on this device's resolved paper forecasts, not a guarantee of future results.", color = Amber, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.fillMaxWidth().background(Color(0x1AFFC46B), RoundedCornerShape(10.dp)).padding(11.dp))
        }
    }
}

@Composable
private fun SignalCard(s: Opportunity, rank: Int) {
    val long = s.direction == "LONG"
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(17.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#$rank", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(s.symbol, color = White, fontSize = 16.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Text(if (long) "LONG" else "SHORT", color = if (long) Mint else Red, fontWeight = FontWeight.Black, fontSize = 11.sp, modifier = Modifier.background(if (long) Color(0x1F59DDB2) else Color(0x1FFF7185), RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 5.dp))
                Spacer(Modifier.width(8.dp))
                Text("${s.score.toInt()} pts", color = Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Text(s.mode.replace('_', ' '), color = Muted, fontSize = 10.sp, letterSpacing = .5.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PriceTile("ENTRY", s.entry, Modifier.weight(1f), Mint)
                PriceTile("TARGET", s.target, Modifier.weight(1f), Mint)
                PriceTile("STOP LOSS", s.stop, Modifier.weight(1f), Red)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Risk / Reward", color = Muted, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                Text(s.rr?.let { "%.2f : 1".format(Locale.US, it) } ?: "—", color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text(s.status, color = if (s.status == "ACTIVE") Mint else Amber, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(color = Color(0xFF25354B))
            s.reasons.take(3).forEach { Text("• $it", color = Muted, fontSize = 10.sp, lineHeight = 14.sp) }
            Text("SL is structural invalidation. Do not widen it hoping for recovery.", color = Amber, fontSize = 9.sp, lineHeight = 13.sp)
        }
    }
}

@Composable
private fun MarketsScreen(modifier: Modifier, markets: List<MarketRow>, search: String, onSearch: (String) -> Unit) {
    val shown = markets.filter { it.symbol.contains(search.trim(), ignoreCase = true) }
        .sortedByDescending { it.volume ?: 0.0 }
    Column(modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("ALL MARKETS", color = White, fontSize = 17.sp, fontWeight = FontWeight.Black)
        Text("Eligible Binance USDT perpetuals · sorted by 24h quote volume", color = Muted, fontSize = 10.sp)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = search, onValueChange = onSearch, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search BTCUSDT, ETHUSDT…") }, shape = RoundedCornerShape(12.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Mint, unfocusedBorderColor = Panel2, focusedTextColor = White, unfocusedTextColor = White, cursorColor = Mint))
        Spacer(Modifier.height(8.dp))
        Text("${shown.size} markets currently received", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(shown, key = { it.symbol }) { m -> MarketCard(m) }
        }
    }
}

@Composable
private fun MarketCard(m: MarketRow) {
    val up = (m.change ?: 0.0) >= 0.0
    Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(m.symbol, color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("Vol ${m.volume?.let { compact(it) } ?: "—"} USDT · Spread ${m.spread?.let { "%.1f bps".format(Locale.US, it) } ?: "—"}", color = Muted, fontSize = 9.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(m.price?.let { price(it) } ?: "—", color = White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(m.change?.let { "%+.2f%%".format(Locale.US, it) } ?: "—", color = if (up) Mint else Red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PaperScreen(modifier: Modifier, account: PaperAccount) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 14.dp, bottom = 18.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("PAPER TRADING", color = White, fontSize = 17.sp, fontWeight = FontWeight.Black)
                            Text("Automatic virtual execution · no exchange orders", color = Muted, fontSize = 10.sp)
                        }
                        Text("SIM ONLY", color = Amber, fontSize = 10.sp, fontWeight = FontWeight.Black,
                            modifier = Modifier.background(Color(0x1FFFC46B), RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 6.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("EQUITY", "${"%.2f".format(Locale.US, account.equity)} USDT", Modifier.weight(1f))
                        StatTile("REALIZED", "${"%+.2f".format(Locale.US, account.realizedPnl)}", Modifier.weight(1f))
                        StatTile("FLOATING", "${"%+.2f".format(Locale.US, account.unrealizedPnl)}", Modifier.weight(1f))
                    }
                    Text("Virtual starting balance: ${"%.2f".format(Locale.US, account.initialBalance)} USDT · 1x notional · max ${account.maxPositions} open positions", color = Muted, fontSize = 9.sp)
                    Text("Sizing: 0.5% equity risk per trade, maximum 20% equity notional per position. Estimated fees 0.05% + slippage 0.05% per side.", color = Amber, fontSize = 9.sp, lineHeight = 13.sp)
                }
            }
        }
        item { Text("OPEN PAPER POSITIONS (${account.open.size}/${account.maxPositions})", color = White, fontSize = 14.sp, fontWeight = FontWeight.Black) }
        if (account.open.isEmpty()) item { EmptyPanel("No paper positions yet", "When a scanner setup passes all entry and risk filters, SPLED opens a virtual position automatically. No real funds are used.") }
        items(account.open, key = { it.id }) { t -> PaperTradeCard(t, true) }
        item { Text("RECENT CLOSED TRADES", color = White, fontSize = 14.sp, fontWeight = FontWeight.Black) }
        if (account.closed.isEmpty()) item { EmptyPanel("No closed paper trades", "Target, structural stop or the four-hour time limit will close virtual positions. Net P&L includes estimated fees and slippage.") }
        items(account.closed, key = { it.id }) { t -> PaperTradeCard(t, false) }
        item { Text("Paper results are simulated using live public prices and assumed costs. They are not exchange fills and do not predict real-money performance.", color = Amber, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.fillMaxWidth().background(Color(0x1AFFC46B), RoundedCornerShape(10.dp)).padding(11.dp)) }
    }
}

@Composable
private fun PaperTradeCard(t: PaperTradeRow, open: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.symbol, color = White, fontSize = 13.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Text(t.direction, color = if (t.direction == "LONG") Mint else Red, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(8.dp))
                Text(if (open) "OPEN" else t.status, color = if (open) Amber else if ((t.netPnl ?: 0.0) >= 0) Mint else Red, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PriceTile("ENTRY", t.entry, Modifier.weight(1f), Mint)
                PriceTile("TARGET", t.target, Modifier.weight(1f), Mint)
                PriceTile("STOP", t.stop, Modifier.weight(1f), Red)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Qty ${"%.5f".format(Locale.US, t.quantity)} · Risk ${"%.2f".format(Locale.US, t.riskUsdt)} USDT", color = Muted, fontSize = 9.sp, modifier = Modifier.weight(1f))
                if (!open) Text("Net ${"%+.3f".format(Locale.US, t.netPnl ?: 0.0)} USDT", color = if ((t.netPnl ?: 0.0) >= 0) Mint else Red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun JournalScreen(modifier: Modifier, history: List<Opportunity>) {
    Column(modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(14.dp))
        Text("SIGNAL JOURNAL", color = White, fontSize = 17.sp, fontWeight = FontWeight.Black)
        Text("Local signal history · latest 500 events", color = Muted, fontSize = 10.sp)
        Spacer(Modifier.height(10.dp))
        if (history.isEmpty()) EmptyPanel("No signals recorded yet", "When a setup passes the scanner filters, its entry, target, stop and outcome will be recorded here.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(history.sortedByDescending { it.createdAt }, key = { it.key }) { item ->
                Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(11.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${item.symbol} · ${item.direction}", color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("${item.mode.replace('_', ' ')} · Entry ${price(item.entry)}", color = Muted, fontSize = 9.sp)
                    }
                    Text(item.status, color = when (item.status) { "TARGET_HIT" -> Mint; "STOP_HIT" -> Red; else -> Amber }, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SystemScreen(modifier: Modifier, status: String, universeCount: Int, modelStatus: String) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Text("SYSTEM & RISK", color = White, fontSize = 17.sp, fontWeight = FontWeight.Black)
        InfoPanel("DATA SOURCE", "Binance USDⓈ-M Futures public market data. No API key and no exchange order permissions.")
        InfoPanel("SCANNER STATUS", "${humanStatus(status)} · $universeCount eligible USDT perpetual contracts discovered.")
        InfoPanel("DECISION ENGINE", "Seven rule-based strategies with spread, liquidity, freshness and net risk/reward gates.")
        InfoPanel("MODEL STATUS", "$modelStatus. No trained, independently validated AI model is installed. Scores are rule ranks, not win probabilities.")
        InfoPanel("STOP-LOSS POLICY", "Stop is placed beyond recent structural swing with an ATR buffer. If structure is invalidated, exit; never widen a stop to wait for a recovery.")
        InfoPanel("PHONE LIMITATION", "Scanning runs while this app process is alive and connected. Force-stop, battery killing, phone shutdown or network loss pauses scanning.")
        InfoPanel("EXECUTION", "Manual only. SPLED never places, modifies or cancels exchange orders.")
    }
}

@Composable
private fun BrandMark(size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        drawRoundRect(Panel2, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.toPx() * .25f))
        val w = this.size.width; val h = this.size.height
        val stroke = w * .075f
        drawLine(Mint, androidx.compose.ui.geometry.Offset(w*.22f,h*.70f), androidx.compose.ui.geometry.Offset(w*.43f,h*.49f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(Mint, androidx.compose.ui.geometry.Offset(w*.43f,h*.49f), androidx.compose.ui.geometry.Offset(w*.58f,h*.58f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(Mint, androidx.compose.ui.geometry.Offset(w*.58f,h*.58f), androidx.compose.ui.geometry.Offset(w*.79f,h*.29f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawCircle(Amber, radius = w*.055f, center = androidx.compose.ui.geometry.Offset(w*.79f,h*.29f))
    }
}

@Composable
private fun StatusPill(status: String) {
    val live = status.startsWith("LIVE")
    Row(Modifier.background(if (live) Color(0x1F59DDB2) else Color(0x1FFFC46B), CircleShape).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (live) Mint else Amber))
        Spacer(Modifier.width(6.dp))
        Text(if (live) "LIVE" else "SCANNING", color = if (live) Mint else Amber, fontSize = 9.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Column(modifier.background(Bg, RoundedCornerShape(10.dp)).padding(9.dp)) {
        Text(label, color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
        Text(value, color = White, fontSize = 17.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun PriceTile(label: String, value: Double?, modifier: Modifier, accent: Color) {
    Column(modifier.background(Bg, RoundedCornerShape(9.dp)).padding(8.dp)) {
        Text(label, color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(value?.let { price(it) } ?: "—", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EmptyPanel(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(body, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun InfoPanel(title: String, body: String) {
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(13.dp)).padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = Mint, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Text(body, color = White, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

private fun parsePaperTrade(x: JSONObject): PaperTradeRow = PaperTradeRow(
    id=x.optString("id"), symbol=x.optString("symbol"), direction=x.optString("direction"), mode=x.optString("mode"),
    entry=x.optDouble("entry"), target=x.optDouble("target"), stop=x.optDouble("stop"),
    quantity=x.optDouble("quantity"), riskUsdt=x.optDouble("risk_usdt"), status=x.optString("status"),
    netPnl=if (x.isNull("net_pnl")) null else x.optDouble("net_pnl"), openedAt=x.optLong("opened_at")
)

private fun parsePaperAccount(x: JSONObject): PaperAccount = PaperAccount(
    initialBalance=x.optDouble("initial_balance", 1000.0), realizedPnl=x.optDouble("realized_pnl"),
    unrealizedPnl=x.optDouble("unrealized_pnl"), equity=x.optDouble("equity", 1000.0),
    maxPositions=x.optInt("max_positions", 5),
    open=(0 until (x.optJSONArray("open_positions")?.length() ?: 0)).mapNotNull { x.optJSONArray("open_positions")?.optJSONObject(it)?.let(::parsePaperTrade) },
    closed=(0 until (x.optJSONArray("closed_trades")?.length() ?: 0)).mapNotNull { x.optJSONArray("closed_trades")?.optJSONObject(it)?.let(::parsePaperTrade) }
)

private fun parseMarkets(array: JSONArray?): List<MarketRow> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { x ->
        MarketRow(x.optString("symbol"), x.doubleOrNull("price"), x.doubleOrNull("change_24h_pct"), x.doubleOrNull("quote_volume_24h"), x.doubleOrNull("spread_bps"), x.doubleOrNull("data_age_seconds"))
    } }
}

private fun parseFiveMinuteSignals(array: JSONArray?): List<FiveMinuteSignal> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { x ->
        FiveMinuteSignal(x.optString("symbol"), x.optString("direction"), x.optDouble("entry"),
            x.optDouble("target"), x.optDouble("max_price"), x.optDouble("expected_move_pct"),
            x.optDouble("max_move_pct"), x.optInt("confluence"), x.optDouble("return_5m_pct"), x.optLong("expires_at"))
    } }
}

private fun parseSignals(array: JSONArray?): List<Opportunity> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::parseSignal) }
}

private fun parseSignal(x: JSONObject) = Opportunity(
    symbol = x.optString("symbol"), direction = x.optString("direction"), score = x.optDouble("score"),
    entry = x.optDouble("entry_reference"), target = x.optDouble("target_reference"),
    stop = x.doubleOrNull("structural_stop_reference"), rr = x.doubleOrNull("risk_reward"),
    mode = x.optString("mode"), status = x.optString("status", "ACTIVE"),
    aiStatus = x.optString("ai_status", "MODEL_NOT_VALIDATED"), aiProbability = x.doubleOrNull("ai_probability"),
    createdAt = x.optDouble("created_at"), reasons = x.optJSONArray("rationale").toStringList(),
    invalidation = x.optJSONArray("invalidation").toStringList()
)

private fun JSONObject.doubleOrNull(key: String): Double? = if (isNull(key) || !has(key)) null else optDouble(key).takeIf { it.isFinite() }
private fun JSONArray?.toStringList(): List<String> = this?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
private fun humanStatus(status: String): String = when {
    status.startsWith("LIVE") -> "Connected to Binance public streams"
    status.startsWith("DISCOVERED") -> "Universe discovered; warming up candles"
    status.startsWith("UNIVERSE_UPDATED") -> "Universe refreshed"
    status.startsWith("RECONNECTING") -> "Reconnecting market feed"
    status.startsWith("CONNECTION_ERROR") -> status
    status == "STOPPED" -> "Scanner stopped"
    else -> status.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}
private fun price(value: Double): String = if (value >= 1000.0) String.format(Locale.US, "%,.3f", value) else String.format(Locale.US, "%.6f", value)
private fun compact(value: Double): String = when {
    value >= 1e9 -> "%.2fB".format(Locale.US, value / 1e9)
    value >= 1e6 -> "%.2fM".format(Locale.US, value / 1e6)
    value >= 1e3 -> "%.1fK".format(Locale.US, value / 1e3)
    else -> "%.0f".format(Locale.US, value)
}
