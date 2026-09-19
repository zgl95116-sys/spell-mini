package com.logan.spellmini.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.logan.spellmini.Graph
import com.logan.spellmini.agent.ChatAgent
import com.logan.spellmini.data.CardState
import com.logan.spellmini.data.ChatMsg
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
    LaunchedEffect(rowCount, streaming?.second?.length) {
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
        MsgKind.NOTE -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(message.text, color = Ink.Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
        }
        MsgKind.CARD -> ConfirmCard(message)
        else -> {
            val mine = message.role == MsgRole.USER
            // Older builds stored imitated "[确认卡…]" lines inside assistant text; never show them as if they were cards.
            val text = (liveText ?: message.text).let { if (mine) it else ChatAgent.stripInternal(it) }
            // An empty streaming placeholder is represented by the thinking bubble instead.
            if (text.isBlank()) return
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
            }
        }
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

@Composable
private fun ConfirmCard(message: ChatMsg) {
    val context = LocalContext.current
    val pending = message.cardState == CardState.PENDING
    Column(
        Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(20.dp)).border(1.dp, Ink.Line, RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("需要你确认", color = Ink.Muted, fontSize = 12.sp)
        // This line is generated from the exact arguments that will run, not from the model's prose.
        Text(message.text, color = Ink.Black, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (pending) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CardButton("同意", filled = true, Modifier.weight(1f)) { Graph.chat.resolveCard(context, message, approve = true) }
                CardButton("不用了", filled = false, Modifier.weight(1f)) { Graph.chat.resolveCard(context, message, approve = false) }
            }
        } else {
            val (label, color) = when (message.cardState) {
                CardState.APPROVED -> "已同意并执行" to Ink.Green
                CardState.DENIED -> "已拒绝" to Ink.Muted
                else -> "执行失败" to Ink.Red
            }
            Text(label, color = color, fontSize = 13.sp)
        }
    }
}

@Composable
private fun CardButton(label: String, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier.clip(shape).then(if (filled) Modifier.background(Ink.Black) else Modifier.border(1.dp, Ink.Faint, shape))
            .clickable(onClick = onClick).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (filled) Color.White else Ink.Black, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
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
