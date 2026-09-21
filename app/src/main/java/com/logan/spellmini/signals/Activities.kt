package com.logan.spellmini.signals

import android.app.Notification
import android.service.notification.StatusBarNotification

/** Something the user is in the middle of, as far as the notification shade can tell. */
enum class ActivityKind(val english: String, val chinese: String) {
    CALL("in a phone or voice call", "通话中"),
    NAVIGATION("navigating or driving with a maps app", "导航中"),
    MEETING("in a video meeting", "会议中"),
    SCREEN_SHARE("sharing, casting or recording his screen, so anything shown on it may be seen by others", "投屏或录屏中"),
    RIDE("in a ride-hailing trip", "打车行程中"),
    DELIVERY("waiting for a food delivery that is on its way", "外卖配送中"),
    MEDIA("playing music, a podcast or a video", "在播放"),
    TIMER("running a timer or stopwatch", "计时中"),
    ALARM_RINGING("his alarm is ringing", "闹钟在响"),
}

data class OngoingActivity(val kind: ActivityKind, val app: String, val title: String)

/**
 * Ongoing notifications are the phone's own status lights: navigation, a call, a meeting, a screen cast, a ride, a
 * delivery, a timer. They used to be dropped as noise, which they are as messages; as a description of what he is doing
 * right now they are the cheapest sensor there is, and their appearing and disappearing marks moments worth acting on.
 */
object Activities {
    private val MAPS = setOf("com.autonavi.minimap", "com.baidu.BaiduMap", "com.tencent.map", "com.google.android.apps.maps", "com.waze")
    private val RIDES = setOf("com.sdu.didi.psnger", "com.didi.global.passenger", "com.ubercab", "com.grabtaxi.passenger", "com.caocaokeji.user", "com.t3go.passenger")
    private val DELIVERIES = setOf("com.sankuai.meituan", "com.sankuai.meituan.takeoutnew", "me.ele", "com.dianping.v1")
    private val MEETINGS = setOf(
        "com.ss.android.lark", "com.larksuite.suite", "com.tencent.wemeet.app", "us.zoom.videomeetings", "com.alibaba.android.rimet",
        "com.google.android.apps.tachyon", "com.google.android.apps.meetings", "com.microsoft.teams",
    )
    private val MEETING_WORDS = Regex("会议中|正在会议|会议进行|通话中|语音通话|视频通话|in (a )?(meeting|call)", RegexOption.IGNORE_CASE)
    private val SHARE_WORDS = Regex("共享屏幕|屏幕共享|正在投屏|投屏中|投放|录屏|正在录制|屏幕录制|screen (cast|record|shar)|casting", RegexOption.IGNORE_CASE)
    private val DELIVERY_WORDS = Regex("配送中|骑手|正在为您送|预计.{0,8}送达")

    fun classify(sbn: StatusBarNotification, appName: String): OngoingActivity? {
        val n = sbn.notification
        val ongoing = sbn.isOngoing || (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val words = "$title $text"
        val kind = when {
            n.category == Notification.CATEGORY_ALARM -> ActivityKind.ALARM_RINGING
            !ongoing -> null
            SHARE_WORDS.containsMatchIn(words) -> ActivityKind.SCREEN_SHARE
            n.category == Notification.CATEGORY_CALL -> ActivityKind.CALL
            n.category == Notification.CATEGORY_NAVIGATION || sbn.packageName in MAPS -> ActivityKind.NAVIGATION
            sbn.packageName in MEETINGS && MEETING_WORDS.containsMatchIn(words) -> ActivityKind.MEETING
            sbn.packageName in RIDES -> ActivityKind.RIDE
            sbn.packageName in DELIVERIES && DELIVERY_WORDS.containsMatchIn(words) -> ActivityKind.DELIVERY
            n.category == Notification.CATEGORY_STOPWATCH -> ActivityKind.TIMER
            n.category == Notification.CATEGORY_TRANSPORT || n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) -> ActivityKind.MEDIA
            else -> null
        } ?: return null
        return OngoingActivity(kind, appName, title.take(60))
    }
}
