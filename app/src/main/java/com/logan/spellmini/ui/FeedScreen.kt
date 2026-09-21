package com.logan.spellmini.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.logan.spellmini.Graph
import com.logan.spellmini.actions.Actions
import com.logan.spellmini.agent.FeedAgent
import com.logan.spellmini.agent.JobAgent
import com.logan.spellmini.data.FeedCard
import com.logan.spellmini.data.FeedSource
import com.logan.spellmini.data.MemorySource
import com.logan.spellmini.net.str
import com.logan.spellmini.sources.Subscriptions
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private fun strings(jsonText: String): List<String> = runCatching {
    (Json.parseToJsonElement(jsonText) as JsonArray).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}.getOrDefault(emptyList())

private fun links(jsonText: String): List<Pair<String, String>> = runCatching {
    (Json.parseToJsonElement(jsonText) as JsonArray).mapNotNull { it as? JsonObject }
        .mapNotNull { item -> item.str("url")?.let { (item.str("title") ?: it) to it } }
}.getOrDefault(emptyList())

/** What "讨论" carries into the chat: enough for the model to talk about the card without re-searching. */
private fun discussionContext(card: FeedCard): String = buildString {
    appendLine(if (card.sourceLabel.startsWith(JobAgent.DOC_LABEL)) "成品 #${card.id}《${card.title}》" else card.title)
    appendLine(card.body.take(1_500))
    strings(card.bulletsJson).forEach { appendLine("- $it") }
    links(card.sourcesJson).forEach { (title, url) -> appendLine("来源：$title $url") }
}.trim()

@Composable
fun FeedScreen(onDiscuss: (String) -> Unit) {
    val cards by Graph.db.feed().visible().collectAsState(initial = emptyList())
    // Opening the feed counts as a tick of the scheduler, so a stale feed refreshes without waiting for the timer.
    LaunchedEffect(Unit) { Graph.scope.launch { Graph.feed.refreshIfDue() } }
    Column(Modifier.fillMaxSize().background(Ink.Bubble)) {
        RefreshHeader()
        FollowRow()
        SourceRow()
        if (cards.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(36.dp), contentAlignment = Alignment.Center) {
                Text(
                    "这里还是空的。\n\n我会每小时按你最近在关注的事和画像找一批内容；通知里出现你可能感兴趣的东西时，也会查一查做成卡片。" +
                        "想持续跟进某个主题，在聊天里跟我说「帮我关注……」。",
                    color = Ink.Muted, fontSize = 14.sp, lineHeight = 22.sp,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
                items(cards, key = { it.id }) { card -> FeedCardView(card, onDiscuss = { onDiscuss(discussionContext(card)) }) }
            }
        }
    }
}

/** Shows when the interest patrol last ran and lets the user ask for a batch right now. */
@Composable
private fun RefreshHeader() {
    val settings = Graph.settings
    val version by settings.version.collectAsState()
    val refreshing by Graph.feed.refreshing.collectAsState()
    val lastSummary by Graph.feed.lastSummary.collectAsState()
    // Keyed on `version` so the line updates when the scheduler stamps a new run time or a setting changes.
    val status = remember(version, refreshing, lastSummary) {
        when {
            refreshing -> "正在找新内容…"
            !settings.interestFeedEnabled -> "兴趣巡查已关闭，只有通知会触发新卡"
            settings.lastInterestRunAt == 0L -> lastSummary ?: "还没按兴趣找过内容"
            else -> "上次巡查 ${formatClock(settings.lastInterestRunAt)}" + (lastSummary?.let { " · $it" } ?: " · 每 ${settings.interestIntervalMin} 分钟一次")
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(status, color = Ink.Muted, fontSize = 12.sp, maxLines = 2, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Pill(if (refreshing) "生成中" else "再来一批", Ink.Black) {
            if (!refreshing) Graph.scope.launch { runCatching { Graph.feed.refreshFromProfile(manual = true) } }
        }
    }
}

/** Topics the user asked the assistant to keep following. Added from the chat, removed here or from the chat. */
@Composable
private fun FollowRow() {
    val follows by Graph.db.memory().watchBySource(MemorySource.FOLLOW).collectAsState(initial = emptyList())
    if (follows.isEmpty()) return
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("在关注", color = Ink.Muted, fontSize = 12.sp)
        follows.forEach { follow ->
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color.White).padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(follow.text, color = Ink.Black, fontSize = 12.sp, maxLines = 1)
                Icon(
                    Icons.Filled.Close, contentDescription = "取消关注 ${follow.text}", tint = Ink.Muted,
                    modifier = Modifier.padding(start = 4.dp).size(14.dp).clickable { scope.launch { Graph.db.memory().delete(follow.id) } },
                )
            }
        }
    }
}

