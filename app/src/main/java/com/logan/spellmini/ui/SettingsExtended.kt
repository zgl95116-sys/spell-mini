package com.logan.spellmini.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logan.spellmini.Graph
import com.logan.spellmini.data.Criteria
import kotlinx.coroutines.launch

/** Caps, model routing, the editable JEV criteria and the per-app switches. */
@Composable
fun ExtendedSettings() {
    val settings = Graph.settings
    val version by settings.version.collectAsState()
    val rules by Graph.db.appRules().all().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    key(version) {
        SectionTitle("节流与上限", Modifier.padding(top = 8.dp))
        Stepper("静默窗口", "同一条通知停止更新这么久后才送判，用来合并刷屏", "${settings.quietWindowMs / 1000} 秒",
            onMinus = { settings.quietWindowMs -= 1_000 }, onPlus = { settings.quietWindowMs += 1_000 })
        Stepper("最长等待", "再热闹的会话也不会攒超过这个时间", "${settings.maxWaitMs / 1000} 秒",
            onMinus = { settings.maxWaitMs -= 5_000 }, onPlus = { settings.maxWaitMs += 5_000 })
        Stepper("主动消息上限", "每小时最多主动开口几次", "${settings.chatPerHourCap} 条/小时",
            onMinus = { settings.chatPerHourCap -= 1 }, onPlus = { settings.chatPerHourCap += 1 })
        Stepper("「要紧」的门槛", "JEV 紧急度（0–3）达到这个值算要紧：通知会弹出；低于它只响铃不弹出", "%.1f".format(settings.alertUrgencyTenths / 10.0),
            onMinus = { settings.alertUrgencyTenths -= 5 }, onPlus = { settings.alertUrgencyTenths += 5 })
        ToggleRow("要紧的事必须开口", "打开：达到上面门槛的通知，主模型不能沉默。关闭：回到「一律有增量才开口」。", settings.mustSpeakWhenUrgent) { settings.mustSpeakWhenUrgent = it }
        Stepper("通知触发的 Feed 上限", "每 24 小时最多生成几张卡（每张约 \$0.008）", "${settings.feedPerDayCap} 张/天",
            onMinus = { settings.feedPerDayCap -= 5 }, onPlus = { settings.feedPerDayCap += 5 })

        SectionTitle("兴趣巡查（不靠通知，按画像主动找内容）", Modifier.padding(top = 8.dp))
        ToggleRow("定时按兴趣生成 Feed", "0 点到 7 点不跑。每张卡约 \$0.01，每一次尝试都会记进通知流水。", settings.interestFeedEnabled) { settings.interestFeedEnabled = it }
        Stepper("多久一批", "到点后在后台跑；打开 Feed 时到点了也会跑", "${settings.interestIntervalMin} 分钟",
            onMinus = { settings.interestIntervalMin -= 15 }, onPlus = { settings.interestIntervalMin += 15 })
        Stepper("每批几个选题", "模型可以少给，没有好选题就一个都不给", "${settings.interestBatchSize} 个",
            onMinus = { settings.interestBatchSize -= 1 }, onPlus = { settings.interestBatchSize += 1 })
        Stepper("每天最多几张", "只算按兴趣生成的，和通知触发的上限分开", "${settings.interestPerDayCap} 张",
            onMinus = { settings.interestPerDayCap -= 10 }, onPlus = { settings.interestPerDayCap += 10 })
    }

    ModelFields()
    CriteriaEditor()

    SectionTitle("逐个 App 开关（默认全开）", Modifier.padding(top = 8.dp))
    if (rules.isEmpty()) Text("收到通知后，来源 App 会出现在这里。", color = Ink.Muted, fontSize = 12.sp)
    rules.forEach { rule ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rule.appName, color = Ink.Black, fontSize = 14.sp)
                Text("${rule.count} 条 · ${rule.pkg}", color = Ink.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Switch(checked = rule.enabled, onCheckedChange = { on -> scope.launch { Graph.db.appRules().setEnabled(rule.pkg, on) } })
        }
    }

    SectionTitle("数据", Modifier.padding(top = 8.dp))
    Pill("清空聊天记录", Ink.Red) { scope.launch { Graph.db.messages().clear() } }
}

@Composable
private fun Stepper(title: String, detail: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink.Black, fontSize = 15.sp)
            Text(detail, color = Ink.Muted, fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("－", Ink.Black, onClick = onMinus)
            Text(value, color = Ink.Black, fontSize = 13.sp)
            Pill("＋", Ink.Black, onClick = onPlus)
        }
    }
}

@Composable
private fun ModelFields() {
    val settings = Graph.settings
    var jev by remember { mutableStateOf(settings.jevModel) }
    var chat by remember { mutableStateOf(settings.chatModel) }
    SectionTitle("模型", Modifier.padding(top = 8.dp))
    OutlinedTextField(jev, { jev = it }, label = { Text("JEV 模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(chat, { chat = it }, label = { Text("Chat / Feed 模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (jev != settings.jevModel || chat != settings.chatModel) {
        Pill("保存模型设置", Ink.Black, filled = true) { settings.jevModel = jev; settings.chatModel = chat; jev = jev.trim(); chat = chat.trim() }
    }
}

@Composable
private fun CriteriaEditor() {
    val settings = Graph.settings
    var draft by remember { mutableStateOf(settings.criteria) }
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("JEV 判据（版本 ${settings.criteria.version}）", Modifier.weight(1f))
        Pill(if (expanded) "收起" else "编辑", Ink.Black) { expanded = !expanded }
    }
    Text("判据用英文效果最好。改动后每条流水会记录新的版本号，方便对比前后判定。", color = Ink.Muted, fontSize = 12.sp)
    if (!expanded) return
    CriteriaField("总问题", draft.instructions) { draft = draft.copy(instructions = it) }
    CriteriaField("chat", draft.chat) { draft = draft.copy(chat = it) }
    CriteriaField("feed", draft.feed) { draft = draft.copy(feed = it) }
    CriteriaField("ignore", draft.ignore) { draft = draft.copy(ignore = it) }
    CriteriaField("review", draft.review) { draft = draft.copy(review = it) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (draft != settings.criteria) Pill("保存判据", Ink.Black, filled = true) { settings.criteria = draft }
        Pill("恢复默认", Ink.Black) { settings.resetCriteria(); draft = Criteria.DEFAULT }
    }
}

@Composable
private fun CriteriaField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, minLines = 2, modifier = Modifier.fillMaxWidth())
}
