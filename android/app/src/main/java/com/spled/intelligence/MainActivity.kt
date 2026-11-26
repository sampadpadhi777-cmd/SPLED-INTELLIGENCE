package com.spled.intelligence

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

private val Bg = Color(0xFF080D16); private val Panel = Color(0xFF121E2D)
private val Muted = Color(0xFF91A4BA); private val Green = Color(0xFF55D6A3); private val Red = Color(0xFFFF7777)

data class MarketRow(val symbol:String,val price:Double?,val change:Double?,val volume:Double?,val spread:Double?,val age:Double?)
data class Opportunity(val symbol:String,val direction:String,val score:Double,val entry:Double,val target:Double,val stop:Double?,val rr:Double?,val mode:String,val status:String,val reasons:List<String>,val invalidation:List<String>,val aiProbability:Double?,val aiStatus:String)

class MainActivity:ComponentActivity(){
 private var localEngine:LocalMarketEngine?=null
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{App(::connect,::disconnect)}}
 private fun connect(onEvent:(String,String)->Unit){
  if(localEngine!=null)return
  val engine=LocalMarketEngine(this,onEvent);localEngine=engine;engine.start()
 }
 private fun disconnect(){localEngine?.stop();localEngine=null}
 override fun onDestroy(){disconnect();super.onDestroy()}
}

