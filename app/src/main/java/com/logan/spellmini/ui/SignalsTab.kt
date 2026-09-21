package com.logan.spellmini.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as SystemSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.logan.spellmini.Graph
import com.logan.spellmini.signals.Fact
import com.logan.spellmini.signals.Need
import com.logan.spellmini.signals.NowContext
import com.logan.spellmini.signals.SignalCatalog
import com.logan.spellmini.signals.SignalDef
import com.logan.spellmini.signals.SignalGroup
import com.logan.spellmini.signals.Weather
import com.logan.spellmini.sources.CalendarSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private fun granted(context: Context, need: Need): Boolean = when (need) {
    Need.NONE -> true
    Need.CALENDAR -> CalendarSource.allowed(context)
    Need.USAGE_ACCESS -> NowContext.usageAllowed(context)
    Need.LOCATION -> NowContext.locationAllowed(context)
    Need.IMAGES -> ContextCompat.checkSelfPermission(context, if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES" else Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    Need.CITY -> Graph.settings.cityName.isNotBlank()
}

/**
 * Every context the app can take in, on one page: what JEV sees of this moment, what may trigger a turn by itself, and
 * what flows in from outside. Each row says what it is for, what it needs and what extra leaves the phone because of it.
 */
@Composable
fun SignalsTab() {
    val context = LocalContext.current
    val settings = Graph.settings
    val version by settings.version.collectAsState()
    var refresh by remember { mutableStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    LifecycleResumeEffect(Unit) { refresh++; onPauseOrDispose { } }

    fun obtain(need: Need) = when (need) {
        Need.USAGE_ACCESS -> context.startActivity(Intent(SystemSettings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Need.IMAGES -> ask.launch(arrayOf(if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES" else Manifest.permission.READ_EXTERNAL_STORAGE))
        Need.LOCATION -> ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        else -> if (need.permissions.isNotEmpty()) ask.launch(need.permissions.toTypedArray()) else Unit
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("所有信号先汇到这里，由 JEV 判断值不值得，值得的才交给助理去办。开着的越多，它对「此刻」知道得越多；每一项旁边写明了它要什么、多发出去什么。", color = Ink.Muted, fontSize = 12.sp, lineHeight = 18.sp)
        RightNowCard(refresh + version)
        SignalGroup.values().forEach { group ->
            SectionTitle(group.label, Modifier.padding(top = 10.dp))
            Text(group.blurb, color = Ink.Muted, fontSize = 12.sp, lineHeight = 18.sp)
            SignalCatalog.ALL.filter { it.group == group }.forEach { def ->
                SignalRow(def, refresh + version, granted(context, def.need)) { obtain(def.need) }
                when (def.id) {
                    SignalCatalog.PLACE -> if (settings.signalOn(def.id, def.defaultOn)) PlaceFields(refresh) { ask.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) }
                    SignalCatalog.WEATHER_PLAN -> CityField()
                    SignalCatalog.MEETING_SOON -> if (settings.signalOn(def.id, def.defaultOn)) MeetingLead()
                }
            }
            if (group == SignalGroup.STREAM) SourcesManager()
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** What JEV would be told about this moment if a notification arrived now. */
@Composable
private fun RightNowCard(trigger: Int) {
    var facts by remember { mutableStateOf<List<Fact>>(emptyList()) }
    LaunchedEffect(trigger) {
        while (true) {
            // Off the main thread: reading the shade, the calendar and the usage log is no work for a frame.
            runCatching { withContext(Dispatchers.Default) { Graph.now.facts() } }
                .onSuccess { facts = it }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else android.util.Log.w("SpellSignals", "right-now card failed", it) }
            delay(5_000)
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.Bubble).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("JEV 现在看到的你", color = Ink.Black, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (facts.isEmpty()) Text("没有特别的状态：没在开会、没在通话、手机没静音。此刻来的通知按平常处理。", color = Ink.Muted, fontSize = 12.sp)
        facts.forEach { fact -> Text("· ${fact.chinese}", color = Ink.Body, fontSize = 13.sp) }
    }
}

@Composable
private fun SignalRow(def: SignalDef, trigger: Int, allowed: Boolean, obtain: () -> Unit) {
    val settings = Graph.settings
    val on = settings.signalOn(def.id, def.defaultOn)
    val scope = rememberCoroutineScope()
    var paced by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(trigger, on) {
        paced = if (def.group != SignalGroup.MOMENT || def.cooldownMin == 0) null else runCatching {
            val pkg = SignalCatalog.MOMENT_PKG + def.id
            val last = Graph.db.events().lastAtByPkg(pkg)
            val today = Graph.db.events().countByPkgSince(pkg, java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
            listOfNotNull(last?.let { "上次 ${formatClock(it)}" } ?: "还没触发过", "今天 $today/${def.perDay} 次", "冷却 ${if (def.cooldownMin >= 60) "${def.cooldownMin / 60} 小时" else "${def.cooldownMin} 分钟"}").joinToString(" · ")
        }.getOrNull()
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(def.title, color = Ink.Black, fontSize = 15.sp)
                Text(def.detail, color = Ink.Muted, fontSize = 12.sp, lineHeight = 17.sp)
                def.sendsOut?.let { Text("打开后会多发给模型：$it", color = Ink.Amber, fontSize = 12.sp) }
                if (on && !allowed) Text("还不能用，需要：${def.need.label}", color = Ink.Red, fontSize = 12.sp)
                if (on && allowed) paced?.let { Text(it, color = Ink.Faint, fontSize = 12.sp) }
            }
            Switch(checked = on, onCheckedChange = { value ->
                settings.setSignal(def.id, value)
                if (value && !allowed) obtain()
                if (def.id == SignalCatalog.MEETING_SOON || def.id == SignalCatalog.MEETING_ENDED) Graph.moments.rearmSoon()
            })
        }
        if (on) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!allowed && def.need != Need.CITY) Pill("去允许", Ink.Black, filled = true, onClick = obtain)
            if (def.group == SignalGroup.MOMENT && def.cooldownMin > 0 && def.id != SignalCatalog.SCREENSHOT) Pill("试一下", Ink.Black) {
                scope.launch { Graph.moments.fire(def.id, "试一下", sample(def.id), force = true) }
            }
        }
    }
}

/** What "试一下" pretends has happened. It goes through JEV and the playbook like the real thing, so the answer may well be silence. */
private fun sample(id: String): String = when (id) {
    SignalCatalog.WAKE_UP -> "他的闹钟刚响过并被关掉，多半是起床了。（这是「试一下」触发的）"
    SignalCatalog.CALL_ENDED -> "他刚结束一通电话：对方「138 开头的陌生号码」，通了约 4 分钟。通话内容你听不到。（这是「试一下」触发的）"
    SignalCatalog.ARRIVED -> "导航刚结束，持续了约 35 分钟，他多半到了目的地。（这是「试一下」触发的）"
    SignalCatalog.MEETING_SOON -> "日历上的下一场会 10 分钟后开始。（这是「试一下」触发的，请用 calendar_agenda 看看是哪一场）"
    SignalCatalog.MEETING_ENDED -> "日历上的一场一小时的会刚到结束时间。（这是「试一下」触发的）"
    SignalCatalog.HOME -> "他的手机刚连上家里的 Wi‑Fi，多半是到家了。（这是「试一下」触发的）"
    SignalCatalog.WORK -> "他的手机刚连上公司的 Wi‑Fi，多半是到公司了。（这是「试一下」触发的）"
    SignalCatalog.BEDTIME -> "夜里，他刚给手机插上充电器，多半准备睡了。（这是「试一下」触发的）"
    SignalCatalog.APP_INSTALLED -> "他刚在手机上装了一个新 App：「携程旅行」。（这是「试一下」触发的）"
    SignalCatalog.BACK_TO_PHONE -> "他放下手机约 120 分钟后刚刚解锁。（这是「试一下」触发的）"
    SignalCatalog.WEATHER_PLAN -> "明天的天气：8–15℃；最高降水概率 80%；降水概率过半的时段：7–10 点。（这是「试一下」触发的，日程请用 calendar_agenda 查）"
    else -> "（这是「试一下」触发的）"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceFields(trigger: Int, askBackground: () -> Unit) {
    val context = LocalContext.current
    val settings = Graph.settings
    var wifi by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(trigger) { wifi = NowContext.currentWifi(context) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.Bubble).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (wifi == null) "现在读不到 Wi‑Fi 名称：没连 Wi‑Fi，或者定位权限、系统定位开关没开。" else "现在连着：$wifi", color = Ink.Body, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            wifi?.let { name ->
                Pill("把它记为家", Ink.Black) { settings.homeWifi = (settings.homeWifi.lines() + name).filter { it.isNotBlank() }.distinct().joinToString("\n") }
                Pill("把它记为公司", Ink.Black) { settings.workWifi = (settings.workWifi.lines() + name).filter { it.isNotBlank() }.distinct().joinToString("\n") }
            }
            if (Build.VERSION.SDK_INT >= 29) Pill("后台也允许定位", Ink.Muted, onClick = askBackground)
        }
        listOf("家" to settings.homeWifi, "公司" to settings.workWifi).forEach { (label, names) ->
            if (names.isNotBlank()) Text("$label：${names.lines().joinToString("、")}（点一下清空）", color = Ink.Muted, fontSize = 12.sp, modifier = Modifier.clickable { if (label == "家") settings.homeWifi = "" else settings.workWifi = "" })
        }
    }
}

@Composable
private fun CityField() {
    val settings = Graph.settings
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(settings.cityName) }
    var note by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it; note = null }, label = { Text("城市") }, singleLine = true, modifier = Modifier.weight(1f))
        Pill("查找", Ink.Black, filled = true) {
            scope.launch {
                runCatching { Weather.findCity(name) }.fold(
                    onSuccess = { settings.cityLat = it.lat; settings.cityLon = it.lon; settings.cityName = it.name; name = it.name; note = "已设为 ${it.name}" },
                    onFailure = { note = it.message?.take(80) },
                )
            }
        }
    }
    note?.let { Text(it, color = Ink.Muted, fontSize = 12.sp) }
}

@Composable
private fun MeetingLead() {
    val settings = Graph.settings
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("提前", color = Ink.Muted, fontSize = 13.sp)
        Pill("－", Ink.Black) { settings.meetingLeadMin -= 1; Graph.moments.rearmSoon() }
        Text("${settings.meetingLeadMin} 分钟", color = Ink.Black, fontSize = 13.sp)
        Pill("＋", Ink.Black) { settings.meetingLeadMin += 1; Graph.moments.rearmSoon() }
    }
}
