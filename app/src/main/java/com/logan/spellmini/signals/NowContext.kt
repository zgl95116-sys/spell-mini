package com.logan.spellmini.signals

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import androidx.core.content.ContextCompat
import com.logan.spellmini.data.AppDb
import com.logan.spellmini.data.EventStatus
import com.logan.spellmini.data.Handled
import com.logan.spellmini.data.NotifEvent
import com.logan.spellmini.data.Outcome
import com.logan.spellmini.data.Settings
import com.logan.spellmini.notify.SpellListenerService
import com.logan.spellmini.sources.CalendarSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One worked-out fact about the present: the English goes to JEV, the Chinese is what the signals page shows. */
data class Fact(val signal: String, val key: String, val english: String, val chinese: String)

/**
 * What the phone knows about this moment, as sentences rather than readings. JEV is weak at arithmetic with times and
 * has no idea what a ringer mode of 1 means, so everything is worked out here: "in a calendar event now, 25 minutes
 * left", not two timestamps. Only signals that are switched on and allowed contribute.
 */
class NowContext(private val context: Context, private val db: AppDb, private val settings: Settings) {
    private fun on(id: String) = SignalCatalog.find(id)?.let { settings.signalOn(it.id, it.defaultOn) } == true
    private val clock = SimpleDateFormat("HH:mm", Locale.CHINA)

    suspend fun facts(): List<Fact> = buildList {
        val now = System.currentTimeMillis()
        if (on(SignalCatalog.DOING)) SpellListenerService.activities().distinctBy { it.kind }.forEach { activity ->
            val app = activity.app.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            add(Fact(SignalCatalog.DOING, "doing", activity.kind.english + app, activity.kind.chinese + activity.app.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()))
        }
        if (on(SignalCatalog.CALENDAR_NOW) && CalendarSource.allowed(context)) {
            val events = CalendarSource.between(context, now - 4 * HOUR_MS, now + 3 * HOUR_MS, limit = 12).filter { !it.allDay }
            events.firstOrNull { it.begin <= now && it.end > now }?.let {
                val left = (it.end - now) / MINUTE_MS
                add(Fact(SignalCatalog.CALENDAR_NOW, "calendar", "in a calendar event right now: \"${it.title.take(40)}\", $left minutes left", "日历：在「${it.title.take(16)}」里，还有 $left 分钟"))
            }
            events.firstOrNull { it.begin > now }?.let {
                val away = (it.begin - now) / MINUTE_MS
                add(Fact(SignalCatalog.CALENDAR_NOW, "calendar_next", "next calendar event: \"${it.title.take(40)}\" starts in $away minutes", "日历：$away 分钟后「${it.title.take(16)}」"))
            }
        }
        if (on(SignalCatalog.RINGER)) {
            val audio = context.getSystemService(AudioManager::class.java)
            val dnd = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
            when {
                dnd -> add(Fact(SignalCatalog.RINGER, "quiet", "he has switched do-not-disturb on", "勿扰模式开着"))
                audio.ringerMode == AudioManager.RINGER_MODE_SILENT -> add(Fact(SignalCatalog.RINGER, "quiet", "he has silenced the ringer", "手机静音"))
                audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE -> add(Fact(SignalCatalog.RINGER, "quiet", "ringer set to vibrate only", "只震动"))
            }
        }
        val interactive = context.getSystemService(PowerManager::class.java).isInteractive
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        // A phone that is being used is not under a sleeping man's pillow, whatever the clock says.
        if (on(SignalCatalog.SLEEP) && !(interactive && !locked)) sleeping(now)?.let { add(it) }
        if (on(SignalCatalog.SCREEN)) {
            val away = DeviceMoments.screenOffAt.takeIf { it > 0 && !interactive }?.let { (now - it) / MINUTE_MS }
            when {
                interactive && !locked -> add(Fact(SignalCatalog.SCREEN, "screen", "he is using the phone right now (screen on, unlocked)", "正在用手机"))
                away != null && away >= 20 -> add(Fact(SignalCatalog.SCREEN, "screen", "the phone has been lying untouched for $away minutes", "手机放下 $away 分钟了"))
            }
        }
        if (on(SignalCatalog.POWER)) battery()?.let { add(it) }
        if (on(SignalCatalog.AUDIO)) audioOut()?.let { add(it) }
        if (on(SignalCatalog.PLACE)) place()?.let { add(it) }
        if (on(SignalCatalog.FOREGROUND_APP)) foregroundApp(now)?.let { add(it) }
        if (on(SignalCatalog.FATIGUE)) {
            val hour = db.events().countOutcomeSince(Outcome.CHAT_SENT, now - HOUR_MS)
            val day = db.events().countOutcomeSince(Outcome.CHAT_SENT, startOfDay())
            if (day > 0) add(Fact(SignalCatalog.FATIGUE, "interruptions", "the assistant has already messaged him $day times today, $hour of them in the last hour", "今天已经主动找过你 $day 次（最近一小时 $hour 次）"))
        }
    }

    fun toJson(facts: List<Fact>): JsonObject = buildJsonObject {
        put("local_time", SimpleDateFormat("HH:mm EEEE", Locale.US).format(Date()))
        facts.groupBy { it.key }.forEach { (key, group) ->
            if (group.size == 1) put(key, group[0].english) else putJsonArray(key) { group.forEach { add(it.english) } }
        }
    }