/**
 * Feeds and public lists whose every update is triaged like a notification. Tap a name to switch it on or off; the
 * cross removes one the user added himself. New ones come from here or from the chat ("订阅……").
 */
@Composable
private fun SourceRow() {
    val entries by Graph.db.memory().watchBySource(MemorySource.SOURCE).collectAsState(initial = emptyList())
    val sources = remember(entries) { entries.mapNotNull(FeedSource::parse).sortedWith(compareBy({ !it.enabled }, { it.createdAt })) }
    val polling by Graph.sources.polling.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(if (polling) "订阅 · 检查中" else "订阅", color = Ink.Muted, fontSize = 12.sp)
        sources.forEach { source ->
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(if (source.enabled) Color.White else Color.Transparent)
                    .border(1.dp, if (source.enabled) Color.Transparent else Ink.Line, RoundedCornerShape(50))
                    .clickable { scope.launch { Graph.sources.setEnabled(source.id, !source.enabled) } }
                    .padding(start = 10.dp, end = if (source.preset) 10.dp else 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    source.name + if (source.enabled && source.lastError.isNotBlank()) " ⚠" else "",
                    color = if (source.enabled) Ink.Black else Ink.Faint, fontSize = 12.sp, maxLines = 1,
                )
                if (!source.preset) Icon(
                    Icons.Filled.Close, contentDescription = "取消订阅 ${source.name}", tint = Ink.Muted,
                    modifier = Modifier.padding(start = 4.dp).size(14.dp).clickable { scope.launch { Graph.sources.remove(source.id) } },
                )
            }
        }
        Icon(
            Icons.Filled.Add, contentDescription = "添加订阅源", tint = Ink.Black,
            modifier = Modifier.clip(CircleShape).background(Color.White).clickable { adding = true }.padding(4.dp).size(16.dp),
        )
    }
    if (adding) {
        var address by remember { mutableStateOf("") }
        var working by remember { mutableStateOf(false) }
        var problem by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!working) adding = false },
            title = { Text("添加订阅源", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("填 RSS 或 Atom 地址，或者网站首页（会自动找它的订阅源）。它的每条更新都会过一遍分流：和你有关的进 Feed，直接影响你手头的事才在聊天里说。", color = Ink.Body, fontSize = 13.sp, lineHeight = 19.sp)
                    OutlinedTextField(value = address, onValueChange = { address = it; problem = null }, singleLine = true, placeholder = { Text("https://", color = Ink.Faint) }, modifier = Modifier.fillMaxWidth())
                    problem?.let { Text(it, color = Ink.Red, fontSize = 12.sp, lineHeight = 17.sp) }
                }
            },
            confirmButton = {
                Pill(if (working) "在找…" else "订阅", Ink.Black, filled = true) {
                    if (working || address.isBlank()) return@Pill
                    working = true
                    scope.launch {
                        Graph.sources.subscribe(address).fold(
                            onSuccess = { source ->
                                adding = false
                                Toast.makeText(context, "已订阅「${source.name}」，先看第一眼", Toast.LENGTH_SHORT).show()
                                runCatching { Graph.sources.pollNow() }
                            },
                            onFailure = { problem = it.message?.take(120) ?: "没订阅成" },
                        )
                        working = false
                    }
                }
            },
            dismissButton = { Pill("取消", Ink.Muted) { if (!working) adding = false } },
            containerColor = Color.White,
        )
    }
}

/** Full-width cover. Site thumbnails are often tiny (120x75); upscaled they look broken, so anything that small is dropped. */
@Composable
private fun Cover(url: String) {
    var usable by remember(url) { mutableStateOf(true) }
    if (!usable) return
    AsyncImage(
        model = url, contentDescription = null, contentScale = ContentScale.Crop,
        onSuccess = { state -> if (state.result.drawable.intrinsicWidth < MIN_COVER_PX) usable = false },
        onError = { usable = false },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Ink.Bubble),
    )
}

