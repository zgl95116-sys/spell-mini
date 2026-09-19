package com.logan.spellmini.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.logan.spellmini.MainActivity
import com.logan.spellmini.R

/**
 * A resident foreground service. The notification listener is already bound by the system; this only lowers the odds
 * that an aggressive OEM battery manager kills the process between notifications.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "后台常驻", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_spell)
            .setContentTitle("Spell Mini 正在留意你的通知")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type) }
            .onFailure { Log.w(TAG, "startForeground rejected", it); stopSelf() }
        return START_STICKY
    }

    companion object {
        private const val TAG = "SpellKeepAlive"
        private const val CHANNEL = "keepalive"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            // Background starts can be refused on Android 12+; the listener keeps working without this service.
            runCatching { context.startForegroundService(Intent(context, KeepAliveService::class.java)) }
                .onFailure { Log.w(TAG, "could not start keep-alive service", it) }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) KeepAliveService.start(context)
    }
}
