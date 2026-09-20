package com.logan.spellmini.actions

import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import com.logan.spellmini.net.str
import com.logan.spellmini.notify.SpellListenerService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Everything the assistant can do on the phone. Two kinds:
 *  - timed items that live inside this app (a reminder, a task the assistant performs later). They run without a
 *    screen, from anywhere, and can be taken back with one tap;
 *  - intents that open another app's screen with the details filled in (dialler, SMS composer, calendar editor, a
 *    deep link). The user always performs the last step there, which is why none of them needs a confirm card;
 *  - one action that reaches another person: sending a drafted reply. It only ever exists as a button showing the
 *    full text, and the user's tap is the confirmation.
 *
 * Android only lets an app start a screen while it is in the foreground, so the chat agent runs the second kind
 * directly during a conversation and turns it into a button under its message everywhere else.
 */
object Actions {
    const val CALENDAR = "create_calendar_event"
    const val ALARM = "set_alarm"
    const val TIMER = "set_timer"
    const val REMINDER = "set_reminder"

    /** Like a reminder, but when it fires the assistant does the work itself (research, summary) and reports back. */
    const val SCHEDULE = "schedule_task"
    const val OPEN_APP = "open_app"
    const val OPEN_LINK = "open_link"
    const val OPEN_NOTIFICATION = "open_notification"
    const val DIAL = "dial_number"
    const val COMPOSE = "compose_message"
    const val MAP = "show_on_map"
    const val SETTINGS = "open_settings"
    const val SHARE = "share_text"
    const val COPY = "copy_text"
    const val CONTACT = "add_contact"
    const val MUSIC = "play_music"
    const val CAMERA = "open_camera"

    /**
     * Sends a drafted reply in the conversation a notification came from. The one action here that speaks to someone
     * else in the user's name, so it never runs on its own: it is always a button, and the button shows the full text.
     */
    const val REPLY = "send_reply"

    /** These start another app's screen, which Android only allows while we are in the foreground. */
    val opensScreen = setOf(CALENDAR, ALARM, TIMER, OPEN_APP, OPEN_LINK, OPEN_NOTIFICATION, DIAL, COMPOSE, MAP, SETTINGS, SHARE, CONTACT, MUSIC, CAMERA)

    /** These live inside this app and can be taken back with one tap. */
    val undoable = setOf(REMINDER, SCHEDULE)

    /** Leave lasting state behind, so "you already have this" is a meaningful answer to a repeated request. */
    val lasting = setOf(ALARM, REMINDER, SCHEDULE, CALENDAR)

    val all = opensScreen + undoable + COPY + REPLY

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
    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

    /** When a timed item is due, or null for every other tool. */
    fun dueAt(tool: String, args: JsonObject): Long? = if (tool in undoable) parseTime(args.str("time_iso")) else null

    private fun span(seconds: Int): String = when {
        seconds % 3600 == 0 -> "${seconds / 3600} 小时"
        seconds >= 60 && seconds % 60 == 0 -> "${seconds / 60} 分钟"
        seconds >= 60 -> "${seconds / 60} 分 ${seconds % 60} 秒"
        else -> "$seconds 秒"
    }

    private fun short(text: String?, max: Int = 16): String = text.orEmpty().replace('\n', ' ').trim().let { if (it.length > max) it.take(max) + "…" else it }

