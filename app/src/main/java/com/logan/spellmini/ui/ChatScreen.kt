package com.logan.spellmini.ui

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Mic
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import coil.compose.AsyncImage
import com.logan.spellmini.Graph
import com.logan.spellmini.actions.Actions
import com.logan.spellmini.agent.ChatAgent
import com.logan.spellmini.data.ActionChip
import com.logan.spellmini.data.Attachments
import com.logan.spellmini.data.CardState
import com.logan.spellmini.data.ChatMsg
import com.logan.spellmini.data.ChipState
import com.logan.spellmini.data.Handled
import com.logan.spellmini.data.LinkPreview
import com.logan.spellmini.net.obj
import com.logan.spellmini.net.str
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.logan.spellmini.data.MsgKind
import com.logan.spellmini.data.MsgRole

@Composable
fun ChatScreen(pendingContext: String?, onContextConsumed: () -> Unit) {
    val messages by Graph.db.messages().all().collectAsState(initial = emptyList())
    val activity by Graph.chat.activity.collectAsState()
    val streaming by Graph.chat.streaming.collectAsState()
    val listState = rememberLazyListState()

    // Proactive messages skip the system notification while the user is looking at the chat.
    LifecycleResumeEffect(Unit) {
        Graph.chatOnScreen.value = true
        onPauseOrDispose { Graph.chatOnScreen.value = false }
    }

    val rowCount = messages.size + if (activity != null) 1 else 0
    LaunchedEffect(rowCount, streaming?.second?.length, messages.lastOrNull()?.cardJson) {
        if (rowCount > 0) listState.animateScrollToItem(rowCount - 1)
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                val liveText = streaming?.takeIf { it.first == message.id }?.second
                MessageRow(message, liveText)
            }
            // The thinking bubble is always the last row, even if the user keeps typing while a turn runs.
            if (activity != null) item(key = "thinking") { ThinkingBubble(activity.orEmpty()) }
        }
        Composer(
            quoted = pendingContext,
            onClearQuoted = onContextConsumed,
            onSend = { text ->
                Graph.chat.send(text, pendingContext)
                onContextConsumed()
            },
        )
    }
}

@Composable
private fun MessageRow(message: ChatMsg, liveText: String?) {
    when (message.kind) {
        MsgKind.NOTE -> if (message.cardJson != null) ActionNote(message) else Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(message.text, color = Ink.Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
        }
        MsgKind.CARD -> LegacyCardLine(message)
        else -> {
            val mine = message.role == MsgRole.USER
            // Older builds stored imitated "[确认卡…]" lines inside assistant text; never show them as if they were cards.
            val text = (liveText ?: message.text).let { if (mine) it else ChatAgent.stripInternal(it) }
            // An empty streaming placeholder is represented by the thinking bubble instead.
            if (text.isBlank()) return
            val attachments = remember(message.cardJson) { Attachments.parse(message.cardJson) }
            // Proactive messages about a notification can always jump back to it.
            val fromNotification = !mine && message.eventId != null && message.sourceLabel != null
            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                message.sourceLabel?.let { label ->
                    Text(
                        "起因 · $label", color = Ink.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp, bottom = 3.dp).widthIn(max = 280.dp),
                    )
                }
                Box(
                    Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp))
                        .background(if (mine) Ink.Black else Ink.Bubble).padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(lightMarkdown(text), color = if (mine) Color.White else Ink.Black, fontSize = 15.sp, lineHeight = 22.sp)
                }
                if (!attachments?.links.isNullOrEmpty()) LinkRow(attachments!!.links)
                if (fromNotification || !attachments?.actions.isNullOrEmpty()) ChipColumn(message, attachments?.actions.orEmpty(), fromNotification)
                if (fromNotification) FeedbackRow(message, attachments)
            }
        }
    }
}

/**
 * Two words under every proactive message. "别再提这类" becomes a rule he can read and take back; both are kept as
 * labels, together with what he did about the notification on his own, which is shown here once it is known.
 */
