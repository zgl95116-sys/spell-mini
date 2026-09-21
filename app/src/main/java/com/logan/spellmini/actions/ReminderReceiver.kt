package com.logan.spellmini.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.logan.spellmini.Graph
import com.logan.spellmini.data.ChatMsg
import com.logan.spellmini.data.MsgRole
import com.logan.spellmini.notify.Notifier
import kotlinx.coroutines.launch

/**
 * Fires when a timed item comes due. A plain reminder speaks up in chat and buzzes. A scheduled task is handed to the
 * chat agent, which does the work (searching, summarising) and reports: that is what makes "周一 18:00 我整理一份发你" true.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getStringExtra(EXTRA_MOMENT) == com.logan.spellmini.signals.DeviceMoments.MEETING_TICK) {
            Graph.moments.onMeetingTick()
            return
        }
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0)
        if (taskId > 0) {
            Graph.scope.launch { Graph.tasks.run(taskId) }
            return
        }
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { return }
        val doIt = intent.getBooleanExtra(EXTRA_DO_IT, false)
        // The work can take a minute, far longer than a receiver may run. The process stays alive on its own (bound
        // notification listener plus a foreground service), so the receiver only hands over and returns.
        Graph.scope.launch {
            if (doIt) {
                Graph.chat.onScheduled(text)
            } else {
                Graph.db.messages().insert(ChatMsg(role = MsgRole.ASSISTANT, text = "⏰ 到点了：$text", createdAt = System.currentTimeMillis()))
                Notifier.proactive(context, title = "提醒", text = text, alert = true)
            }
        }
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_DO_IT = "do_it"
        const val EXTRA_TASK_ID = "task_id"

        /** A calendar-driven moment is due (see DeviceMoments.armMeetings). */
        const val EXTRA_MOMENT = "moment"
    }
}
