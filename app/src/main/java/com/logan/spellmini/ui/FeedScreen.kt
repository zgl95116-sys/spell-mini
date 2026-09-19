package com.logan.spellmini.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.logan.spellmini.Graph
import com.logan.spellmini.actions.Actions
import com.logan.spellmini.data.FeedCard
import com.logan.spellmini.net.str
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
    appendLine(card.title)
    appendLine(card.body)
    strings(card.bulletsJson).forEach { appendLine("- $it") }
    links(card.sourcesJson).forEach { (title, url) -> appendLine("来源：$title $url") }
}.trim()

@Composable
fun FeedScreen(onDiscuss: (String) -> Unit) {
    val cards by Graph.db.feed().visible().collectAsState(initial = emptyList())
    // Opening the feed counts as a tick of the scheduler, so a stale feed refreshes without waiting for the timer.
    LaunchedEffect(Unit) { Graph.scope.launch { Graph.feed.refreshIfDue() } }
    Column(Modifier.fillMaxSize()) {
        RefreshHeader()
        if (cards.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(36.dp), contentAlignment = Alignment.Center) {
                Text(
                    "这里还是空的。\n\n我会每小时按你的画像找一批内容；通知里出现你可能感兴趣的东西时，也会查一查做成卡片。" +
                        "在「画像」里写下你的兴趣，推得才准。",
                    color = Ink.Muted, fontSize = 14.sp, lineHeight = 22.sp,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
                items(cards, key = { it.id }) { card ->
                    FeedCardView(card, onDiscuss = { onDiscuss(discussionContext(card)) })
                    HorizontalDivider(color = Ink.Line, modifier = Modifier.padding(start = 64.dp, end = 20.dp))
                }
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
            refreshing -> "正在按你的兴趣找内容…"
            !settings.interestFeedEnabled -> "兴趣巡查已关闭，只有通知会触发新卡"
            settings.lastInterestRunAt == 0L -> "还没按兴趣找过内容"
            else -> "上次巡查 ${formatClock(settings.lastInterestRunAt)}" + (lastSummary?.let { " · $it" } ?: " · 每 ${settings.interestIntervalMin} 分钟一次")
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(status, color = Ink.Muted, fontSize = 12.sp, maxLines = 2, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Pill(if (refreshing) "生成中" else "再来一批", Ink.Black) {
            if (!refreshing) Graph.scope.launch { runCatching { Graph.feed.refreshFromProfile(manual = true) } }
        }
    }
}

/** Site thumbnails are often tiny (120x75). Upscaled to a tile they look broken, so anything that small is dropped. */
@Composable
private fun CoverImage(url: String) {
    var usable by remember(url) { mutableStateOf(true) }
    if (!usable) return
    AsyncImage(
        model = url, contentDescription = null, contentScale = ContentScale.Crop,
        onSuccess = { state -> if (state.result.drawable.intrinsicWidth < MIN_COVER_PX) usable = false },
        onError = { usable = false },
        modifier = Modifier.size(width = 150.dp, height = 150.dp).clip(RoundedCornerShape(16.dp)).background(Ink.Bubble),
    )
}

private const val MIN_COVER_PX = 300

@Composable
private fun FeedCardView(card: FeedCard, onDiscuss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showInfo by rememberSaveable(card.id) { mutableStateOf(false) }
    val bullets = remember(card.bulletsJson) { strings(card.bulletsJson) }
    val images = remember(card.imagesJson) { strings(card.imagesJson) }
    val sources = remember(card.sourcesJson) { links(card.sourcesJson) }
    // Interest-patrol cards have no notification behind them, so there is nothing to jump back to.
    val fromNotification = card.eventId != null && !card.sourceLabel.startsWith("兴趣")

    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp)) {
        Text(card.emoji, fontSize = 26.sp, modifier = Modifier.width(44.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(card.title, color = Ink.Black, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
            Text(card.body, color = Ink.Body, fontSize = 15.sp, lineHeight = 23.sp)
            bullets.forEach { bullet ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 9.dp, end = 10.dp).size(6.dp).clip(CircleShape).background(Ink.Black))
                    Text(bullet, color = Ink.Body, fontSize = 15.sp, lineHeight = 22.sp)
                }
            }
            if (images.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    images.forEach { url -> CoverImage(url) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (card.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = "喜欢", tint = if (card.liked) Ink.Red else Ink.Black,
                    modifier = Modifier.size(24.dp).clickable { scope.launch { Graph.db.feed().setLiked(card.id, !card.liked) } },
                )
                Spacer(Modifier.width(28.dp))
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "讨论", tint = Ink.Black, modifier = Modifier.size(23.dp).clickable(onClick = onDiscuss))
                if (fromNotification) {
                    Spacer(Modifier.width(28.dp))
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "打开原内容", tint = Ink.Black,
                        modifier = Modifier.size(23.dp).clickable {
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
                Text(formatClock(card.createdAt), color = Ink.Faint, fontSize = 12.sp)
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Outlined.Info, contentDescription = "来源与原因", tint = Ink.Black, modifier = Modifier.size(23.dp).clickable { showInfo = !showInfo })
            }
            AnimatedVisibility(showInfo) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.Bubble).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (card.reason.isNotBlank()) Text("为什么推给你：${card.reason}", color = Ink.Body, fontSize = 13.sp, lineHeight = 19.sp)
                    if (card.sourceLabel.isNotBlank()) Text("起因：${card.sourceLabel}", color = Ink.Muted, fontSize = 12.sp)
                    sources.forEach { (title, url) ->
                        Text(
                            title.ifBlank { url }, color = Ink.Blue, fontSize = 13.sp, maxLines = 1,
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
        }
    }
}
