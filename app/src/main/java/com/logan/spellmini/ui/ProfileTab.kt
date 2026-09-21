package com.logan.spellmini.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logan.spellmini.Graph
import kotlinx.coroutines.launch

@Composable
fun ProfileTab() {
    val settings = Graph.settings
    val version by settings.version.collectAsState()
    // Tasks live in the same table as JSON; they have their own page and are no business of the profile.
    val allEntries by Graph.db.memory().all().collectAsState(initial = emptyList())
    val entries = allEntries.filter { it.source != com.logan.spellmini.data.MemorySource.TASK && it.source != com.logan.spellmini.data.MemorySource.SOURCE }
    val logs by Graph.db.memory().logs().collectAsState(initial = emptyList())
    val running by Graph.profile.running.collectAsState()
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable { mutableStateOf(settings.userProfile) }
    var lastResult by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionTitle("我写的（只有你能改，模型不会动）")
        OutlinedTextField(
            value = draft, onValueChange = { draft = it }, minLines = 4, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("你是谁、家里有谁、最近在忙什么、对什么感兴趣、什么不想被打扰。写得越具体，判断越准。", color = Ink.Faint, fontSize = 14.sp) },
        )
        if (draft != settings.userProfile) Pill("保存", Ink.Black, filled = true) { settings.userProfile = draft.trim(); draft = draft.trim() }

        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("模型记的（${entries.size} 条）", Modifier.weight(1f))
            Pill(if (running) "更新中…" else "让模型更新", Ink.Blue) {
                if (!running) scope.launch {
                    lastResult = runCatching { Graph.profile.run("手动") }.getOrElse { "失败：${it.message?.take(100)}" }
                }
            }
        }
        lastResult?.let { Text("本次结果：$it", color = Ink.Muted, fontSize = 12.sp) }
        key(version) {
            ToggleRow("后台自动更新", "每新增约 50 条通知，或隔 6 小时且有新通知时，自动整理一次。", settings.autoProfile) { settings.autoProfile = it }
        }
        if (entries.isEmpty()) {
            Text("还没有。聊天里告诉我「记住……」，或点上面的「让模型更新」从最近的通知里归纳。", color = Ink.Muted, fontSize = 13.sp)
        }
        entries.forEach { entry ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.Bubble).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(entry.text, color = Ink.Black, fontSize = 14.sp)
                    Text("${entry.source} · ${formatClock(entry.updatedAt)}", color = Ink.Muted, fontSize = 11.sp)
                }
                Icon(
                    Icons.Filled.Close, contentDescription = "删除", tint = Ink.Muted,
                    modifier = Modifier.size(18.dp).clickable { scope.launch { Graph.db.memory().delete(entry.id) } },
                )
            }
        }

        if (logs.isNotEmpty()) SectionTitle("更新记录", Modifier.padding(top = 10.dp))
        logs.forEach { log ->
            Text("${formatClock(log.time)}  ${log.summary}", color = Ink.Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.height(32.dp))
    }
}
