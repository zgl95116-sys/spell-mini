package com.logan.spellmini.notify

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.logan.spellmini.Graph

/** Plain snapshot of a posted notification, detached from framework objects. */
data class RawNotification(
    val key: String,
    val pkg: String,
    val appName: String,
    val title: String,
    val text: String,
    val category: String?,
    val postedAt: Long,
    val ongoing: Boolean,
    val groupSummary: Boolean,
    val synthetic: Boolean = false,
)

class SpellListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        Log.i(TAG, "listener connected")
        Graph.listenerConnected.value = true
        KeepAliveService.start(this)
        // "首次连接来源": judge what is already sitting in the shade once, so the first open is not empty.
        if (!Graph.settings.initialSweepDone) {
            Graph.settings.initialSweepDone = true
            val active = runCatching { activeNotifications }.getOrNull().orEmpty()
            Log.i(TAG, "initial sweep over ${active.size} active notifications")
            active.sortedBy { it.postTime }.takeLast(INITIAL_SWEEP_LIMIT).forEach(::handle)
        }
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "listener disconnected")
        Graph.listenerConnected.value = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn)

    private fun handle(sbn: StatusBarNotification) {
        // Never react to our own notifications, otherwise proactive messages would trigger themselves.
        if (sbn.packageName == packageName) return
        val raw = runCatching { extract(sbn) }
            .onFailure { Log.w(TAG, "extract failed for ${sbn.packageName}", it) }
            .getOrNull() ?: return
        Graph.pipeline.ingest(raw, sbn.notification.contentIntent)
    }

    private fun extract(sbn: StatusBarNotification): RawNotification {
        val n = sbn.notification
        val extras = n.extras
        val title = listOf(
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG),
            extras.getCharSequence(Notification.EXTRA_TITLE),
        ).firstOrNull { !it.isNullOrBlank() }?.toString().orEmpty()

        // MessagingStyle carries the recent turns of a conversation, which is far more useful than the one-line text.
        val messaging = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }
            .getOrNull()?.messages.orEmpty().takeLast(MAX_MESSAGES)
            .mapNotNull { m ->
                val body = m.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val sender = m.person?.name?.toString()
                if (sender.isNullOrBlank()) body else "$sender: $body"
            }
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val plain = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n").orEmpty()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val body = when {
            messaging.isNotEmpty() -> messaging.joinToString("\n")
            bigText.length > plain.length -> bigText
            plain.isNotBlank() -> plain
            else -> lines
        }
        val text = listOf(body, sub).filter { it.isNotBlank() }.joinToString("\n").take(MAX_TEXT)

        return RawNotification(
            key = sbn.key,
            pkg = sbn.packageName,
            appName = appLabel(sbn.packageName),
            title = title.take(200),
            text = text,
            category = n.category,
            postedAt = sbn.postTime,
            ongoing = sbn.isOngoing || (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0,
            groupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
        )
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        private const val TAG = "SpellListener"
        private const val MAX_TEXT = 2_000
        private const val MAX_MESSAGES = 6
        private const val INITIAL_SWEEP_LIMIT = 30

        /** Content intents of recent notifications, so "打开原通知" can jump to the exact source screen. */
        val contentIntents = object : LinkedHashMap<Long, PendingIntent>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, PendingIntent>?) = size > 200
        }
    }
}