    /**
     * One line built from the exact arguments that will run, never from the model's prose. It is what the chat shows
     * after an action ran, the label of a button, and the key that tells a repeated request from a new one.
     */
    fun describe(tool: String, args: JsonObject): String = when (tool) {
        CALENDAR -> {
            val start = parseTime(args.str("start_iso"))
            val place = args.str("location")?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            "新建日程「${args.str("title").orEmpty()}」${start?.let { " " + show(it) } ?: "（时间未能解析）"}$place"
        }
        ALARM -> "设闹钟 %02d:%02d %s".format(args.int("hour") ?: 0, args.int("minute") ?: 0, args.str("label").orEmpty()).trim()
        TIMER -> "倒计时 ${span(args.int("seconds") ?: 0)} ${args.str("label").orEmpty()}".trim()
        REMINDER -> "提醒你「${args.str("text").orEmpty()}」${parseTime(args.str("time_iso"))?.let { " · " + show(it) } ?: "（时间未能解析）"}"
        SCHEDULE -> "到点我去办「${args.str("instruction").orEmpty()}」${parseTime(args.str("time_iso"))?.let { " · " + show(it) } ?: "（时间未能解析）"}"
        OPEN_APP -> "打开 ${args.str("app_name").orEmpty()}"
        OPEN_LINK -> "打开 ${linkTarget(args.str("url").orEmpty())}"
        OPEN_NOTIFICATION -> "打开这条通知对应的页面"
        DIAL -> "拨号盘填入 ${args.str("number").orEmpty()}（由你按下拨出）"
        COMPOSE -> if (args.str("channel") == "email") {
            "写邮件给 ${args.str("to").orEmpty()}：${short(args.str("subject") ?: args.str("body"))}（由你发出）"
        } else {
            "写短信给 ${args.str("to").orEmpty()}：${short(args.str("body"))}（由你发出）"
        }
        MAP -> (if (args.flag("navigate")) "导航去 " else "在地图里看 ") + args.str("query").orEmpty()
        SETTINGS -> "打开系统设置 · ${SETTINGS_PAGES[args.str("page")]?.first ?: args.str("page").orEmpty()}"
        SHARE -> "分享「${short(args.str("text"))}」"
        COPY -> "复制「${short(args.str("text"))}」"
        CONTACT -> "新建联系人 ${args.str("name").orEmpty()} ${args.str("phone").orEmpty()}".trim()
        MUSIC -> "播放 ${args.str("query").orEmpty()}"
        CAMERA -> if (args.str("mode") == "video") "打开相机录像" else "打开相机拍照"
        // Never shortened: what the button says is exactly what goes out.
        REPLY -> (if (args.flag("direct")) "发给 ${args.str("to").orEmpty()}：" else "复制并打开 ${args.str("app").orEmpty()}：") + args.str("text").orEmpty()
        else -> tool
    }

    /** True while the notification behind this event still offers a quick reply we can fire. */
    fun canReplyDirectly(eventId: Long?): Boolean =
        eventId != null && synchronized(SpellListenerService.replyActions) { SpellListenerService.replyActions[eventId] } != null

    /** Shown on link buttons: a host for web pages, the scheme for deep links, so the user sees where a tap leads. */
    private fun linkTarget(url: String): String {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url.take(30)
        return if (uri.scheme == "http" || uri.scheme == "https") uri.host.orEmpty().removePrefix("www.") else "${uri.scheme}:// 链接"
    }

    /** Common names the model uses for stock apps, mapped across the two UI languages the demo runs in. */
    private val ALIASES = listOf(
        setOf("设置", "settings"), setOf("时钟", "闹钟", "clock"), setOf("相机", "camera"), setOf("日历", "calendar"),
        setOf("电话", "拨号", "phone", "dialer"), setOf("信息", "短信", "messages", "messaging"),
        setOf("相册", "图库", "照片", "photos", "gallery"), setOf("浏览器", "browser", "chrome"),
        setOf("计算器", "calculator"), setOf("文件", "文件管理", "files"), setOf("地图", "maps"), setOf("联系人", "通讯录", "contacts"),
    )

