package com.logan.spellmini.actions

import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.logan.spellmini.net.str
import com.logan.spellmini.notify.SpellListenerService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Local write actions. None of these run on the model's say-so: the chat agent only drafts a confirm card, and
 * [execute] is called from the UI after the user taps Approve. That tap is also what makes the activity start legal
 * when the request originated from a background notification.
 */
object Actions {
    const val CALENDAR = "create_calendar_event"
    const val ALARM = "set_alarm"
    const val REMINDER = "set_reminder"
    const val OPEN_APP = "open_app"
    const val OPEN_NOTIFICATION = "open_notification"
    const val DIAL = "dial_number"

    val needsConfirmation = setOf(CALENDAR, ALARM, REMINDER, OPEN_APP, OPEN_NOTIFICATION, DIAL)

    private val friendly = DateTimeFormatter.ofPattern("M月d日 EEEE HH:mm", Locale.CHINA)

    /** Accepts "2026-09-20T15:00:00" (treated as local time) or an ISO string with an offset. */
    fun parseTime(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        val text = iso.trim()
        return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            .recoverCatching { LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            .recoverCatching { LocalDateTime.parse(text.replace(' ', 'T')).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            .getOrNull()
    }

    private fun show(millis: Long): String =
        friendly.format(java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    /** One-line description shown on the confirm card, built from the exact arguments that will be executed. */
    fun describe(tool: String, args: JsonObject): String = when (tool) {
        CALENDAR -> {
            val start = parseTime(args.str("start_iso"))
            val place = args.str("location")?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            "新建日程「${args.str("title").orEmpty()}」${start?.let { " " + show(it) } ?: "（时间未能解析）"}$place"
        }
        ALARM -> "设闹钟 %02d:%02d %s".format(args.int("hour") ?: 0, args.int("minute") ?: 0, args.str("label").orEmpty()).trim()
        REMINDER -> "提醒你「${args.str("text").orEmpty()}」${parseTime(args.str("time_iso"))?.let { " · " + show(it) } ?: "（时间未能解析）"}"
        OPEN_APP -> "打开 ${args.str("app_name").orEmpty()}"
        OPEN_NOTIFICATION -> "打开这条通知对应的页面"
        DIAL -> "拨号盘填入 ${args.str("number").orEmpty()}（由你按下拨出）"
        else -> tool
    }

    /** Common names the model uses for stock apps, mapped across the two UI languages the demo runs in. */
    private val ALIASES = listOf(
        setOf("设置", "settings"), setOf("时钟", "闹钟", "clock"), setOf("相机", "camera"), setOf("日历", "calendar"),
        setOf("电话", "拨号", "phone", "dialer"), setOf("信息", "短信", "messages", "messaging"),
        setOf("相册", "图库", "照片", "photos", "gallery"), setOf("浏览器", "browser", "chrome"),
        setOf("计算器", "calculator"), setOf("文件", "文件管理", "files"), setOf("地图", "maps"), setOf("联系人", "通讯录", "contacts"),
    )

    private fun launchables(context: Context) =
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)

    /** Finds a launcher app by label: exact, then alias (设置 = Settings), then substring. Null when nothing fits. */
    private fun resolveApp(context: Context, wanted: String): android.content.pm.ResolveInfo? {
        val pm = context.packageManager
        val name = wanted.trim().lowercase()
        val names = ALIASES.firstOrNull { name in it } ?: setOf(name)
        val apps = launchables(context)
        fun label(app: android.content.pm.ResolveInfo) = app.loadLabel(pm).toString().trim().lowercase()
        return apps.firstOrNull { label(it) in names }
            ?: apps.firstOrNull { app -> names.any { it.isNotBlank() && (label(app).contains(it) || it.contains(label(app))) } }
    }

    private fun canHandle(context: Context, intent: Intent): Boolean = intent.resolveActivity(context.packageManager) != null

    /**
     * Everything that can be known in advance is checked here, before a card is shown: a card that fails only after
     * the user taps it is worse than no card. The returned error goes back to the model so it can correct itself.
     */
    fun validate(context: Context, tool: String, args: JsonObject, eventId: Long?): String? = when (tool) {
        CALENDAR -> when {
            args.str("title").isNullOrBlank() -> "title is required"
            parseTime(args.str("start_iso")) == null -> "start_iso must be ISO-8601 local time such as 2026-09-20T15:00:00"
            !canHandle(context, Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)) ->
                "this phone has no calendar app that accepts new events; tell the user, or offer set_reminder instead"
            else -> null
        }
        ALARM -> when {
            (args.int("hour") ?: -1) !in 0..23 || (args.int("minute") ?: -1) !in 0..59 -> "hour 0-23 and minute 0-59 are required"
            !canHandle(context, Intent(AlarmClock.ACTION_SET_ALARM)) -> "this phone has no clock app that accepts alarms; offer set_reminder instead"
            else -> null
        }
        REMINDER -> when {
            args.str("text").isNullOrBlank() -> "text is required"
            (parseTime(args.str("time_iso")) ?: 0) <= System.currentTimeMillis() -> "time_iso must be a future ISO-8601 local time"
            else -> null
        }
        OPEN_APP -> {
            val wanted = args.str("app_name").orEmpty()
            when {
                wanted.isBlank() -> "app_name is required"
                resolveApp(context, wanted) != null -> null
                else -> "no installed app is called \"$wanted\". Installed apps: " +
                    launchables(context).map { it.loadLabel(context.packageManager).toString() }.distinct().sorted().take(80).joinToString(", ") +
                    ". Call open_app again with one of these exact names, or tell the user it is not installed."
            }
        }
        OPEN_NOTIFICATION -> when {
            eventId == null -> "only available when the conversation was triggered by a notification"
            synchronized(SpellListenerService.contentIntents) { SpellListenerService.contentIntents[eventId] } == null ->
                "this notification has no page to open (it carried no tap target, or the app restarted since)"
            else -> null
        }
        DIAL -> when {
            args.str("number").orEmpty().count { it.isDigit() } < 3 -> "number is required"
            !canHandle(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:10086"))) -> "this device cannot place calls"
            else -> null
        }
        else -> "unknown tool"
    }

    /**
     * Opens what a notification pointed at: its own tap target when we still hold it, otherwise the app that posted it.
     * The tap target lives only in memory and may be one-shot, so falling back to the app is the normal case after a
     * restart. Returns false when neither is possible.
     */
    fun openOriginal(context: Context, eventId: Long?, pkg: String?): Boolean {
        val target = eventId?.let { id -> synchronized(SpellListenerService.contentIntents) { SpellListenerService.contentIntents[id] } }
        if (target != null) {
            val options = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
            } else null
            if (runCatching { target.send(context, 0, null, null, null, null, options) }.isSuccess) return true
        }
        val launch = pkg?.let { context.packageManager.getLaunchIntentForPackage(it) } ?: return false
        return runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    /** Runs an approved action. Returns a short result line for the chat, or throws with a user-readable reason. */
    fun execute(context: Context, tool: String, args: JsonObject, eventId: Long?): String {
        when (tool) {
            CALENDAR -> {
                val start = parseTime(args.str("start_iso")) ?: error("开始时间无法解析")
                val end = parseTime(args.str("end_iso")) ?: (start + 3_600_000)
                context.startActivity(
                    Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, args.str("title"))
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                        .putExtra(CalendarContract.Events.EVENT_LOCATION, args.str("location").orEmpty())
                        .putExtra(CalendarContract.Events.DESCRIPTION, args.str("notes").orEmpty())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return "已在日历里填好，保存一下就行"
            }
            ALARM -> {
                context.startActivity(
                    Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, args.int("hour") ?: 0)
                        .putExtra(AlarmClock.EXTRA_MINUTES, args.int("minute") ?: 0)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, args.str("label").orEmpty())
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return "闹钟已设好"
            }
            REMINDER -> {
                val at = parseTime(args.str("time_iso")) ?: error("提醒时间无法解析")
                val text = args.str("text").orEmpty()
                val fire = PendingIntent.getBroadcast(
                    context, (at % Int.MAX_VALUE).toInt(),
                    Intent(context, ReminderReceiver::class.java).putExtra(ReminderReceiver.EXTRA_TEXT, text),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val alarms = context.getSystemService(AlarmManager::class.java)
                if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
                } else {
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
                }
                return "到 ${show(at)} 我会提醒你"
            }
            OPEN_APP -> {
                val wanted = args.str("app_name").orEmpty().trim()
                val pm = context.packageManager
                val match = resolveApp(context, wanted) ?: error("手机上没找到叫「$wanted」的 App")
                val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: error("「$wanted」无法启动")
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "已打开 ${match.loadLabel(pm)}"
            }
            OPEN_NOTIFICATION -> {
                val target = synchronized(SpellListenerService.contentIntents) { SpellListenerService.contentIntents[eventId] }
                    ?: error("这条通知的跳转已经失效（App 重启后不再保留）")
                val options = if (Build.VERSION.SDK_INT >= 34) {
                    ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
                } else null
                target.send(context, 0, null, null, null, null, options)
                return "已打开原通知"
            }
            DIAL -> {
                val number = args.str("number").orEmpty().filter { it.isDigit() || it == '+' }
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "号码已填进拨号盘"
            }
            else -> error("未知操作 $tool")
        }
    }
}