@Composable
private fun FeedbackRow(message: ChatMsg, attachments: Attachments?) {
    val handled = attachments?.handled
    val feedback = attachments?.feedback
    Row(Modifier.padding(start = 6.dp, top = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (handled != null) Text("✓ ${Handled.label(handled)}", color = Ink.Green, fontSize = 11.sp)
        when (feedback) {
            "up" -> Text("已标：有用", color = Ink.Muted, fontSize = 11.sp)
            "down" -> Text("已标：别再提这类", color = Ink.Muted, fontSize = 11.sp)
            else -> {
                Text("有用", color = Ink.Muted, fontSize = 11.sp, modifier = Modifier.clickable { Graph.chat.feedback(message, useful = true) })
                Text("别再提这类", color = Ink.Muted, fontSize = 11.sp, modifier = Modifier.clickable { Graph.chat.feedback(message, useful = false) })
            }
        }
    }
}

/** An action that already ran: one quiet line, with a way back for the ones that can be taken back. */
@Composable
private fun ActionNote(message: ChatMsg) {
    val payload = remember(message.cardJson) { runCatching { Json.parseToJsonElement(message.cardJson.orEmpty()) as? JsonObject }.getOrNull() }
    val tool = payload?.str("tool")
    val due = remember(payload) { tool?.let { name -> payload?.obj("args")?.let { Actions.dueAt(name, it) } } }
    val undone = message.cardState == CardState.UNDONE
    // A timed item can be taken back until it fires; a rule learnt from feedback, at any time.
    val canUndo = !undone && ((tool in Actions.undoable && (due ?: 0) > System.currentTimeMillis()) || tool == ChatAgent.RULE_NOTE)
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (undone) "已撤销 · ${message.text}" else "✓ ${message.text}", color = Ink.Muted, fontSize = 12.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textDecoration = if (undone) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (canUndo) {
            Text("撤销", color = Ink.Blue, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 10.dp).clickable { Graph.chat.undo(message) })
        }
    }
}

/** Pages the reply drew on, as cards you can swipe through: a cover when the page has one, a play mark for video sites. */
@Composable
private fun LinkRow(links: List<LinkPreview>) {
    val context = LocalContext.current
    Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        links.forEach { link ->
            LinkCard(link) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Toast.makeText(context, "这个链接打不开", Toast.LENGTH_SHORT).show() }
            }
        }
    }
}

