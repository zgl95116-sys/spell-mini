package com.logan.spellmini.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logan.spellmini.Graph
import com.logan.spellmini.data.FeedSource
import com.logan.spellmini.data.MemorySource
import com.logan.spellmini.data.Secrets
import com.logan.spellmini.data.SourceConfig
import com.logan.spellmini.data.SourceKind
import com.logan.spellmini.data.SourceTemplate
import com.logan.spellmini.data.SourceTemplates
import kotlinx.coroutines.launch

/** Which fields of [FeedSource.config] the form shows for a kind, with the label and the hint for each. */
private fun fieldsFor(kind: String): List<Triple<String, String, String>> = when (kind) {
    SourceKind.JSON -> listOf(
        Triple(SourceConfig.ITEMS, "列表在哪（items）", "如 data、events；响应本身就是列表就留空"),
        Triple(SourceConfig.ID, "每条的标识（id）", "如 {id}，可拼：{id}:{updated_at}"),
        Triple(SourceConfig.TITLE, "标题（title）", "如 {title} 或 {subject.title}"),
        Triple(SourceConfig.TEXT, "正文（text）", "如 {summary}，可拼多个 {字段}"),
        Triple(SourceConfig.LINK, "链接（link）", "如 {url} 或 https://…/{id}"),
        Triple(SourceConfig.DATE, "时间字段（date）", "路径，如 publishedAt；可不填"),
        Triple(SourceConfig.VALUE, "盯一个数：数在哪（value）", "如 rates.JPY；填了这项就按「盯一个数」处理"),
        Triple(SourceConfig.SCALE, "除以（scale）", "秒换成分钟填 60；可不填"),
        Triple(SourceConfig.LABEL, "怎么说（label）", "如 现在要 {value} 分钟（上次 {last}）"),
        Triple(SourceConfig.CHANGE, "变动超过百分之几才说（change）", "如 1.5"),
        Triple(SourceConfig.ABOVE, "升到多少才说（above）", ""),
        Triple(SourceConfig.BELOW, "降到多少才说（below）", ""),
        Triple(SourceConfig.HOURS, "只在这些钟点看（hours）", "如 17-20；可不填"),
        Triple(SourceConfig.FIT, "多贴近你才成卡（fit，0 到 3）", "留空用默认；0 表示这个源全收"),
        Triple(SourceConfig.HEADER, "令牌放进哪个请求头（header）", "如 Authorization；不需要就留空"),
        Triple(SourceConfig.PREFIX, "令牌前面加什么（prefix）", "如 Bearer 后面带一个空格"),
    )
    SourceKind.PAGE, SourceKind.ICS -> listOf(
        Triple(SourceConfig.HOURS, "只在这些钟点看（hours）", "如 8-22；可不填"),
        Triple(SourceConfig.FIT, "多贴近你才成卡（fit，0 到 3）", "留空用默认；0 表示这个源全收"),
        Triple(SourceConfig.HEADER, "令牌放进哪个请求头（header）", "不需要就留空"),
        Triple(SourceConfig.PREFIX, "令牌前面加什么（prefix）", ""),
    )
    SourceKind.SSE, SourceKind.WEBSOCKET -> listOf(
        Triple(SourceConfig.TITLE, "标题（title）", "消息是 JSON 时用 {路径}；否则留空"),
        Triple(SourceConfig.TEXT, "正文（text）", "如 {message}；留空就用整条消息"),
        Triple(SourceConfig.SEND, "连上后先发一句（send）", "WebSocket 的订阅请求；可不填"),
        Triple(SourceConfig.HEADER, "令牌放进哪个请求头（header）", "不需要就留空"),
        Triple(SourceConfig.PREFIX, "令牌前面加什么（prefix）", ""),
    )
    SourceKind.IMAP -> listOf(Triple(SourceConfig.USER, "邮箱地址", "name@example.com"))
    else -> emptyList()
}

private fun needsSecret(kind: String, config: Map<String, String>) = kind == SourceKind.IMAP || !config[SourceConfig.HEADER].isNullOrBlank()

