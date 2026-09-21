package com.logan.spellmini.signals

import android.Manifest
import com.logan.spellmini.data.NotifEvent

/** What a signal is for. The groups are also the sections of the signals page. */
enum class SignalGroup(val label: String, val blurb: String) {
    STATE("此刻的状态", "每次判断都随通知一起交给 JEV：你此刻在干嘛、方不方便被打扰。它们自己不触发任何事，只影响要不要现在打扰你。"),
    SENDER("这个人对你多重要", "从你自己的反应和系统里的标记算出来，随这个人的通知一起交给 JEV。"),
    MOMENT("时刻", "手机上发生的一个瞬间，本身就是一次触发：先过 JEV，值得才交给助理开口。每个都有冷却时间和每天的上限。"),
    STREAM("外面的流", "订阅源、公开接口、网页、日历、推送、邮箱：每条更新像通知一样过一遍分流。任何拿得到的源都能在这里接上。"),
}

/** What has to be granted or filled in before a signal can work. */
enum class Need(val label: String, val permissions: List<String> = emptyList()) {
    NONE(""),
    CALENDAR("读取日历", listOf(Manifest.permission.READ_CALENDAR)),
    USAGE_ACCESS("使用情况访问权限（在系统页面里打开）"),
    LOCATION("定位权限：读 Wi‑Fi 名称必须有它，后台也要读所以选「始终允许」", listOf(Manifest.permission.ACCESS_FINE_LOCATION)),
    IMAGES("读取照片和视频", listOf("android.permission.READ_MEDIA_IMAGES")),
    CITY("先在下面填一个城市"),
}

/**
 * One source of context. [sendsOut] says what extra leaves the phone when it is on, in the words shown to the user;
 * everything here ends up in a model request sooner or later, and the page should never make that a surprise.
 */
data class SignalDef(
    val id: String,
    val group: SignalGroup,
    val title: String,
    val detail: String,
    val defaultOn: Boolean,
    val need: Need = Need.NONE,
    val sendsOut: String? = null,
    /** Moments only: the shortest gap between two firings, and how many a day. */
    val cooldownMin: Int = 0,
    val perDay: Int = 0,
)

/**
 * Every context the app can take in, in one list: the signals page is drawn from it, the pipeline asks it what is on,
 * and the trace labels rows with its titles. Adding a source means adding an entry here and the code that feeds it.
 */
object SignalCatalog {
    // state
    const val DOING = "doing"
    const val CALENDAR_NOW = "calendar_now"
    const val RINGER = "ringer"
    const val SLEEP = "sleep"
    const val SCREEN = "screen"
    const val POWER = "power"
    const val AUDIO = "audio"
    const val PLACE = "place"
    const val FOREGROUND_APP = "foreground_app"
    const val FATIGUE = "fatigue"

    // sender
    const val SENDER_HISTORY = "sender_history"
    const val CHANNEL = "channel"
    const val BURST = "burst"

    // moments
    const val WAKE_UP = "wake_up"
    const val CALL_ENDED = "call_ended"
    const val ARRIVED = "arrived"
    const val MEETING_SOON = "meeting_soon"
    const val MEETING_ENDED = "meeting_ended"
    const val SCREENSHOT = "screenshot"
    const val HOME = "arrived_home"
    const val WORK = "arrived_work"
    const val BEDTIME = "bedtime"
    const val APP_INSTALLED = "app_installed"
    const val BACK_TO_PHONE = "back_to_phone"
    const val WEATHER_PLAN = "weather_plan"
    const val EXPIRY = "expiry"

    // streams
    const val MODEL_CHANGES = "model_changes"

