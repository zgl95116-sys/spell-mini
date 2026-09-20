package com.logan.spellmini.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logan.spellmini.Graph
import com.logan.spellmini.data.Repeat
import com.logan.spellmini.data.Task
import com.logan.spellmini.data.TaskKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the assistant keeps doing without being asked again: what, when next, what came of it last time. */
@Composable
fun TasksTab() {
    val changes by Graph.tasks.changes.collectAsState()
    val version by Graph.settings.version.collectAsState()
    var tasks by remember { mutableStateOf<List<Task>>(emptyList()) }
    var spent by remember { mutableStateOf(0.0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(changes) {
        tasks = withContext(Dispatchers.IO) { Graph.tasks.store.all().sortedWith(compareBy({ it.paused }, { it.nextAt })) }
        spent = withContext(Dispatchers.IO) { Graph.tasks.spentToday() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "在聊天里说「每天 8 点给我简报」「盯着某某项目的新版本，有了告诉我」「帮我做个国庆行程」，事项就会出现在这里。盯着的事只有条件满足或有实质变化才会找你，每次检查都记在通知流水里。",
            color = Ink.Muted, fontSize = 13.sp, lineHeight = 20.sp,
        )
        key(version) {
            Text("今天后台已花 ${formatUsd(spent)}，每天上限 ${formatUsd(Graph.settings.autoBudgetCents / 100.0)}（设置里可调）", color = Ink.Muted, fontSize = 12.sp)
        }
        if (tasks.isEmpty()) Text("现在没有在办的事。", color = Ink.Body, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
        tasks.forEach { task ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink.Bubble).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val kind = when (task.kind) { TaskKind.WATCH -> "盯着"; TaskKind.LOOP -> "等下文"; else -> "定期" }
                Text("$kind · ${cadence(task)}" + if (task.origin == "auto") " · 我从通知里记下的" else "", color = Ink.Muted, fontSize = 12.sp)
                Text(task.title, color = Ink.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(task.instruction, color = Ink.Body, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (task.condition.isNotBlank()) Text("告诉你的条件：${task.condition}", color = Ink.Body, fontSize = 13.sp, lineHeight = 19.sp)
                if (task.feed.isNotBlank()) Text("来源：${task.feed}", color = Ink.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (task.paused) "已暂停" else "下次 ${formatClock(task.nextAt)}") + (if (task.lastRunAt > 0) " · 上次 ${formatClock(task.lastRunAt)}：${task.lastResult.ifBlank { "—" }}" else " · 还没跑过"),
                    color = Ink.Muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(if (task.paused) "恢复" else "暂停", Ink.Black) { scope.launch { Graph.tasks.setPaused(task.id, !task.paused) } }
                    Pill("现在跑一次", Ink.Blue) { Graph.scope.launch { Graph.tasks.run(task.id, manual = true) } }
                    Pill("删除", Ink.Red) { scope.launch { Graph.tasks.delete(task.id) } }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

private fun cadence(task: Task): String = when (task.repeat) {
    Repeat.DAILY -> "每天 ${task.at}"
    Repeat.WEEKDAYS -> "工作日 ${task.at}"
    Repeat.WEEKLY -> "每周${"一二三四五六日"[task.weekday.coerceIn(1, 7) - 1]} ${task.at}"
    Repeat.HOURS -> "每 ${task.everyHours} 小时"
    else -> "到点一次"
}
