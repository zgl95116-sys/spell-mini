package com.logan.spellmini.agent

import com.logan.spellmini.actions.Actions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Who started the turn. It decides which tools are offered and whether a phone action runs or becomes a button. */
enum class TurnMode {
    /** The user typed something. The app is on screen, so actions run right away. */
    USER,

    /** A notification came in. We are in the background: nothing may open a screen, and the user asked for nothing. */
    TRIGGER,

    /** A task the assistant scheduled earlier has come due. Background as well. */
    SCHEDULED,
}

/** The function-calling schemas handed to the chat model. Kept apart from the agent so the turn logic stays readable. */
internal object ChatTools {
    const val SEARCH = "web_search"
    const val REMEMBER = "remember"
    const val FOLLOW = "follow_topic"
    const val UNFOLLOW = "unfollow_topic"
    const val REFRESH_FEED = "refresh_feed"
    const val DRAFT_REPLY = "draft_reply"
    const val UNHANDLED = "list_unhandled"
    const val READ_PAGE = "read_page"
    const val FETCH_FEED = "fetch_feed"
    const val SEARCH_HISTORY = "search_history"
    const val CALENDAR = "calendar_agenda"
    const val WATCH = "watch"
    const val LIST_TASKS = "list_tasks"
    const val UPDATE_TASK = "update_task"
    const val START_JOB = "start_job"
    const val SUBSCRIBE = "subscribe_source"
    const val UNSUBSCRIBE = "unsubscribe_source"
    const val LIST_SOURCES = "list_sources"

    private class Param(val type: String, val description: String, val options: List<String>? = null)