    /** Settings screens with a public intent action. The label is what the user sees; the action is what runs. */
    private val SETTINGS_PAGES: Map<String, Pair<String, String>> = mapOf(
        "main" to ("首页" to Settings.ACTION_SETTINGS),
        "wifi" to ("Wi-Fi" to Settings.ACTION_WIFI_SETTINGS),
        "bluetooth" to ("蓝牙" to Settings.ACTION_BLUETOOTH_SETTINGS),
        "mobile_data" to ("移动网络" to Settings.ACTION_DATA_ROAMING_SETTINGS),
        "airplane" to ("飞行模式" to Settings.ACTION_AIRPLANE_MODE_SETTINGS),
        "nfc" to ("NFC" to Settings.ACTION_NFC_SETTINGS),
        "vpn" to ("VPN" to Settings.ACTION_VPN_SETTINGS),
        "display" to ("显示与亮度" to Settings.ACTION_DISPLAY_SETTINGS),
        "sound" to ("声音" to Settings.ACTION_SOUND_SETTINGS),
        "do_not_disturb" to ("勿扰" to Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS),
        "location" to ("定位" to Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        "battery" to ("省电" to Settings.ACTION_BATTERY_SAVER_SETTINGS),
        "storage" to ("存储" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        "apps" to ("应用管理" to Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
        "notification_access" to ("通知使用权" to Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        "accessibility" to ("无障碍" to Settings.ACTION_ACCESSIBILITY_SETTINGS),
        "security" to ("安全" to Settings.ACTION_SECURITY_SETTINGS),
        "privacy" to ("隐私" to Settings.ACTION_PRIVACY_SETTINGS),
        "date_time" to ("日期与时间" to Settings.ACTION_DATE_SETTINGS),
        "language" to ("语言" to Settings.ACTION_LOCALE_SETTINGS),
        "keyboard" to ("输入法" to Settings.ACTION_INPUT_METHOD_SETTINGS),
        "about" to ("关于手机" to Settings.ACTION_DEVICE_INFO_SETTINGS),
    )

    val settingsPageNames: List<String> get() = SETTINGS_PAGES.keys.toList()

    /** Schemes that never go to ACTION_VIEW: they run script, read local files, or smuggle an arbitrary intent. */
    private val BLOCKED_SCHEMES = setOf("javascript", "file", "content", "intent", "data", "about", "blob")

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

    private fun installed(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    // ------------------------------------------------------------------ intents

    private fun viewIntent(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()))

    private fun composeIntent(args: JsonObject): Intent {
        val to = args.str("to").orEmpty().trim()
        val body = args.str("body").orEmpty()
        return if (args.str("channel") == "email") {
            // ACTION_SENDTO with a mailto: URI is the documented way to reach mail apps only, not every share target.
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(to)))
                .putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                .putExtra(Intent.EXTRA_SUBJECT, args.str("subject").orEmpty())
                .putExtra(Intent.EXTRA_TEXT, body)
        } else {
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(to.filter { it.isDigit() || it == '+' }))).putExtra("sms_body", body)
        }
    }

    /**
     * A place name is all we have, so only URIs that accept a keyword are used. Formats are from the vendors' URI API
     * docs: AMap's navigation and route URIs require coordinates, which leaves its keyword search; Baidu's navigation
     * takes a keyword directly. `geo:` is the platform standard and is answered by whichever map app is installed.
     */
    private fun mapIntents(context: Context, query: String, navigate: Boolean): List<Intent> {
        val q = Uri.encode(query)
        val amap = Intent(Intent.ACTION_VIEW, Uri.parse("androidamap://poi?sourceApplication=SpellMini&keywords=$q&dev=0")).setPackage(AMAP)
        val baiduNavi = Intent(Intent.ACTION_VIEW, Uri.parse("baidumap://map/navi?query=$q&coord_type=bd09ll&src=andr.logan.spellmini"))
        val baiduSearch = Intent(Intent.ACTION_VIEW, Uri.parse("baidumap://map/place/search?query=$q&src=andr.logan.spellmini"))
        val googleNavi = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q")).setPackage(GOOGLE_MAPS)
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q"))
        val ordered = if (navigate) {
            listOfNotNull(amap.takeIf { installed(context, AMAP) }, baiduNavi, googleNavi, geo)
        } else {
            listOfNotNull(geo, amap.takeIf { installed(context, AMAP) }, baiduSearch)
        }
        return ordered.filter { canHandle(context, it) }
    }