@Composable fun App(connect:((String,String)->Unit)->Unit, disconnect:()->Unit){
 var status by remember{mutableStateOf("CONNECTING")}; var aiStatus by remember{mutableStateOf("MODEL_NOT_INSTALLED")}; var markets by remember{mutableStateOf(listOf<MarketRow>())}; var signals by remember{mutableStateOf(listOf<Opportunity>())}; var history by remember{mutableStateOf(listOf<Opportunity>())}; var tab by remember{mutableIntStateOf(0)}; var query by remember{mutableStateOf("")}
 DisposableEffect(Unit){connect{type,value->if(type=="status")status=value else try{val o=JSONObject(value);when(o.optString("type")){
  "snapshot"->{val d=o.optJSONObject("data")?:return@connect;aiStatus=d.optString("ai_model_status","MODEL_NOT_INSTALLED");markets=parseMarkets(d.optJSONArray("markets"));signals=parseSignals(d.optJSONArray("signals"));history=parseSignals(d.optJSONArray("history"))}
  "market_update"->{val m=parseMarket(o.optJSONObject("market")?:return@connect);markets=(listOf(m)+markets.filterNot{it.symbol==m.symbol}).take(1000)}
  "market_batch"->{val rows=parseMarkets(o.optJSONArray("markets"));val bySymbol=(markets+rows).associateBy{it.symbol};markets=bySymbol.values.toList().sortedByDescending{it.volume?:0.0}.take(1000)}
  "signal_update"->{val s=parseSignal(o.optJSONObject("signal")?:return@connect);signals=(listOf(s)+signals.filterNot{it.symbol==s.symbol && it.direction==s.direction && it.mode==s.mode}).take(500);history=(listOf(s)+history).take(500)}
 }}catch(_:Exception){}};onDispose{disconnect()}}
 MaterialTheme(colorScheme=darkColorScheme(primary=Green,background=Bg,surface=Panel,onSurface=Color.White)){
  Scaffold(containerColor=Bg,topBar={Column(Modifier.fillMaxWidth().background(Bg).padding(horizontal=18.dp, vertical=14.dp)){Text("SPLED INTELLIGENCE",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,color=Color.White);Text("BINANCE FUTURES · ON-DEVICE SCANNER · MANUAL EXECUTION",style=MaterialTheme.typography.labelSmall,color=Muted);Spacer(Modifier.height(10.dp));Row(verticalAlignment=Alignment.CenterVertically){Text("●",color=if(status.startsWith("LIVE"))Green else Color(0xFFFFC266));Spacer(Modifier.width(7.dp));Text("LOCAL BINANCE FEED  $status",color=Muted,style=MaterialTheme.typography.labelMedium);Spacer(Modifier.weight(1f));Text("${markets.size} symbols",color=Muted,style=MaterialTheme.typography.labelMedium)};Text("AI MODEL: $aiStatus",color=if(aiStatus=="SHADOW_MODEL_ENABLED")Green else Color(0xFFFFC266),style=MaterialTheme.typography.labelSmall)}},bottomBar={NavigationBar(containerColor=Panel){listOf("Radar","Markets","History").forEachIndexed{i,t->NavigationBarItem(selected=tab==i,onClick={tab=i},icon={},label={Text(t)})}}}){pad->Column(Modifier.fillMaxSize().padding(pad).padding(horizontal=14.dp)){
  when(tab){
   0->{Row(Modifier.fillMaxWidth().padding(vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){Metric("TRACKED",markets.size.toString(),Modifier.weight(1f));Metric("CANDIDATES",signals.size.toString(),Modifier.weight(1f));Metric("MODE","HYBRID / SHADOW",Modifier.weight(1f))};Text("OPPORTUNITY RADAR",style=MaterialTheme.typography.titleLarge,color=Color.White,fontWeight=FontWeight.SemiBold);Text("7 rule-based strategies · eligible USDT perpetuals · runs while this app is active · AI shadow model only when a validated artifact is installed",color=Muted,style=MaterialTheme.typography.bodySmall)
    Text("NOT APPROVED FOR REAL-MONEY RELIANCE · PAPER / SHADOW TEST ONLY. AI estimates are not guaranteed outcomes.",color=Color(0xFFFF8B8B),style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(top=4.dp))
    Text("LOCAL MODE: scanning pauses if Android stops this app, the phone is off, or internet disconnects.",color=Color(0xFFFFC266),style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(top=4.dp))
    Spacer(Modifier.height(8.dp));if(signals.isEmpty())EmptyState("No qualifying setup right now","The scanner will show a candidate only when its filters pass. No-trade is a valid result.") else LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)){items(signals,key={it.symbol+it.mode+it.direction}){SignalCard(it)}}}
   1->{OutlinedTextField(query,{query=it},modifier=Modifier.fillMaxWidth().padding(vertical=8.dp),placeholder={Text("Search symbol")},singleLine=true);LazyColumn(verticalArrangement=Arrangement.spacedBy(7.dp)){items(markets.filter{it.symbol.contains(query.trim(),true)}.sortedByDescending{it.volume?:0.0},key={it.symbol}){MarketCard(it)}}}
   2->{Text("RECENT SIGNALS",style=MaterialTheme.typography.titleLarge,color=Color.White,modifier=Modifier.padding(vertical=12.dp));Text("On-device session signal journal · latest received signals",color=Muted);LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)){items(history,key={it.symbol+it.mode+it.direction+it.entry}){SignalCard(it)}}}
  }
 }}
 }
}
@Composable private fun Metric(label:String,value:String,modifier:Modifier){Card(modifier,colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(12.dp)){Column(Modifier.padding(11.dp)){Text(label,color=Muted,style=MaterialTheme.typography.labelSmall);Text(value,color=Color.White,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)}}}
@Composable private fun SignalCard(s:Opportunity){Card(colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(15.dp)){Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
 Row(verticalAlignment=Alignment.CenterVertically){Text(s.symbol,color=Color.White,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));Text(s.direction,color=if(s.direction=="LONG")Green else Red,fontWeight=FontWeight.Bold);Spacer(Modifier.width(10.dp));Text("RULE RANK ${s.score.toInt()}/100",color=Muted)}
 Text(s.mode.replace('_',' '),color=Muted,style=MaterialTheme.typography.labelSmall)
 Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){Level("ENTRY",s.entry);Level("TARGET",s.target);Level("STRUCTURAL SL",s.stop)}
 Text("Risk / reward: ${s.rr?.let{"%.2f:1".format(it)}?:"—"}",color=Color.White,style=MaterialTheme.typography.bodyMedium)
 Text(if(s.aiProbability!=null) "SHADOW MODEL ESTIMATE: %.1f%% · NOT LIVE-APPROVED".format(s.aiProbability*100) else "${s.aiStatus} · NO VALIDATED AI PROBABILITY",color=Color(0xFFFFC266),style=MaterialTheme.typography.labelSmall)
 s.reasons.take(4).forEach{Text("• $it",color=Muted,style=MaterialTheme.typography.bodySmall)}
 }} }
