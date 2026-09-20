package com.logan.spellmini.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.logan.spellmini.Graph
import com.logan.spellmini.data.EventStatus
import com.logan.spellmini.data.NotifEvent
import com.logan.spellmini.data.Outcome
import com.logan.spellmini.data.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.Calendar

private enum class HubTab(val label: String) { TRACE("通知流水"), PROFILE("画像"), SETTINGS("设置") }

@Composable
fun HubScreen(onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(HubTab.TRACE) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = Ink.Black) }
            HubTab.values().forEach { item ->
                val selected = item == tab
                Text(
                    item.label,
                    modifier = Modifier.padding(horizontal = 10.dp).clickable { tab = item },
                    color = if (selected) Ink.Black else Ink.Muted,
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
        HorizontalDivider(color = Ink.Line)
        when (tab) {
            HubTab.TRACE -> TraceTab()
            HubTab.PROFILE -> ProfileTab()
            HubTab.SETTINGS -> SettingsTab()
        }
    }
}

private val traceFilters = listOf("全部", Route.CHAT, Route.FEED, Route.IGNORE, "二判", "已过滤", "出错")

private fun NotifEvent.matches(filter: String): Boolean = when (filter) {
    "全部" -> true
    "二判" -> secondJudgeNote != null
    "已过滤" -> status == EventStatus.FILTERED || status == EventStatus.APP_OFF
    "出错" -> status == EventStatus.ERROR || outcome == Outcome.ERROR
    else -> status != EventStatus.FILTERED && status != EventStatus.APP_OFF && (finalRoute ?: route) == filter
}

@Composable
private fun TraceTab() {
    val events by Graph.db.events().recent(800).collectAsState(initial = emptyList())
    var filter by rememberSaveable { mutableStateOf("全部") }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        TodaySummary(events)
        // Kept out of the scrolling filter row below: at its far end nobody would find it.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("导出完整数据", Ink.Blue, filled = true) { scope.launch { exportEverything(context) } }
            Pill("只导出流水 JSONL", Ink.Blue) { scope.launch { exportTrace(context) } }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            traceFilters.forEach { name ->
                Pill("$name ${events.count { it.matches(name) }}", Ink.Black, filled = filter == name) { filter = name }
            }
        }
        val shown = events.filter { it.matches(filter) }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("还没有通知记录。\n确认「设置」里的通知使用权已开启，或用「模拟通知」试一条。", color = Ink.Muted, fontSize = 14.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { event -> EventRow(event) { selectedId = event.id } }
            }
        }
    }
    events.firstOrNull { it.id == selectedId }?.let { EventDetail(it, onDismiss = { selectedId = null }) }
}

@Composable
private fun TodaySummary(events: List<NotifEvent>) {
    val startOfDay = remember(events.size) {
        Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
    }
    val today = events.filter { it.postedAt >= startOfDay }
    val received = today.count { it.status != EventStatus.INTEREST }
    val patrols = today.count { it.status == EventStatus.INTEREST }
    val judged = today.filter { it.status == EventStatus.JUDGED }
    val latencies = judged.mapNotNull { it.jevLatencyMs }.sorted()
    val median = latencies.getOrNull(latencies.size / 2)
    val cost = today.sumOf { (it.jevCostUsd ?: 0.0) + (it.downstreamCostUsd ?: 0.0) }
    val spoke = today.count { it.outcome == Outcome.CHAT_SENT }
    val silent = today.count { it.outcome == Outcome.CHAT_SILENT }
    val cards = today.count { it.outcome == Outcome.FEED_CARD }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            "今天收到 $received 条通知，送 JEV 判断 ${judged.size} 条" + if (patrols > 0) "；兴趣巡查 $patrols 个选题" else "",
            color = Ink.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "主动开口 $spoke · 选择沉默 $silent · Feed 卡 $cards · JEV 中位耗时 ${median?.let { "$it ms" } ?: "—"} · 花费 ${formatUsd(cost)}",
            color = Ink.Muted, fontSize = 12.sp,
        )
    }
}