    private fun sleeping(now: Long): Fact? {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val alarm = context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime
        val night = hour >= 23 || hour < 6
        return when {
            alarm != null && alarm - now in 0..(9 * HOUR_MS) && (hour >= 22 || hour < 9) ->
                Fact(SignalCatalog.SLEEP, "asleep", "probably asleep: it is ${clock.format(Date(now))} and his alarm is set for ${clock.format(Date(alarm))}", "多半在睡觉（闹钟定在 ${clock.format(Date(alarm))}）")
            night -> Fact(SignalCatalog.SLEEP, "asleep", "it is the middle of the night (${clock.format(Date(now))})", "深夜")
            else -> null
        }
    }

    private fun battery(): Fact? {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val percent = level * 100 / scale
        val charging = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return when {
            charging -> Fact(SignalCatalog.POWER, "power", "the phone is charging ($percent%)", "在充电（$percent%）")
            percent in 0..15 -> Fact(SignalCatalog.POWER, "power", "battery is low ($percent%) and not charging", "电量只剩 $percent%")
            else -> null
        }
    }

    private fun audioOut(): Fact? {
        val types = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toSet()
        return when {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP in types || AudioDeviceInfo.TYPE_BLE_HEADSET in types ->
                Fact(SignalCatalog.AUDIO, "audio", "audio goes to a bluetooth device (headphones or a car)", "连着蓝牙耳机或车载")
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES in types || AudioDeviceInfo.TYPE_WIRED_HEADSET in types || AudioDeviceInfo.TYPE_USB_HEADSET in types ->
                Fact(SignalCatalog.AUDIO, "audio", "wired headphones are plugged in", "插着耳机")
            else -> null
        }
    }

    private fun place(): Fact? {
        val ssid = currentWifi(context) ?: return connectivity()?.let { Fact(SignalCatalog.PLACE, "place", it.first, it.second) }
        val home = settings.homeWifi.lines().any { it.isNotBlank() && it == ssid }
        val work = settings.workWifi.lines().any { it.isNotBlank() && it == ssid }
        return when {
            home -> Fact(SignalCatalog.PLACE, "place", "he is at home (on his home wifi)", "在家")
            work -> Fact(SignalCatalog.PLACE, "place", "he is at work (on his office wifi)", "在公司")
            else -> Fact(SignalCatalog.PLACE, "place", "he is on a wifi that is neither his home nor his office", "连着别处的 Wi‑Fi")
        }
    }

    private fun connectivity(): Pair<String, String>? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return null
        return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) "he is out and about (on mobile data, no wifi)" to "在外面（走流量）" else null
    }

    private fun foregroundApp(now: Long): Fact? {
        if (!usageAllowed(context)) return null
        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(now - 30 * MINUTE_MS, now)
        var last: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.packageName != context.packageName) last = event.packageName
        }
        val pkg = last ?: return null
        val label = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        if (pkg.contains("launcher", ignoreCase = true)) return null
        return Fact(SignalCatalog.FOREGROUND_APP, "foreground_app", "the app he last had open is $label", "最近在用「$label」")
    }

    /**
     * How much this conversation has mattered to him, from his own reactions. Only for notifications that came from the
     * phone: a feed item or a moment has no sender.
     */
    suspend fun sender(event: NotifEvent): JsonObject? {
        if (event.synthetic && !SignalCatalog.isPush(event)) return null
        val now = System.currentTimeMillis()
        val lines = buildJsonObject {
            if (on(SignalCatalog.SENDER_HISTORY) && event.title.isNotBlank()) {
                val since = now - 14 * DAY_MS
                val received = db.events().countConversation(event.pkg, event.title, since)
                if (received >= 3) {
                    val reactions = db.events().reactions(event.pkg, event.title, since).groupingBy { it }.eachCount()
                    val replied = reactions[Handled.REPLIED] ?: 0
                    val opened = (reactions[Handled.OPENED] ?: 0) + (reactions[Handled.READ_IN_APP] ?: 0)
                    val dismissed = reactions[Handled.DISMISSED] ?: 0
                    put("history", "in the last 14 days this conversation sent $received notifications; he replied to $replied, opened or read $opened, swiped away $dismissed")
                }
            }
            if (on(SignalCatalog.BURST) && event.title.isNotBlank()) {
                val others = db.events().sameNameSince(event.title, now - 15 * MINUTE_MS, event.id)
                val calls = others.count { it.category?.startsWith("call") == true || it.category?.startsWith("missed_call") == true }
                val apps = others.map { it.appName }.distinct().filter { it != event.appName }
                if (calls > 0 || apps.isNotEmpty()) {
                    put("burst", "the same name also reached him in the last 15 minutes through: " + (apps + listOfNotNull("$calls phone calls".takeIf { calls > 0 })).joinToString(", "))
                }
            }
        }
        return lines.takeIf { it.isNotEmpty() }
    }

    private fun startOfDay(): Long = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    companion object {
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 24 * HOUR_MS

        fun usageAllowed(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java)
            return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        }

        fun locationAllowed(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** The name of the Wi‑Fi the phone is on, or null when there is none or the name may not be read. */
        @Suppress("DEPRECATION")
        fun currentWifi(context: Context): String? {
            if (!locationAllowed(context)) return null
            val info = context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo ?: return null
            return info.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" && it != WifiManager.UNKNOWN_SSID }
        }
    }
}