@Composable
private fun LinkCard(link: LinkPreview, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    // Site thumbnails are often tiny or broken; a card without a cover looks better than one with a smudge.
    var imageUsable by remember(link.image) { mutableStateOf(link.image != null) }
    val host = remember(link.url) { runCatching { Uri.parse(link.url).host }.getOrNull().orEmpty().removePrefix("www.") }
    Column(Modifier.width(212.dp).clip(shape).border(1.dp, Ink.Line, shape).clickable(onClick = onClick)) {
        if (imageUsable) {
            Box(contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = link.image, contentDescription = null, contentScale = ContentScale.Crop,
                    onSuccess = { state -> if (state.result.drawable.intrinsicWidth < MIN_LINK_IMAGE_PX) imageUsable = false },
                    onError = { imageUsable = false },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Ink.Bubble),
                )
                if (link.video) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "视频", tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(link.title.ifBlank { host }, color = Ink.Black, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((if (link.video && !imageUsable) "▶ " else "") + host, color = Ink.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private const val MIN_LINK_IMAGE_PX = 200

/** One-tap actions under a message. The label comes from the exact arguments that will run, not from the model's prose. */
@Composable
private fun ChipColumn(message: ChatMsg, chips: List<ActionChip>, fromNotification: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(top = 6.dp).widthIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEachIndexed { index, chip ->
            val mark = when (chip.state) { ChipState.DONE -> "✓ "; ChipState.FAILED -> "✕ "; else -> "" }
            if (chip.tool == Actions.REPLY) ReplyChip(chip, message.eventId) { Graph.chat.runChip(context, message, index) }
            else ChipButton(mark + chip.label) { Graph.chat.runChip(context, message, index) }
        }
        if (fromNotification) {
            ChipButton("查看原消息 ↗") {
                scope.launch {
                    val pkg = message.eventId?.let { Graph.db.events().get(it)?.pkg }
                    if (!Actions.openOriginal(context, message.eventId, pkg)) Toast.makeText(context, "原消息打不开了：来源 App 可能已卸载", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

/**
 * A drafted reply. Unlike the other buttons it shows its whole text: tapping it sends these exact words to someone
 * else, so nothing may be hidden behind an ellipsis. Once sent through the notification it cannot be tapped again.
 */
@Composable
private fun ReplyChip(chip: ActionChip, messageEventId: Long?, onClick: () -> Unit) {
    val eventId = chip.args.str("eventId")?.toLongOrNull() ?: messageEventId
    // Asked at display time: the quick reply only lives as long as this process and the notification do.
    val direct = remember(chip.state, eventId) { Actions.canReplyDirectly(eventId) }
    val sent = chip.state == ChipState.DONE
    val header = when {
        sent -> "✓ 已发给 ${chip.args.str("to").orEmpty()}"
        chip.state == ChipState.COPIED -> "✓ 已复制，去 ${chip.args.str("app").orEmpty()} 里粘贴发送（再点一次重新复制）"
        chip.state == ChipState.FAILED -> "✕ 没发出去，再点一次重试"
        direct -> "点一下，直接发给 ${chip.args.str("to").orEmpty()}"
        else -> "点一下，复制并打开 ${chip.args.str("app").orEmpty()}"
    }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.clip(shape).border(1.dp, if (sent) Ink.Line else Ink.Faint, shape).clickable(enabled = !sent, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(header, color = if (sent) Ink.Green else Ink.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(chip.args.str("text").orEmpty(), color = if (sent) Ink.Muted else Ink.Black, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun ChipButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.clip(shape).border(1.dp, Ink.Faint, shape).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)) {
        Text(label, color = Ink.Black, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Only bold is rendered; the prompt already discourages headings and tables, so nothing else needs a library. */
private fun lightMarkdown(source: String): AnnotatedString = buildAnnotatedString {
    var rest = source
    while (true) {
        val open = rest.indexOf("**")
        val close = if (open >= 0) rest.indexOf("**", open + 2) else -1
        if (open < 0 || close < 0) {
            append(rest)
            return@buildAnnotatedString
        }
        append(rest.substring(0, open))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(rest.substring(open + 2, close)) }
        rest = rest.substring(close + 2)
    }
}

/**
 * Confirm cards are gone: actions run directly or appear as buttons. Cards left in the chat by earlier builds are
 * shown as one quiet line, so an upgraded phone does not open onto a wall of stale "需要你确认" boxes.
 */
@Composable
private fun LegacyCardLine(message: ChatMsg) {
    val ran = message.cardState == CardState.APPROVED
    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
        Text(
            if (ran) "✓ ${message.text}" else "未执行 · ${message.text}", color = if (ran) Ink.Muted else Ink.Faint, fontSize = 12.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ThinkingBubble(label: String) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Ink.Bubble).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(3) { Box(Modifier.size(6.dp).clip(CircleShape).background(Ink.Faint)) }
        Spacer(Modifier.size(2.dp))
        Text(label, color = Ink.Muted, fontSize = 13.sp)
    }
}

@Composable
private fun Composer(quoted: String?, onClearQuoted: () -> Unit, onSend: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp, top = 6.dp)) {
        if (quoted != null) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(Ink.Bubble)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "讨论：" + quoted.lineSequence().firstOrNull().orEmpty(), color = Ink.Body, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Icon(Icons.Filled.Close, contentDescription = "取消引用", tint = Ink.Muted, modifier = Modifier.size(16.dp).clickable(onClick = onClearQuoted))
            }
        }
        Row(
            Modifier.fillMaxWidth().shadow(10.dp, RoundedCornerShape(28.dp), ambientColor = Color(0x22000000), spotColor = Color(0x22000000))
                .clip(RoundedCornerShape(28.dp)).background(Color.White).heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = Ink.Black, modifier = Modifier.size(22.dp))
            Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                if (draft.isEmpty()) Text("有事，跟我说…", color = Ink.Faint, fontSize = 15.sp)
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = TextStyle(color = Ink.Black, fontSize = 15.sp, lineHeight = 21.sp),
                    cursorBrush = SolidColor(Ink.Black),
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (draft.isBlank()) {
                Icon(Icons.Outlined.Mic, contentDescription = null, tint = Ink.Muted, modifier = Modifier.size(20.dp))
            } else {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Ink.Black).clickable {
                        onSend(draft.trim())
                        draft = ""
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.ArrowUpward, contentDescription = "发送", tint = Color.White, modifier = Modifier.size(18.dp)) }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}