    val ALL: List<SignalDef> = listOf(
        SignalDef(DOING, SignalGroup.STATE, "正在进行的事", "导航中、通话中、会议中、投屏或录屏中、打车行程中、外卖配送中、在放音乐。来自通知栏里的常驻通知，以前被当噪音丢掉。", true, sendsOut = "你正在做的这类事和那个 App 的名字（不含通知正文）"),
        SignalDef(CALENDAR_NOW, SignalGroup.STATE, "日历：此刻和下一场", "「在开会，到 15:00」「40 分钟后有评审」。会议里只放要紧的。", true, Need.CALENDAR, sendsOut = "此刻和下一场日程的标题"),
        SignalDef(RINGER, SignalGroup.STATE, "响铃模式和勿扰", "你设了静音或勿扰，就不为不要紧的事出声。", true),
        SignalDef(SLEEP, SignalGroup.STATE, "大概在睡觉", "夜里，且系统时钟里定了明早的闹钟：按睡着了处理。", true),
        SignalDef(SCREEN, SignalGroup.STATE, "屏幕和解锁", "屏幕亮着、刚解锁，还是已经放下两小时。", true),
        SignalDef(POWER, SignalGroup.STATE, "充电和电量", "在充电多半是在桌前或床边；电量很低时少打扰。", true),
        SignalDef(AUDIO, SignalGroup.STATE, "耳机或车载蓝牙", "声音正从耳机或蓝牙设备出去。只读设备类型，不读设备名。", true),
        SignalDef(PLACE, SignalGroup.STATE, "在家、在公司还是在外面", "按连着的 Wi‑Fi 判断。先在下面把家和公司的 Wi‑Fi 记下来。", false, Need.LOCATION),
        SignalDef(FOREGROUND_APP, SignalGroup.STATE, "正在用的 App", "在会议 App 里不打扰，在导航里只说要紧的。", false, Need.USAGE_ACCESS, sendsOut = "正在使用的 App 的名字"),
        SignalDef(FATIGUE, SignalGroup.STATE, "今天已经打扰你几次", "打扰多了，自动抬高门槛。", true),

        SignalDef(SENDER_HISTORY, SignalGroup.SENDER, "你对这个人、这个群的历史反应", "「他的消息你一般 3 分钟内回」「这个群你从不点开」。来自 0.7 起记下的点开、划掉、回复。", true),
        SignalDef(CHANNEL, SignalGroup.SENDER, "通知渠道和优先对话", "App 自己把这条通知归在「营销」还是「聊天」渠道；你在系统里有没有把这个对话标成优先。", true),
        SignalDef(BURST, SignalGroup.SENDER, "连环找你", "同一个名字 15 分钟内从几个 App 发来消息，或者夹着未接来电：多半是真急。", true),

        SignalDef(WAKE_UP, SignalGroup.MOMENT, "闹钟响过之后", "早报不在固定的 8 点，而在你真的起床的那一刻：今天的日程、昨晚到现在该回的、在办的进展、订阅里最值得看的几条。", true, cooldownMin = 600, perDay = 1),
        SignalDef(CALL_ENDED, SignalGroup.MOMENT, "通话结束", "挂了电话的那一刻最容易忘事：和不常联系的人通了一分钟以上，问一句要不要记点什么，把他之前发来的相关通知带上。听不到通话内容。", true, sendsOut = "通话对方的名字或号码、通了多久", cooldownMin = 10, perDay = 6),
        SignalDef(ARRIVED, SignalGroup.MOMENT, "导航或打车行程结束", "到了：取件码、会议室、对方电话，翻得到就摆出来，翻不到就不出声。", true, sendsOut = "导航或打车 App 的名字和它最后显示的那行字", cooldownMin = 20, perDay = 6),
        SignalDef(MEETING_SOON, SignalGroup.MOMENT, "会前 10 分钟", "这场会相关的通知、与会的事里你还欠着的。日历自己会提醒时间，所以没有可补的就不出声。", true, Need.CALENDAR, cooldownMin = 20, perDay = 8),
        SignalDef(MEETING_ENDED, SignalGroup.MOMENT, "会议一结束", "半小时以上的会结束时问一句：要不要记下结论和待办。", false, Need.CALENDAR, cooldownMin = 45, perDay = 4),
        SignalDef(SCREENSHOT, SignalGroup.MOMENT, "截图", "截图是你自己说「这个重要」：截到地址问要不要导航，截到活动问要不要进日历。", false, Need.IMAGES, sendsOut = "每一张新截图的画面（先由模型转写成文字）", cooldownMin = 1, perDay = 20),
        SignalDef(HOME, SignalGroup.MOMENT, "到家", "连上家里 Wi‑Fi 的那一刻：还没取的快递、今天没回的私事。", false, Need.LOCATION, cooldownMin = 180, perDay = 2),
        SignalDef(WORK, SignalGroup.MOMENT, "到公司", "连上公司 Wi‑Fi 的那一刻：今天的会、昨晚到现在工作上该回的。", false, Need.LOCATION, cooldownMin = 180, perDay = 2),
        SignalDef(BEDTIME, SignalGroup.MOMENT, "睡前插上充电器", "晚上十点以后插上充电器：今天还没处理的、明天第一件事、要不要定闹钟。", true, cooldownMin = 600, perDay = 1),
        SignalDef(APP_INSTALLED, SignalGroup.MOMENT, "装了新 App", "刚装好的那一刻意图最强。只有和你说过的计划对得上才开口（刚装了携程，而你说过十月想去青岛）。", true, sendsOut = "新装的 App 的名字", cooldownMin = 5, perDay = 5),
        SignalDef(BACK_TO_PHONE, SignalGroup.MOMENT, "放下很久之后拿起手机", "离开一个半小时以上再解锁：这段时间里发生了什么值得你知道的，一条说完。", true, cooldownMin = 90, perDay = 6),
        SignalDef(WEATHER_PLAN, SignalGroup.MOMENT, "天气碰上你的计划", "不播报天气，只在它和你的日程或通勤撞上时说：明早有雨而你八点半要出门。每晚看一次明天。", false, Need.CITY, cooldownMin = 600, perDay = 1),
        SignalDef(EXPIRY, SignalGroup.MOMENT, "到期雷达", "会员、订阅、证件、积分、优惠券的到期和自动续费日，从通知里记下，到期前两天问你一句。跟着「留意该有下文的事」一起跑。", true),

        SignalDef(MODEL_CHANGES, SignalGroup.STREAM, "模型列表的变更", "OpenRouter 上已有模型的价格、上下文长度变了，或者下架了。和「OpenRouter 新模型」用的是同一次请求。", true),
    )

    private val byId = ALL.associateBy { it.id }
    fun find(id: String): SignalDef? = byId[id]

    /** `pkg` of a moment's trace row is this plus the signal id; pushes use their own prefix and are ordinary notifications otherwise. */
    const val MOMENT_PKG = "signal."
    const val PUSH_PKG = "push."
    const val MOMENT_LABEL = "时刻 · "
    const val PUSH_LABEL = "推送 · "

    fun isMoment(event: NotifEvent): Boolean = event.pkg.startsWith(MOMENT_PKG)
    fun isPush(event: NotifEvent): Boolean = event.pkg.startsWith(PUSH_PKG)
    fun momentId(event: NotifEvent): String? = event.pkg.takeIf { it.startsWith(MOMENT_PKG) }?.removePrefix(MOMENT_PKG)
}