@Composable
private fun EventRow(event: NotifEvent, onClick: () -> Unit) {
    val (label, color) = event.verdictLabel()
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(event.appName + if (event.synthetic) " · 模拟" else "", color = Ink.Muted, fontSize = 12.sp, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Text(formatClock(event.postedAt), color = Ink.Faint, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            event.confidence?.let { Text("${(it * 100).toInt()}%", color = Ink.Muted, fontSize = 12.sp) }
            Spacer(Modifier.width(8.dp))
            Pill(label, color, filled = label == Route.CHAT || label == Route.FEED)
        }
        if (event.title.isNotBlank()) {
            Text(event.title, color = Ink.Black, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (event.text.isNotBlank()) {
            Text(event.text, color = Ink.Body, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        outcomeLine(event)?.let { Text(it, color = Ink.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
    HorizontalDivider(color = Ink.Line, modifier = Modifier.padding(start = 16.dp))
}

private fun outcomeLine(event: NotifEvent): String? = when {
    event.filterReason != null -> "未外发：${event.filterReason}"
    event.outcome == Outcome.PENDING -> if (event.finalRoute == Route.FEED) "→ 正在查资料、写卡片…" else "→ 排队等主模型处理…"
    event.outcome == Outcome.CHAT_SENT -> "→ 已在 Chat 主动开口" + event.outcomeNote?.let { " · $it" }.orEmpty()
    event.outcome == Outcome.CHAT_SILENT -> "→ 主模型选择沉默：${event.outcomeNote.orEmpty()}"
    event.outcome == Outcome.FEED_CARD -> "→ 已生成 Feed 卡"
    event.outcome == Outcome.FEED_SKIPPED -> "→ 未生成卡片：${event.outcomeNote.orEmpty()}"
    event.outcome == Outcome.CAPPED -> "→ ${event.outcomeNote.orEmpty()}"
    event.outcome == Outcome.ERROR -> "→ 下游出错：${event.outcomeNote.orEmpty()}"
    event.jevError != null -> "JEV 出错：${event.jevError}"
    event.outcome == Outcome.NONE && event.outcomeNote != null -> "→ ${event.outcomeNote}"
    else -> null
}

@Composable
private fun EventDetail(event: NotifEvent, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val probabilities = remember(event.routeProbs) {
        runCatching { Json.parseToJsonElement(event.routeProbs ?: "{}") as JsonObject }.getOrNull()
            ?.mapValues { it.value.jsonPrimitive.doubleOrNull ?: 0.0 }.orEmpty()
            .entries.sortedByDescending { it.value }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            Row {
                TextButton(onClick = { copy(context, eventJson(event).toString()) }) { Text("复制 JSON") }
                if (event.status == EventStatus.JUDGED || event.status == EventStatus.ERROR) {
                    TextButton(onClick = { Graph.pipeline.rejudge(event.id); onDismiss() }) { Text("重判") }
                }
            }
        },
        title = { Text("${event.appName} · ${formatClock(event.postedAt)}", fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (event.title.isNotBlank()) Text(event.title, fontWeight = FontWeight.SemiBold, color = Ink.Black)
                Text(event.text.ifBlank { "（无正文）" }, color = Ink.Body, fontSize = 14.sp)
                if (event.mergedCount > 1) Text("合并了 ${event.mergedCount} 次更新", color = Ink.Muted, fontSize = 12.sp)
                HorizontalDivider(color = Ink.Line)
                if (probabilities.isNotEmpty()) {
                    SectionTitle("JEV 各选项概率")
                    probabilities.forEach { (name, p) -> ProbabilityBar(name, p) }
                }
                val facts = listOfNotNull(
                    event.urgency?.let { "紧急度 %.2f / 3".format(it) },
                    event.jevLatencyMs?.let { "JEV $it ms" },
                    event.jevCostUsd?.let { "JEV " + formatUsd(it) },
                    event.downstreamLatencyMs?.let { "下游 $it ms" },
                    event.downstreamCostUsd?.let { "下游 " + formatUsd(it) },
                )
                if (facts.isNotEmpty()) Text(facts.joinToString(" · "), color = Ink.Muted, fontSize = 12.sp)
                listOfNotNull(
                    event.jevModel?.let { "模型 $it（${event.jevSource}）" },
                    event.criteriaVersion?.let { "判据版本 $it" },
                    event.category?.let { "通知类别 $it" },
                    "包名 ${event.pkg}",
                ).forEach { Text(it, color = Ink.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                event.secondJudgeNote?.let { Text("二判：$it → ${event.finalRoute}", color = Ink.Amber, fontSize = 13.sp) }
                outcomeLine(event)?.let { Text(it, color = Ink.Body, fontSize = 13.sp) }
            }
        },
    )
}

@Composable
private fun ProbabilityBar(name: String, probability: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, modifier = Modifier.width(56.dp), color = Ink.Body, fontSize = 12.sp)
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Ink.Bubble)) {
            Box(
                Modifier.fillMaxWidth(probability.toFloat().coerceIn(0f, 1f)).height(8.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Ink.Black)
            )
        }
        Text("%.0f%%".format(probability * 100), modifier = Modifier.width(44.dp).padding(start = 8.dp), color = Ink.Muted, fontSize = 12.sp)
    }
}

private fun eventJson(event: NotifEvent): JsonObject = buildJsonObject {
    put("id", event.id)
    put("posted_at", event.postedAt)
    put("app", event.appName)
    put("package", event.pkg)
    put("category", event.category)
    put("title", event.title)
    put("text", event.text)
    put("merged_count", event.mergedCount)
    put("synthetic", event.synthetic)
    put("status", event.status)
    put("filter_reason", event.filterReason)
    put("route", event.route)
    put("final_route", event.finalRoute)
    put("route_probabilities", event.routeProbs?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: kotlinx.serialization.json.JsonNull)
    put("confidence", event.confidence)
    put("urgency", event.urgency)
    put("jev_model", event.jevModel)
    put("jev_source", event.jevSource)
    put("jev_latency_ms", event.jevLatencyMs)
    put("jev_cost_usd", event.jevCostUsd)
    put("jev_error", event.jevError)
    put("criteria_version", event.criteriaVersion)
    put("second_judge_note", event.secondJudgeNote)
    put("outcome", event.outcome)
    put("outcome_note", event.outcomeNote)
    put("downstream_latency_ms", event.downstreamLatencyMs)
    put("downstream_cost_usd", event.downstreamCostUsd)
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("spell-mini", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

private fun parsed(jsonText: String?) = jsonText?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: kotlinx.serialization.json.JsonNull

/**
 * One JSON file with everything a review needs: the trace, what the assistant actually said and which cards it made,
 * the feed cards in full, the profile and the settings in force. The API key lives in BuildConfig and is never included.
 */
private suspend fun exportEverything(context: Context) {
    val file = withContext(Dispatchers.IO) {
        val settings = Graph.settings
        val criteria = settings.criteria
        val bundle = buildJsonObject {
            put("exported_at", System.currentTimeMillis())
            put("app_version", com.logan.spellmini.BuildConfig.VERSION_NAME)
            put("settings", buildJsonObject {
                put("jev_model", settings.jevModel); put("chat_model", settings.chatModel)
                put("criteria_version", criteria.version)
                put("criteria", buildJsonObject {
                    put("instructions", criteria.instructions); put("chat", criteria.chat); put("feed", criteria.feed)
                    put("ignore", criteria.ignore); put("review", criteria.review)
                })
                put("pipeline_enabled", settings.pipelineEnabled)
                put("quiet_window_ms", settings.quietWindowMs); put("max_wait_ms", settings.maxWaitMs)
                put("chat_per_hour_cap", settings.chatPerHourCap); put("feed_per_day_cap", settings.feedPerDayCap)
                put("alert_urgency", settings.alertUrgencyTenths / 10.0); put("must_speak_when_urgent", settings.mustSpeakWhenUrgent)
                put("interest_feed_enabled", settings.interestFeedEnabled); put("interest_interval_min", settings.interestIntervalMin)
                put("interest_batch_size", settings.interestBatchSize); put("interest_per_day_cap", settings.interestPerDayCap)
                put("auto_profile", settings.autoProfile)
            })
            put("profile", buildJsonObject {
                put("user_written", settings.userProfile)
                put("learned", kotlinx.serialization.json.JsonArray(Graph.db.memory().list().map {
                    buildJsonObject { put("id", it.id); put("text", it.text); put("source", it.source); put("updated_at", it.updatedAt) }
                }))
                put("log", kotlinx.serialization.json.JsonArray(Graph.db.memory().allLogs().map {
                    buildJsonObject { put("time", it.time); put("summary", it.summary) }
                }))
            })
            put("events", kotlinx.serialization.json.JsonArray(Graph.db.events().all().map { eventJson(it) }))
            put("messages", kotlinx.serialization.json.JsonArray(Graph.db.messages().everything().map {
                buildJsonObject {
                    put("id", it.id); put("role", it.role); put("kind", it.kind); put("text", it.text)
                    put("created_at", it.createdAt); put("event_id", it.eventId); put("source_label", it.sourceLabel)
                    put("card", parsed(it.cardJson)); put("card_state", it.cardState)
                }
            }))
            put("feed", kotlinx.serialization.json.JsonArray(Graph.db.feed().everything().map {
                buildJsonObject {
                    put("id", it.id); put("event_id", it.eventId); put("emoji", it.emoji); put("title", it.title)
                    put("body", it.body); put("bullets", parsed(it.bulletsJson)); put("reason", it.reason)
                    put("sources", parsed(it.sourcesJson)); put("images", parsed(it.imagesJson))
                    put("source_label", it.sourceLabel); put("created_at", it.createdAt)
                    put("liked", it.liked); put("dismissed", it.dismissed)
                }
            }))
            put("apps", kotlinx.serialization.json.JsonArray(Graph.db.appRules().list().map {
                buildJsonObject { put("package", it.pkg); put("name", it.appName); put("enabled", it.enabled); put("count", it.count) }
            }))
        }
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        File(dir, "spell-mini-export-$stamp.json").apply { writeText(bundle.toString()) }
    }
    val uri = FileProvider.getUriForFile(context, "com.logan.spellmini.files", file)
    val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "导出完整数据").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Writes the whole trace as JSONL and hands it to the share sheet; nothing leaves the phone unless the user picks a target. */
private suspend fun exportTrace(context: Context) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        File(dir, "spell-mini-trace-${System.currentTimeMillis()}.jsonl").apply {
            bufferedWriter().use { out -> Graph.db.events().all().forEach { out.appendLine(eventJson(it).toString()) } }
        }
    }
    val uri = FileProvider.getUriForFile(context, "com.logan.spellmini.files", file)
    val send = Intent(Intent.ACTION_SEND).setType("application/x-ndjson").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "导出通知流水").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
