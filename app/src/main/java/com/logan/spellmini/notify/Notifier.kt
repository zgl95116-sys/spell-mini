package com.logan.spellmini.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.logan.spellmini.MainActivity
import com.logan.spellmini.R

/** How a proactive message reached the user; written onto the trace row so "判了 chat 却没提醒我" can be diagnosed. */
enum class Delivery(val label: String) {
    ALERT("已弹出提醒"),
    NORMAL("已发通知（响铃，不弹出）"),
    BLOCKED("系统里关了 Spell Mini 的通知，只能在 Chat 里看到"),
}

/** Our own notifications for proactive chat messages. The listener ignores this package, so they never loop back. */
object Notifier {
    // A channel's importance is frozen once created, so changing behaviour needs new ids. The first build used a
    // silent IMPORTANCE_LOW channel for almost everything, which looked like nothing had been sent.
    private const val ALERT = "proactive_alert_v2"
    private const val NORMAL = "proactive_normal_v2"
    private val RETIRED = listOf("proactive_loud", "proactive_quiet")

    /** [id] ties the notification to what it is about, so [cancel] can take it back once the user has dealt with that. */
    fun proactive(context: Context, title: String, text: String, alert: Boolean, id: Int = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()): Delivery {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return Delivery.BLOCKED
        val manager = context.getSystemService(NotificationManager::class.java)
        RETIRED.forEach(manager::deleteNotificationChannel)
        manager.createNotificationChannel(NotificationChannel(ALERT, "主动消息（要紧，弹出）", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(NORMAL, "主动消息（一般）", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_CHAT, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, if (alert) ALERT else NORMAL)
            .setSmallIcon(R.drawable.ic_stat_spell)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (alert) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify(id, notification)
        return if (alert) Delivery.ALERT else Delivery.NORMAL
    }

    fun cancel(context: Context, id: Int) = context.getSystemService(NotificationManager::class.java).cancel(id)

    /** Notification id for a proactive message about one event. Offset so it cannot collide with the keep-alive id. */
    fun idFor(eventId: Long): Int = 10_000 + (eventId % 1_000_000).toInt()
}