@Composable private fun Level(label:String,value:Double?){Column(Modifier.background(Bg,RoundedCornerShape(8.dp)).padding(8.dp).widthIn(min=78.dp)){Text(label,color=Muted,style=MaterialTheme.typography.labelSmall);Text(value?.let{fmt(it)}?:"N/A",color=Color.White,style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.SemiBold)}}
@Composable private fun MarketCard(m:MarketRow){Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(10.dp)).padding(11.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(m.symbol,color=Color.White,fontWeight=FontWeight.SemiBold);Text("Vol ${m.volume?.let{compact(it)}?:"—"} USDT · Spread ${m.spread?.let{"%.1f bps".format(it)}?:"—"}",color=Muted,style=MaterialTheme.typography.labelSmall)};Column(horizontalAlignment=Alignment.End){Text(m.price?.let{fmt(it)}?:"—",color=Color.White);Text(m.change?.let{"%.2f%%".format(it)}?:"—",color=if((m.change?:0.0)>=0)Green else Red)}}}
@Composable private fun EmptyState(title:String,body:String){Card(colors=CardDefaults.cardColors(containerColor=Panel)){Column(Modifier.fillMaxWidth().padding(18.dp)){Text(title,color=Color.White,fontWeight=FontWeight.Bold);Text(body,color=Muted,style=MaterialTheme.typography.bodySmall)}}}
private fun parseMarkets(a:org.json.JSONArray?):List<MarketRow>{val out=mutableListOf<MarketRow>();if(a!=null)for(i in 0 until a.length())out.add(parseMarket(a.getJSONObject(i)));return out}
private fun parseMarket(x:JSONObject)=MarketRow(x.optString("symbol"),x.dbl("price"),x.dbl("change_24h_pct"),x.dbl("quote_volume_24h"),x.dbl("spread_bps"),x.dbl("data_age_seconds"))
private fun parseSignals(a:org.json.JSONArray?):List<Opportunity>{val out=mutableListOf<Opportunity>();if(a!=null)for(i in 0 until a.length())out.add(parseSignal(a.getJSONObject(i)));return out}
private fun parseSignal(x:JSONObject)=Opportunity(x.optString("symbol"),x.optString("direction"),x.optDouble("score"),x.optDouble("entry_reference"),x.optDouble("target_reference"),x.dbl("structural_stop_reference"),x.dbl("risk_reward"),x.optString("mode"),x.optString("probability_status"),x.stringList("rationale"),x.stringList("invalidation"),x.dbl("ai_probability"),x.optString("ai_status","MODEL_NOT_VALIDATED"))
private fun JSONObject.dbl(k:String):Double?=if(isNull(k))null else optDouble(k).takeIf{it.isFinite()}
private fun JSONObject.stringList(k:String):List<String>{val a=optJSONArray(k)?:return emptyList();return (0 until a.length()).map{a.optString(it)}}
private fun fmt(v:Double) = if(v>=1000)"%,.3f".format(v) else "%.6f".format(v)
private fun compact(v:Double)=when{v>=1e9->"%.2fB".format(v/1e9);v>=1e6->"%.2fM".format(v/1e6);v>=1e3->"%.1fK".format(v/1e3);else->"%.0f".format(v)}
