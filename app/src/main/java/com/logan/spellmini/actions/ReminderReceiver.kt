package com.logan.spellmini.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.logan.spellmini.Graph
import com.logan.spellmini.data.ChatMsg
import com.logan.spellmini.data.MsgRole
import com.logan.spellmini.notify.Notifier
import kotlinx.coroutines.launch

/** Fires when a reminder the user approved comes due: speak up in chat and buzz. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { return }
        val pending = goAsync()
        Graph.scope.launch {
            try {
                val message = "⏰ 到点了：$text"
                Graph.db.messages().insert(ChatMsg(role = MsgRole.ASSISTANT, text = message, createdAt = System.currentTimeMillis()))
                Notifier.proactive(context, title = "提醒", text = text, alert = true)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_TEXT = "text"
    }
}