    private fun intentFor(context: Context, tool: String, args: JsonObject): Intent? = when (tool) {
        CALENDAR -> {
            val start = parseTime(args.str("start_iso")) ?: 0
            Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, args.str("title"))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, parseTime(args.str("end_iso")) ?: (start + 3_600_000))
                .putExtra(CalendarContract.Events.EVENT_LOCATION, args.str("location").orEmpty())
                .putExtra(CalendarContract.Events.DESCRIPTION, args.str("notes").orEmpty())
        }
        ALARM -> Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, args.int("hour") ?: 0)
            .putExtra(AlarmClock.EXTRA_MINUTES, args.int("minute") ?: 0)
            .putExtra(AlarmClock.EXTRA_MESSAGE, args.str("label").orEmpty())
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        TIMER -> Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, args.int("seconds") ?: 0)
            .putExtra(AlarmClock.EXTRA_MESSAGE, args.str("label").orEmpty())
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        OPEN_LINK -> viewIntent(args.str("url").orEmpty())
        DIAL -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + args.str("number").orEmpty().filter { it.isDigit() || it == '+' }))
        COMPOSE -> composeIntent(args)
        MAP -> mapIntents(context, args.str("query").orEmpty(), args.flag("navigate")).firstOrNull()
        SETTINGS -> SETTINGS_PAGES[args.str("page")]?.let { Intent(it.second) }
        SHARE -> Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, args.str("text").orEmpty()), null)
        CONTACT -> Intent(Intent.ACTION_INSERT).setType(ContactsContract.Contacts.CONTENT_TYPE)
            .putExtra(ContactsContract.Intents.Insert.NAME, args.str("name").orEmpty())
            .putExtra(ContactsContract.Intents.Insert.PHONE, args.str("phone").orEmpty())
            .putExtra(ContactsContract.Intents.Insert.EMAIL, args.str("email").orEmpty())
            .putExtra(ContactsContract.Intents.Insert.COMPANY, args.str("company").orEmpty())
        MUSIC -> Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, args.str("query").orEmpty())
        CAMERA -> Intent(if (args.str("mode") == "video") MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        else -> null
    }

    // ------------------------------------------------------------------ validate

    private fun futureTime(args: JsonObject): String? =
        if ((parseTime(args.str("time_iso")) ?: 0) <= System.currentTimeMillis()) "time_iso must be a future ISO-8601 local time such as 2026-09-20T15:00:00" else null

    /**
     * Everything that can be known in advance is checked here, before anything runs or a button is offered: a button
     * that fails only after the user taps it is worse than none. The returned error goes back to the model so it can
     * correct itself or tell the user the truth.
     */
    fun validate(context: Context, tool: String, args: JsonObject, eventId: Long?): String? {
        val missingHandler = "no app on this phone can handle this; tell the user, or try another way (open_app, open_link with an https page)"
        return when (tool) {
            CALENDAR -> when {
                args.str("title").isNullOrBlank() -> "title is required"
                parseTime(args.str("start_iso")) == null -> "start_iso must be ISO-8601 local time such as 2026-09-20T15:00:00"
                !canHandle(context, intentFor(context, tool, args)!!) -> "this phone has no calendar app that accepts new events; offer set_reminder instead"
                else -> null
            }
            ALARM -> when {
                (args.int("hour") ?: -1) !in 0..23 || (args.int("minute") ?: -1) !in 0..59 -> "hour 0-23 and minute 0-59 are required"
                !canHandle(context, Intent(AlarmClock.ACTION_SET_ALARM)) -> "this phone has no clock app that accepts alarms; offer set_reminder instead"
                else -> null
            }
            TIMER -> when {
                (args.int("seconds") ?: 0) !in 1..86_400 -> "seconds must be between 1 and 86400"
                !canHandle(context, Intent(AlarmClock.ACTION_SET_TIMER)) -> "this phone has no clock app that accepts timers; offer set_reminder instead"
                else -> null
            }
            SCHEDULE -> if (args.str("instruction").isNullOrBlank()) "instruction is required" else futureTime(args)
            REMINDER -> if (args.str("text").isNullOrBlank()) "text is required" else futureTime(args)
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
            OPEN_LINK -> {
                val url = args.str("url").orEmpty().trim()
                val scheme = runCatching { Uri.parse(url).scheme }.getOrNull()?.lowercase()
                when {
                    url.isBlank() || scheme.isNullOrBlank() -> "url must be a full URL with a scheme, e.g. https://… or an app deep link like bilibili://…"
                    scheme in BLOCKED_SCHEMES -> "links with the $scheme: scheme are not allowed"
                    !canHandle(context, viewIntent(url)) ->
                        "no installed app handles $scheme:// links. If you were guessing a deep link, fall back to the https page of the same content, or open_app."
                    else -> null
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
            COMPOSE -> when {
                args.str("channel") !in setOf("sms", "email") -> "channel must be sms or email"
                args.str("body").isNullOrBlank() -> "body is required"
                args.str("channel") == "sms" && args.str("to").orEmpty().count { it.isDigit() } < 3 -> "to must be a phone number for sms"
                args.str("channel") == "email" && !args.str("to").orEmpty().contains('@') -> "to must be an email address for email"
                !canHandle(context, composeIntent(args)) -> missingHandler
                else -> null
            }
            MAP -> when {
                args.str("query").isNullOrBlank() -> "query is required: a place name or address"
                mapIntents(context, args.str("query").orEmpty(), args.flag("navigate")).isEmpty() -> "no map app is installed on this phone"
                else -> null
            }
            SETTINGS -> when {
                args.str("page") !in SETTINGS_PAGES -> "page must be one of: " + SETTINGS_PAGES.keys.joinToString(", ")
                !canHandle(context, intentFor(context, tool, args)!!) -> "this phone has no such settings page; try page=main"
                else -> null
            }
            SHARE, COPY -> if (args.str("text").isNullOrBlank()) "text is required" else null
            CONTACT -> when {
                args.str("name").isNullOrBlank() -> "name is required"
                !canHandle(context, intentFor(context, tool, args)!!) -> missingHandler
                else -> null
            }
            MUSIC -> when {
                args.str("query").isNullOrBlank() -> "query is required"
                !canHandle(context, intentFor(context, tool, args)!!) ->
                    "no music app on this phone accepts a play-from-search request. Try open_link with the music app's search deep link, or open_app."
                else -> null
            }
            CAMERA -> if (canHandle(context, intentFor(context, tool, args)!!)) null else missingHandler
            REPLY -> when {
                args.str("text").isNullOrBlank() -> "text is required"
                args.str("text").orEmpty().length > MAX_REPLY_CHARS -> "a reply must stay under $MAX_REPLY_CHARS characters"
                eventId == null -> "there is no notification to reply to"
                else -> null
            }
            else -> "unknown tool"
        }
    }

    // ------------------------------------------------------------------ run

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

    /** Same tool and arguments always give the same PendingIntent, so an undo can find and cancel it. */
    private fun timedIntent(context: Context, tool: String, args: JsonObject): PendingIntent {
        val text = if (tool == SCHEDULE) args.str("instruction").orEmpty() else args.str("text").orEmpty()
        val code = (tool + "|" + args.str("time_iso").orEmpty() + "|" + text).hashCode()
        return PendingIntent.getBroadcast(
            context, code,
            Intent(context, ReminderReceiver::class.java)
                .putExtra(ReminderReceiver.EXTRA_TEXT, text)
                .putExtra(ReminderReceiver.EXTRA_DO_IT, tool == SCHEDULE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun arm(context: Context, tool: String, args: JsonObject, at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val fire = timedIntent(context, tool, args)
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
        }
    }

    /** AlarmManager forgets everything on reboot; called on start with the timed items that are still due. */
    fun rearm(context: Context, tool: String, args: JsonObject) {
        val at = dueAt(tool, args) ?: return
        if (at > System.currentTimeMillis()) arm(context, tool, args, at)
    }

    /** Takes back a reminder or a scheduled task. Returns false for tools that cannot be undone from here. */
    fun undo(context: Context, tool: String, args: JsonObject): Boolean {
        if (tool !in undoable) return false
        context.getSystemService(AlarmManager::class.java).cancel(timedIntent(context, tool, args))
        return true
    }

    /** Runs an action. Returns a short result line, or throws with a user-readable reason. */
    fun execute(context: Context, tool: String, args: JsonObject, eventId: Long?): String {
        when (tool) {
            REMINDER, SCHEDULE -> {
                val at = parseTime(args.str("time_iso")) ?: error("时间无法解析")
                arm(context, tool, args, at)
                return if (tool == SCHEDULE) "到 ${show(at)} 我去办，办完告诉你" else "到 ${show(at)} 我会提醒你"
            }
            COPY -> {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Spell", args.str("text").orEmpty()))
                return "已复制到剪贴板"
            }
            REPLY -> {
                val text = args.str("text").orEmpty()
                val action = synchronized(SpellListenerService.replyActions) { SpellListenerService.replyActions[eventId] }
                val input = action?.remoteInputs?.firstOrNull { it.allowFreeFormInput }
                if (action != null && input != null) {
                    val filled = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    RemoteInput.addResultsToIntent(action.remoteInputs, filled, Bundle().apply { putCharSequence(input.resultKey, text) })
                    action.actionIntent.send(context, 0, filled)
                    // A quick reply is accepted once; most apps then replace the notification.
                    synchronized(SpellListenerService.replyActions) { SpellListenerService.replyActions.remove(eventId) }
                    return "已发给 ${args.str("to").orEmpty()}"
                }
                // No quick reply (the app has none, or the notification is gone): put the text on the clipboard and land
                // in that conversation, where a paste and a tap on send finish the job.
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Spell", text))
                if (!openOriginal(context, eventId, args.str("pkg"))) error("回复已复制，但「${args.str("app").orEmpty()}」打不开，请手动打开后粘贴")
                return "回复已复制，聊天已打开，粘贴后发送"
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
        }
        val intent = intentFor(context, tool, args) ?: error("未知操作 $tool")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return when (tool) {
            CALENDAR -> "已在日历里填好，保存一下就行"
            ALARM -> "闹钟已设好"
            TIMER -> "倒计时已开始"
            OPEN_LINK -> {
                // Whether the page inside the app is the right one cannot be known from here; say only what is known.
                // "android" is the system chooser: several apps can open it and the user picks one.
                val handler = intent.resolveActivity(context.packageManager)?.packageName?.takeIf { it != "android" }
                val label = handler?.let { pkg -> runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull() }
                "已交给 ${label ?: "对应的 App"} 打开"
            }
            DIAL -> "号码已填进拨号盘"
            COMPOSE -> "内容已填好，发不发由你"
            MAP -> if (intent.`package` == AMAP && args.flag("navigate")) "已在高德里搜到这个地点，点「路线」就能导航" else "已在地图里打开"
            SETTINGS -> "已打开设置页"
            SHARE -> "已打开分享面板"
            CONTACT -> "已填好联系人，保存一下就行"
            MUSIC -> "已交给音乐 App 播放"
            CAMERA -> "已打开相机"
            else -> "已执行"
        }
    }

    private const val MAX_REPLY_CHARS = 300
    private const val AMAP = "com.autonavi.minimap"
    private const val GOOGLE_MAPS = "com.google.android.apps.maps"
}
