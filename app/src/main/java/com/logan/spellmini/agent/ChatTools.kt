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
        "到某个时间点由你自己去办一件事（联网查资料、整理汇总），办完把结果发给用户并响铃。用户说「到时候帮我查一下/整理一份/盯一下」时用。调用即生效，可撤销。",
        listOf("time_iso", "instruction"),
        mapOf("time_iso" to Param("string", ISO), "instruction" to Param("string", "到点要办的事，写完整：查什么、整理成什么样。到时你只看得到这句话和聊天记录")),
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

    /**
     * A conversation gets everything. In the background the list is shorter on purpose: nobody asked for anything, so
     * only tools that fit "here is what I noticed, and one tap if you want it" are offered.
     */
    fun forMode(mode: TurnMode): JsonArray = buildJsonArray {
        add(search); add(remember); add(reminder(mode)); add(schedule)
        when (mode) {
            TurnMode.USER -> {
                add(draftReply(mode))
                feedControl.forEach { add(it) }
                listOf(calendar, alarm, timer, openApp, openLink, dial, compose, map, settings, share, copy, contact, music, camera).forEach { add(it) }
            }
            // Every proactive message already carries a "查看原消息" button, so open_notification is not offered.
            TurnMode.TRIGGER -> listOf(draftReply(mode), calendar, alarm, openLink, dial, compose, map, copy, contact).forEach { add(it) }
            TurnMode.SCHEDULED -> listOf(calendar, openLink, dial, compose, map, copy).forEach { add(it) }
        }
    }
}