private const val MIN_COVER_PX = 300

/** Cards written before the text limits came in can run to 400 characters; those start folded. */
private const val LONG_BODY = 130

@Composable
private fun FeedCardView(card: FeedCard, onDiscuss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable(card.id) { mutableStateOf(false) }
    val bullets = remember(card.bulletsJson) { strings(card.bulletsJson) }
    val images = remember(card.imagesJson) { strings(card.imagesJson) }
    val sources = remember(card.sourcesJson) { links(card.sourcesJson) }
    // Interest-patrol cards have no notification behind them, so there is nothing to jump back to.
    val fromSubscription = Subscriptions.isItemLabel(card.sourceLabel)
    val fromNotification = card.eventId != null && !fromSubscription && !FeedAgent.isPatrolLabel(card.sourceLabel)
    // A finished piece of work keeps its Markdown in `body`; the list shows its conclusion and a way in.
    val isDoc = card.sourceLabel.startsWith(JobAgent.DOC_LABEL)
    val long = card.body.length > LONG_BODY
    val shape = RoundedCornerShape(22.dp)

    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth().clip(shape).background(Color.White).border(1.dp, Ink.Line, shape)) {
        images.firstOrNull()?.let { Cover(it) }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${card.emoji} ${card.sourceLabel}".trim(), color = Ink.Muted, fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Text(formatClock(card.createdAt), color = Ink.Faint, fontSize = 12.sp)
            }
            Text(card.title, color = Ink.Black, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (isDoc) {
                Text(card.reason, color = Ink.Body, fontSize = 14.5.sp, lineHeight = 22.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Pill("打开全文", Ink.Black, filled = true) { Graph.openDoc.value = card.id }
            } else {
                Text(
                    card.body, color = Ink.Body, fontSize = 14.5.sp, lineHeight = 22.sp, overflow = TextOverflow.Ellipsis,
                    maxLines = if (expanded) Int.MAX_VALUE else 3, modifier = Modifier.clickable { expanded = !expanded },
                )
            }
            if (!isDoc && (expanded || !long)) {
                bullets.forEach { bullet ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 8.dp, end = 9.dp).size(5.dp).clip(CircleShape).background(Ink.Muted))
                        Text(bullet, color = Ink.Body, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
            }
            AnimatedVisibility(expanded) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.Bubble).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (card.reason.isNotBlank()) Text("为什么推给你：${card.reason}", color = Ink.Body, fontSize = 13.sp, lineHeight = 19.sp)
                    sources.forEach { (title, url) ->
                        Text(
                            title.ifBlank { url }, color = Ink.Blue, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            },
                        )
                    }
                    Text(
                        "不感兴趣，移除这张", color = Ink.Muted, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp).clickable { scope.launch { Graph.db.feed().dismiss(card.id) } },
                    )
                }
            }
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (card.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = "喜欢", tint = if (card.liked) Ink.Red else Ink.Black,
                    modifier = Modifier.size(22.dp).clickable { scope.launch { Graph.db.feed().setLiked(card.id, !card.liked) } },
                )
                Spacer(Modifier.width(26.dp))
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "讨论", tint = Ink.Black, modifier = Modifier.size(21.dp).clickable(onClick = onDiscuss))
                if (fromSubscription && sources.isNotEmpty()) {
                    Spacer(Modifier.width(26.dp))
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "阅读原文", tint = Ink.Black,
                        modifier = Modifier.size(21.dp).clickable {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sources.first().second)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        },
                    )
                }
                if (fromNotification) {
                    Spacer(Modifier.width(26.dp))
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "打开原内容", tint = Ink.Black,
                        modifier = Modifier.size(21.dp).clickable {
                            scope.launch {
                                val pkg = card.eventId?.let { Graph.db.events().get(it)?.pkg }
                                if (!Actions.openOriginal(context, card.eventId, pkg)) {
                                    Toast.makeText(context, "原内容打不开了：来源 App 可能已卸载", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (expanded) "收起" else "来源与原因",
                    tint = Ink.Muted, modifier = Modifier.size(24.dp).clickable { expanded = !expanded },
                )
            }
        }
    }
}