    private fun tool(name: String, description: String, required: List<String>, params: Map<String, Param>): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put("description", description)
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    params.forEach { (key, param) ->
                        putJsonObject(key) {
                            put("type", param.type)
                            put("description", param.description)
                            param.options?.let { options -> putJsonArray("enum") { options.forEach { add(it) } } }
                        }
                    }
                }
                putJsonArray("required") { required.forEach { add(it) } }
            }
        }
    }

    private const val ISO = "本地 ISO-8601 时间，如 2026-09-20T15:00:00，必须是将来"

    private val search = tool(
        SEARCH, "联网搜索最新信息，返回要点和来源。来源会自动做成链接卡片（带封面图）附在你的回复下面，所以回复里不要贴网址。想给用户看视频，就在搜索词里带上「视频」或平台名。",
        listOf("query"), mapOf("query" to Param("string", "搜索词，尽量具体，含时间地点")),
    )
    private val remember = tool(
        REMEMBER, "把关于用户的长期事实或偏好记入画像（包括他希望你怎么称呼他、用什么口吻）。",
        listOf("fact"), mapOf("fact" to Param("string", "一句话事实，第三人称，如「用户每周三晚上打羽毛球」")),
    )

    private fun reminder(mode: TurnMode) = tool(
        Actions.REMINDER,
        "到某个时间点在聊天里提醒用户并响铃。调用即生效，用户可以一键撤销。" + if (mode == TurnMode.USER) "" else
            "只有这条通知里有明确的时间点或截止时间、错过会有损失（几点前取件、哪天到期、几点开会或发车）才用；不要为「记得回复某人」「记得看一下」设提醒。",
        listOf("time_iso", "text"), mapOf("time_iso" to Param("string", ISO), "text" to Param("string", "提醒内容，写具体日期，不写「明天」")),
    )

    private val schedule = tool(
        Actions.SCHEDULE,
        "到某个时间点由你自己去办一件事（联网查资料、整理汇总），办完把结果发给用户并响铃。用户说「到时候帮我查一下/整理一份/盯一下」时用。" +
            "只办一次：填 time_iso。要重复（每天简报、每周周报）：填 repeat，再按规则填 at、weekday 或 every_hours，它会出现在「在办」里。调用即生效，可撤销。",
        listOf("instruction"),
        mapOf(
            "instruction" to Param("string", "到点要办的事，写完整：查什么、整理成什么样。到时你只看得到这句话、上次的结果和聊天记录"),
            "time_iso" to Param("string", "只办一次时填：$ISO"),
            "repeat" to Param("string", "重复规则，只办一次就不填", listOf("daily", "weekdays", "weekly", "hours")),
            "at" to Param("string", "daily / weekdays / weekly 的时刻，HH:mm，如 08:00"),
            "weekday" to Param("integer", "weekly 用：1 是周一，7 是周日"),
            "every_hours" to Param("integer", "hours 用：每隔几小时，最少 1"),
            "title" to Param("string", "在「在办」里显示的短名字，如「每日简报」，可省略"),
        ),
    )

    private val watch = tool(
        WATCH,
        "替用户长期盯着一件事，定时检查，只有条件满足或有实质变化才告诉他，其余时候不出声。用户说「盯着……有了/低于/一旦……告诉我」时用。" +
            "能用订阅源就填 feed_url（比搜索可靠得多）：RSS 或 Atom 地址，GitHub 项目的新版本是 https://github.com/<owner>/<repo>/releases.atom，" +
            "arXiv 是 https://export.arxiv.org/api/query?search_query=all:<关键词>&sortBy=submittedDate&sortOrder=descending。" +
            "机票、商品的实时价格靠搜索查不准，接下这类之前要如实告诉他只能当参考。",
        listOf("what", "condition"),
        mapOf(
            "what" to Param("string", "盯什么，写具体"),
            "condition" to Param("string", "什么情况下要告诉他，如「有新版本发布」「出现量产时间表」"),
            "every_hours" to Param("integer", "多久查一次，小时，默认 6，最少 1"),
            "feed_url" to Param("string", "订阅源地址，可省略"),
            "title" to Param("string", "短名字，可省略"),
        ),
    )

    private val tasks = listOf(
        tool(LIST_TASKS, "列出「在办」里的所有事项：定期任务、盯着的事、等下文的事，带编号。用户问「你现在在帮我盯什么」或要改某一项时先调用。", emptyList(), emptyMap()),
        tool(
            UPDATE_TASK, "暂停、恢复、删除「在办」里的一项，或者现在就跑一次。", listOf("id", "action"),
            mapOf("id" to Param("integer", "list_tasks 里的编号"), "action" to Param("string", "做什么", listOf("pause", "resume", "delete", "run_now"))),
        ),
    )

    private val sources = listOf(
        tool(
            SUBSCRIBE,
            "订阅一个信息源（RSS 或 Atom）：以后它的每条更新都会像通知一样过一遍分流，和用户有关的进 Feed，直接影响他手头的事才在聊天里说。" +
                "用户说「订阅……」「以后……有更新告诉我/放进 Feed」时用。url 填订阅地址，或者网站首页（会自动找它的订阅源）；只知道名字就先 web_search 找到官网。" +
                "GitHub 项目的新版本是 https://github.com/<owner>/<repo>/releases.atom。微博、B 站、知乎、公众号没有订阅源，如实告诉他订不了。" +
                "和 watch 的区别：watch 是盯一件具体的事等一个结果；订阅是长期看一个源的全部更新。",
            listOf("url"), mapOf("url" to Param("string", "订阅源地址或网站首页，https 开头"), "name" to Param("string", "显示用的短名字，可省略")),
        ),
        tool(UNSUBSCRIBE, "取消订阅一个信息源。", listOf("source"), mapOf("source" to Param("string", "list_sources 里的编号或名字"))),
        tool(LIST_SOURCES, "列出现在订阅的信息源、各自多久检查一次、上次检查的情况。用户问「我订阅了什么」或要取消某个之前先调用。", emptyList(), emptyMap()),
    )

    private val readPage = tool(
        READ_PAGE, "打开一个网页读正文。搜索结果只有几行摘要，要细节（时间表、参数、价格、步骤）就读原文。靠脚本渲染或要登录的页面读不到，会返回 error，换一个来源。",
        listOf("url"), mapOf("url" to Param("string", "https 网址")),
    )
    private val fetchFeed = tool(
        FETCH_FEED, "读一个 RSS 或 Atom 订阅源，返回最新的条目。GitHub 项目发布、arXiv 新论文、博客更新都有订阅地址。",
        listOf("url"), mapOf("url" to Param("string", "订阅源地址")),
    )
    private val searchHistory = tool(
        SEARCH_HISTORY, "在手机最近两周收到过的通知里按关键词查找。用户问「上周物业那条通知」「这周花了多少钱」「某某后来回我没有」时用。",
        listOf("keywords"), mapOf("keywords" to Param("string", "一到四个关键词，空格分开，如「物业 停水」"), "days" to Param("integer", "往回找几天，默认 7，最多 14")),
    )
    private val calendarAgenda = tool(
        CALENDAR, "读用户手机日历里接下来几天的日程（只读）。别人约时间、做简报、排行程之前先看一眼。",
        emptyList(), mapOf("days" to Param("integer", "看几天，默认 2，最多 14")),
    )
    private val startJob = tool(
        START_JOB,
        "把一件需要多步调研才能交付的活交给后台去做：行程、选购对比、专题周报、方案整理。后台会多轮搜索、读网页，最后交一页成品，做完通知他，" +
            "期间他可以继续聊天。一两次搜索就能答的问题不要用它。",
        listOf("title", "goal", "deliverable"),
        mapOf(
            "title" to Param("string", "这件活的短名字，几个字，如「磨豆机对比」「京都行程」"),
            "goal" to Param("string", "要解决什么，把他给的约束都带上（时间、预算、同行的人、偏好）。只写事情本身，不要写他的姓名、职业这类身份信息"),
            "deliverable" to Param("string", "要交什么样的成品，如「按天排的行程，含交通和备选」「三款的对比表加结论」"),
            "base_doc_id" to Param("integer", "在上一版成品上修改时，填那份成品的编号；否则省略"),
        ),
    )

    private val feedControl = listOf(
        tool(
            FOLLOW, "把一个主题加入用户的 Feed 关注列表：之后每一轮 Feed 巡查都会优先找它的新内容。用户说「帮我关注/盯着/持续跟进 X」时用。",
            listOf("topic"), mapOf("topic" to Param("string", "主题，几个字到一句话，如「Agent Memory 的最新论文」")),
        ),
        tool(UNFOLLOW, "把一个主题从 Feed 关注列表里移除。", listOf("topic"), mapOf("topic" to Param("string", "要移除的主题，尽量用关注列表里的原话"))),
        tool(
            REFRESH_FEED, "现在就为用户的 Feed 找一批新内容，后台进行，大约一分钟后出现在 Feed 页。用户说「更新一下 Feed」「Feed 里给我来点 X」时用。",
            emptyList(), mapOf("topic" to Param("string", "只想要某个主题时填写，否则省略")),
        ),
    )

    /**
     * In a background turn the conversation is the notification at hand; in a chat the user names whose message he
     * means ("回老周说可以"), and code finds that notification.
     */
    private fun draftReply(mode: TurnMode) = tool(
        DRAFT_REPLY,
        "替用户拟一句回复，回给发来消息的那个人或群。系统会把它做成你这条消息下面的按钮，按钮上是回复全文：那条通知支持快捷回复的，" +
            "他点一下就直接发出去；不支持的，点一下会复制这句话并打开那个聊天，他粘贴发送。你自己不会发出任何消息。" +
            "想给两个版本（比如答应和婉拒）就调用两次。",
        if (mode == TurnMode.USER) listOf("to", "text") else listOf("text"),
        buildMap {
            if (mode == TurnMode.USER) put("to", Param("string", "回给谁：对方的名字或群名，用聊天记录里「起因」写的那个"))
            put("text", Param("string", "回复全文。用用户本人的口吻，短，像他自己打的字；不加引号、称呼和落款"))
        },
    )

    private val unhandled = tool(
        UNHANDLED, "列出最近一天里你主动提过、而用户还没点开、没在 App 里看过、也没回复的通知。他问「还有什么没处理」「谁找我还没回」时用。",
        emptyList(), emptyMap(),
    )

    private val calendar = tool(
        Actions.CALENDAR, "打开日历的新建日程页并填好内容，用户点保存即可。", listOf("title", "start_iso"),
        mapOf(
            "title" to Param("string", "日程标题"),
            "start_iso" to Param("string", "开始时间，本地 ISO-8601，如 2026-09-20T15:00:00"),
            "end_iso" to Param("string", "结束时间，可省略，默认一小时"),
            "location" to Param("string", "地点，可省略"),
            "notes" to Param("string", "备注，可省略"),
        ),
    )
    private val alarm = tool(
        Actions.ALARM, "设置系统闹钟。", listOf("hour", "minute"),
        mapOf("hour" to Param("integer", "0-23"), "minute" to Param("integer", "0-59"), "label" to Param("string", "闹钟备注，可省略")),
    )
    private val timer = tool(
        Actions.TIMER, "开始一个系统倒计时（煮面、番茄钟、泡茶）。", listOf("seconds"),
        mapOf("seconds" to Param("integer", "时长，秒。10 分钟就是 600"), "label" to Param("string", "计时备注，可省略")),
    )
    private val openApp = tool(Actions.OPEN_APP, "打开手机上的某个 App。", listOf("app_name"), mapOf("app_name" to Param("string", "App 名称，如 微信、高德地图")))
    private val openLink = tool(
        Actions.OPEN_LINK,
        "打开一个链接：https 网页，或者 App 的 deeplink（如 bilibili://search?keyword=猫、taobao://s.taobao.com/search?q=耳机、" +
            "alipays://platformapi/startapp?saId=10000007 扫一扫、weixin://）。想直达某个 App 里的搜索结果或功能页时用它。" +
            "系统会先检查手机上有没有 App 能接这个链接；deeplink 是你凭记忆写的，不保证页面一定对，打不开就换 https 页面或 open_app。",
        listOf("url"), mapOf("url" to Param("string", "带 scheme 的完整链接，参数要做 URL 编码")),
    )
    private val dial = tool(
        Actions.DIAL, "把号码填进拨号盘，由用户自己按下拨出。", listOf("number"),
        mapOf("number" to Param("string", "电话号码"), "reason" to Param("string", "为什么要打，可省略")),
    )
    private val compose = tool(
        Actions.COMPOSE, "打开短信或邮件的编辑页，填好收件人和内容，由用户自己按发送。微信、飞书里的消息你发不了，那种情况用 copy_text。",
        listOf("channel", "to", "body"),
        mapOf(
            "channel" to Param("string", "sms 或 email", listOf("sms", "email")),
            "to" to Param("string", "手机号（sms）或邮箱地址（email）"),
            "subject" to Param("string", "邮件主题，短信可省略"),
            "body" to Param("string", "正文，用用户本人的口吻写"),
        ),
    )
    private val map = tool(
        Actions.MAP, "在地图 App 里查看一个地点，或发起去那里的导航。", listOf("query"),
        mapOf("query" to Param("string", "地名或地址，尽量带城市"), "navigate" to Param("boolean", "true 表示要导航过去，false 或省略表示只看位置")),
    )
    private val settings = tool(
        Actions.SETTINGS, "打开系统设置里的某一页。你不能替用户拨开关，只能把他带到那一页。", listOf("page"),
        mapOf("page" to Param("string", "设置页", Actions.settingsPageNames)),
    )
    private val share = tool(Actions.SHARE, "打开系统分享面板，把一段文字分享到用户选的 App。", listOf("text"), mapOf("text" to Param("string", "要分享的文字")))
    private val copy = tool(
        Actions.COPY, "把一段文字放进剪贴板。替用户拟好一句回复、整理好一段地址或单号时用，方便他去别的 App 里粘贴。",
        listOf("text"), mapOf("text" to Param("string", "要复制的文字，只放正文，不加引号和说明")),
    )
    private val contact = tool(
        Actions.CONTACT, "打开新建联系人页并填好信息，用户点保存即可。", listOf("name"),
        mapOf(
            "name" to Param("string", "姓名"), "phone" to Param("string", "电话，可省略"),
            "email" to Param("string", "邮箱，可省略"), "company" to Param("string", "公司，可省略"),
        ),
    )
    private val music = tool(Actions.MUSIC, "让音乐 App 按关键词搜索并播放。", listOf("query"), mapOf("query" to Param("string", "歌名、歌手或歌单")))
    private val camera = tool(
        Actions.CAMERA, "打开相机。", emptyList(), mapOf("mode" to Param("string", "photo 拍照（默认）或 video 录像", listOf("photo", "video"))),
    )

    /** For turns that may only read: searching the web and opening a page. */
    fun lookupOnly(): JsonArray = buildJsonArray { add(search); add(readPage) }

    /**
     * A conversation gets everything. In the background the list is shorter on purpose: nobody asked for anything, so
     * only tools that fit "here is what I noticed, and one tap if you want it" are offered.
     */
    fun forMode(mode: TurnMode): JsonArray = buildJsonArray {
        add(search); add(readPage); add(remember); add(reminder(mode)); add(schedule)
        when (mode) {
            TurnMode.USER -> {
                add(startJob); add(watch); tasks.forEach { add(it) }; sources.forEach { add(it) }
                add(fetchFeed); add(searchHistory); add(calendarAgenda)
                add(draftReply(mode)); add(unhandled)
                feedControl.forEach { add(it) }
                listOf(calendar, alarm, timer, openApp, openLink, dial, compose, map, settings, share, copy, contact, music, camera).forEach { add(it) }
            }
            // Every proactive message already carries a "查看原消息" button, so open_notification is not offered.
            TurnMode.TRIGGER -> listOf(draftReply(mode), calendarAgenda, searchHistory, calendar, alarm, openLink, dial, compose, map, copy, contact).forEach { add(it) }
            // A task that came due works alone: it may look things up everywhere, hand a big piece of work to a job, and
            // leave buttons, but it does not set up further tasks of its own.
            TurnMode.SCHEDULED -> listOf(startJob, fetchFeed, searchHistory, calendarAgenda, unhandled, draftReply(TurnMode.USER), calendar, openLink, dial, compose, map, copy).forEach { add(it) }
        }
    }
}