/**
 * Every outside source in one list, whatever its kind, and the way to add more: a template for the well-known ones, a
 * form for anything else that answers with a feed, JSON, a page, a calendar, a stream or a mailbox.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourcesManager() {
    val entries by Graph.db.memory().watchBySource(MemorySource.SOURCE).collectAsState(initial = emptyList())
    val sources = remember(entries) { entries.mapNotNull(FeedSource::parse).sortedWith(compareBy({ !it.enabled }, { it.createdAt })) }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Pair<FeedSource, SourceTemplate?>?>(null) }
    var choosing by remember { mutableStateOf(false) }
    val settings = Graph.settings

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        sources.forEach { source ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp).clickable { if (!source.preset) editing = source to null }) {
                    Text(source.name + if (source.addressed) " · 发给我的" else "", color = Ink.Black, fontSize = 15.sp)
                    val state = when {
                        !source.enabled -> "已关闭"
                        source.lastError.isNotBlank() -> "出错：${source.lastError.take(50)}"
                        source.lastPolledAt == 0L -> "还没看过"
                        source.kind in SourceKind.PUSHED -> "已连接 · 累计收到 ${source.taken} 条"
                        else -> "上次 ${formatClock(source.lastPolledAt)} · 每 ${source.everyMin} 分钟 · 累计交给分流 ${source.taken} 条"
                    }
                    val bar = source.config[SourceConfig.FIT]?.let { " · 成卡门槛 $it" }.orEmpty()
                    Text("${SourceKind.label(source.kind)} · $state$bar", color = if (source.enabled && source.lastError.isNotBlank()) Ink.Red else Ink.Muted, fontSize = 12.sp)
                }
                if (!source.preset) Text("删除", color = Ink.Muted, fontSize = 12.sp, modifier = Modifier.padding(end = 10.dp).clickable { scope.launch { Graph.sources.remove(source.id); Graph.pushes.sync() } })
                Switch(checked = source.enabled, onCheckedChange = { on -> scope.launch { Graph.sources.setEnabled(source.id, on); Graph.pushes.sync() } })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("添加一个源", Ink.Black, filled = true) { choosing = true }
            Pill("现在都看一遍", Ink.Black) { scope.launch { runCatching { Graph.sources.pollNow() } } }
        }

        // Chinese platforms publish no feeds; with an instance of his own, their routes are one tap away.
        var base by remember { mutableStateOf(settings.rsshubBase) }
        OutlinedTextField(base, { base = it; settings.rsshubBase = it }, label = { Text("你自己的 RSSHub 地址（可不填）") }, placeholder = { Text("https://rsshub.example.com", color = Ink.Faint) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (settings.rsshubBase.startsWith("https://")) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SourceTemplates.RSSHUB_ROUTES.forEach { (name, route) ->
                Pill(name, Ink.Black) { editing = FeedSource(name = name, url = settings.rsshubBase + route, everyMin = 60) to null }
            }
        } else Text("微博、知乎、B 站这类平台自己不出订阅源，公共的 RSSHub 又拒绝匿名请求。填上你自己的实例地址，这里会出现它们的路由。", color = Ink.Muted, fontSize = 12.sp, lineHeight = 17.sp)
    }

    if (choosing) AlertDialog(
        onDismissRequest = { choosing = false }, containerColor = Color.White,
        title = { Text("接哪一种", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                (listOf(SourceTemplate("RSS 或 Atom 订阅源", "博客、新闻、播客、GitHub 项目的发版、arXiv。填订阅地址或网站首页都行。", FeedSource(name = "", url = "https://"), needs = "订阅地址")) + SourceTemplates.ALL).forEach { template ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.Bubble).clickable { choosing = false; editing = template.source to template }.padding(12.dp)) {
                        Text(template.title, color = Ink.Black, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(template.note, color = Ink.Muted, fontSize = 12.sp, lineHeight = 17.sp)
                        if (template.needs.isNotBlank()) Text("要你提供：${template.needs}", color = Ink.Amber, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {}, dismissButton = { Pill("取消", Ink.Muted) { choosing = false } },
    )

    editing?.let { (source, template) -> SourceForm(source, template, onDone = { editing = null }) }
}

@Composable
private fun SourceForm(initial: FeedSource, template: SourceTemplate?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.url) }
    var every by remember { mutableStateOf(initial.everyMin.toString()) }
    var addressed by remember { mutableStateOf(initial.addressed) }
    var secret by remember { mutableStateOf("") }
    val config = remember { mutableStateMapOf<String, String>().apply { putAll(initial.config) } }
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<List<String>>(emptyList()) }
    var problem by remember { mutableStateOf<String?>(null) }
    val existing = initial.id > 0
    val hadSecret = remember { existing && Graph.sources.secrets.has(Secrets.forSource(initial.id)) }

    fun built() = initial.copy(name = name.trim(), url = url.trim(), everyMin = every.toIntOrNull()?.coerceIn(5, 1_440) ?: 60, addressed = addressed, config = config.filterValues { it.isNotBlank() })
    fun secretToUse() = secret.ifBlank { if (existing) Graph.sources.secrets.get(Secrets.forSource(initial.id)).orEmpty() else "" }

    AlertDialog(
        onDismissRequest = { if (!working) onDone() }, containerColor = Color.White,
        title = { Text(if (existing) "改「${initial.name}」" else template?.title ?: "添加 ${SourceKind.label(initial.kind)}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                template?.note?.let { Text(it, color = Ink.Muted, fontSize = 12.sp, lineHeight = 17.sp) }
                OutlinedTextField(name, { name = it }, label = { Text("名字") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it; problem = null }, label = { Text(if (initial.kind == SourceKind.IMAP) "服务器，如 imaps://imap.qq.com:993" else "地址") }, modifier = Modifier.fillMaxWidth())
                if (initial.kind !in SourceKind.PUSHED) OutlinedTextField(every, { every = it.filter(Char::isDigit) }, label = { Text("每隔几分钟看一次") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                fieldsFor(initial.kind).forEach { (key, label, hint) ->
                    OutlinedTextField(config[key].orEmpty(), { config[key] = it }, label = { Text(label, fontSize = 12.sp) }, placeholder = { Text(hint, color = Ink.Faint, fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (needsSecret(initial.kind, config)) {
                    OutlinedTextField(
                        secret, { secret = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (initial.kind == SourceKind.IMAP) "授权码" else "令牌") },
                        placeholder = { Text(if (hadSecret) "已保存；留空就不改" else template?.secretHint.orEmpty(), color = Ink.Faint, fontSize = 12.sp) },
                    )
                    Text("只加密存在这台手机上，只发给上面这个地址，不进流水、不进导出、不发给模型。", color = Ink.Muted, fontSize = 12.sp)
                }
                if (initial.kind != SourceKind.IMAP && initial.kind !in SourceKind.PUSHED) Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("当作发给我的消息", color = Ink.Black, fontSize = 14.sp)
                        Text("开：按通知处理，要紧的会在聊天里说。关：按资讯处理，合适的进 Feed。", color = Ink.Muted, fontSize = 12.sp)
                    }
                    Switch(checked = addressed, onCheckedChange = { addressed = it })
                }
                problem?.let { Text(it, color = Ink.Red, fontSize = 12.sp, lineHeight = 17.sp) }
                result.forEach { Text("· $it", color = Ink.Body, fontSize = 12.sp, lineHeight = 17.sp) }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (working) "在读…" else "测试", Ink.Black) {
                    if (working) return@Pill
                    working = true; problem = null; result = emptyList()
                    scope.launch {
                        runCatching { Graph.sources.preview(built(), secretToUse()) }.fold(onSuccess = { result = it }, onFailure = { problem = it.message?.take(160) ?: "读不了" })
                        working = false
                    }
                }
                Pill("保存", Ink.Black, filled = true) {
                    if (working) return@Pill
                    working = true; problem = null
                    scope.launch {
                        val outcome = if (existing) runCatching {
                            Graph.sources.store.save(built().copy(lastError = ""))
                            if (secret.isNotBlank()) Graph.sources.secrets.put(Secrets.forSource(initial.id), secret)
                        } else if (initial.kind == SourceKind.RSS) Graph.sources.subscribe(url, name.ifBlank { null }) else Graph.sources.add(built(), secret)
                        outcome.fold(
                            onSuccess = { Graph.pushes.sync(); Graph.scope.launch { runCatching { Graph.sources.pollNow() } }; onDone() },
                            onFailure = { problem = it.message?.take(160) ?: "没存上" },
                        )
                        working = false
                    }
                }
            }
        },
        dismissButton = { Pill("取消", Ink.Muted) { if (!working) onDone() } },
    )
}
