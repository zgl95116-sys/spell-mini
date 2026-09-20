package com.logan.spellmini.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logan.spellmini.data.EventStatus
import com.logan.spellmini.data.NotifEvent
import com.logan.spellmini.data.Route
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun formatClock(time: Long): String {
    val then = Calendar.getInstance().apply { timeInMillis = time }
    val now = Calendar.getInstance()
    val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    return SimpleDateFormat(if (sameDay) "HH:mm" else "M月d日 HH:mm", Locale.CHINA).format(Date(time))
}

fun formatUsd(value: Double): String = if (value < 0.01) "$" + "%.5f".format(value) else "$" + "%.3f".format(value)

/** Label and colour for the verdict chip on a trace row. */
fun NotifEvent.verdictLabel(): Pair<String, Color> = when {
    status == EventStatus.FILTERED -> "本地过滤" to Ink.Faint
    status == EventStatus.APP_OFF -> "App 已关" to Ink.Faint
    status == EventStatus.QUEUED -> "等待中" to Ink.Muted
    status == EventStatus.TASK -> "在办" to Ink.Blue
    status == EventStatus.ERROR && finalRoute == null -> "出错" to Ink.Red
    else -> when (finalRoute ?: route) {
        Route.CHAT -> "chat" to Ink.Black
        Route.FEED -> "feed" to Ink.Blue
        Route.IGNORE -> "ignore" to Ink.Muted
        Route.REVIEW -> "review" to Ink.Amber
        else -> "?" to Ink.Muted
    }
}

@Composable
fun Pill(text: String, color: Color, filled: Boolean = false, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    var modifier = Modifier.clip(shape)
    modifier = if (filled) modifier.background(color) else modifier.border(1.dp, color.copy(alpha = 0.55f), shape)
    if (onClick != null) modifier = modifier.clickable(onClick = onClick)
    Box(modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, color = if (filled) Color.White else color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = Ink.Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}
