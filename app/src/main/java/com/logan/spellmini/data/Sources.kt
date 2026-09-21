package com.logan.spellmini.data

import com.logan.spellmini.BuildConfig
import com.logan.spellmini.net.arr
import com.logan.spellmini.net.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

object SourceKind {
    /** RSS 2.0 or Atom. */
    const val RSS = "rss"

    /** OpenRouter's public model list: a JSON endpoint read as a feed, where every model that was not there before is an item. */
    const val OPENROUTER_MODELS = "openrouter_models"

    /** Any JSON endpoint: a list of items picked out with paths, or a single number watched for a change or a threshold. */
    const val JSON = "json"

    /** A page with no feed: what is new in its text since the last look. */
    const val PAGE = "page"

    /** A calendar subscription (.ics): every upcoming event that was not there before. */
    const val ICS = "ics"

    /** Pushed in rather than polled: an ntfy topic, any server-sent-events stream, a WebSocket. */
    const val NTFY = "ntfy"
    const val SSE = "sse"
    const val WEBSOCKET = "ws"

    /** A mailbox read over IMAP. */
    const val IMAP = "imap"

    val PUSHED = setOf(NTFY, SSE, WEBSOCKET)
    fun label(kind: String) = when (kind) {
        RSS -> "RSS"; OPENROUTER_MODELS -> "模型列表"; JSON -> "JSON 接口"; PAGE -> "网页变化"; ICS -> "日历订阅"
        NTFY -> "ntfy 推送"; SSE -> "SSE 流"; WEBSOCKET -> "WebSocket"; IMAP -> "邮箱"; else -> kind
    }
}

/** Keys of [FeedSource.config]. Paths are dotted, with [n] for an index: `route.paths[0].duration`. Templates hold `{path}`. */
object SourceConfig {
    const val ITEMS = "items"        // JSON: path to the array of items; blank when the response itself is the array
    const val ID = "id"              // JSON: template that identifies an item
    const val TITLE = "title"        // JSON, SSE, WS: template
    const val TEXT = "text"          // JSON, SSE, WS: template
    const val LINK = "link"          // JSON: template
    const val DATE = "date"          // JSON: path
    const val HEADER = "header"      // JSON, PAGE, ICS, SSE, WS: name of the request header the secret goes into, e.g. Authorization
    const val PREFIX = "prefix"      // what is put before the secret in that header, e.g. "Bearer "
    const val VALUE = "value"        // JSON, number mode: path to the number
    const val SCALE = "scale"        // number mode: the number is divided by this (seconds to minutes: 60)
    const val LABEL = "label"        // number mode: template for the item's title, with {value} and {last}
    const val CHANGE = "change"      // number mode: tell him when it moved by this many percent since he was last told
    const val ABOVE = "above"        // number mode: tell him when it rises to this
    const val BELOW = "below"        // number mode: tell him when it falls to this
    const val LAST = "last"          // number mode, bookkeeping: the value he was last told about
    const val SIDE = "side"          // number mode, bookkeeping: which side of the threshold it was on
    const val HOURS = "hours"        // any polled kind: only look between these hours, e.g. 17-20
    const val FIT = "fit"            // any published kind: how close to him (0 to 3) an item must be to become a card; blank for the default
    const val HOST = "host"          // IMAP
    const val PORT = "port"          // IMAP
    const val USER = "user"          // IMAP
    const val SEND = "send"          // WS: a message to send once connected (a subscribe request)
}

/**
 * A stream from the outside world the user subscribed to. Its items go through the same triage as notifications: JEV
 * reads every one of them, which is affordable where a chat model reading everything would not be. Stored as JSON in a
 * [MemoryEntry] with source [MemorySource.SOURCE], so the database schema stays as it is; [id] is that entry's id.
 */
data class FeedSource(
    val id: Long = 0,
    val kind: String = SourceKind.RSS,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    /** Came with the app rather than from the user; presets are switched off instead of deleted, so they do not come back. */
    val preset: Boolean = false,
    val everyMin: Int = 60,
    val lastPolledAt: Long = 0,
    val lastError: String = "",
    /** Item ids already taken in, newest last. A feed keeps old items around for weeks, longer than the trace is kept. */
    val seen: List<String> = emptyList(),
    /**
     * For lists that carry a creation time instead of stable positions (the model list): the newest creation time taken
     * in so far. Such a list reshuffles when an entry is withdrawn, and going by ids made a six-week-old model look new.
     */
    val mark: Long = 0,
    /** How to read the source, for the kinds that need telling; see [SourceConfig]. Secrets are not in here, see [Secrets]. */
    val config: Map<String, String> = emptyMap(),
    /**
     * True when what arrives is addressed to the user (a push, a mailbox, his GitHub notifications) rather than published
     * to the world. Such items are judged as notifications, not as things he might like to read.
     */
    val addressed: Boolean = false,
    /** Items handed to triage so far. */
    val taken: Int = 0,
    val createdAt: Long = 0,
) {
    fun toJson(): String = buildJsonObject {
        put("kind", kind); put("name", name); put("url", url); put("enabled", enabled); put("preset", preset)
        put("everyMin", everyMin); put("lastPolledAt", lastPolledAt); put("lastError", lastError)
        putJsonArray("seen") { seen.takeLast(MAX_SEEN).forEach { add(it) } }
        put("mark", mark); put("taken", taken); put("createdAt", createdAt); put("addressed", addressed)
        put("config", buildJsonObject { config.forEach { (key, value) -> put(key, value) } })
    }.toString()

    companion object {
        const val MAX_SEEN = 400

        fun parse(entry: MemoryEntry): FeedSource? {
            val o = runCatching { Json.parseToJsonElement(entry.text) as? JsonObject }.getOrNull() ?: return null
            fun long(key: String) = (o[key] as? JsonPrimitive)?.longOrNull ?: 0L
            fun bool(key: String, default: Boolean) = (o[key] as? JsonPrimitive)?.booleanOrNull ?: default
            return FeedSource(
                id = entry.id, kind = o.str("kind") ?: SourceKind.RSS, name = o.str("name").orEmpty(), url = o.str("url") ?: return null,
                enabled = bool("enabled", true), preset = bool("preset", false),
                everyMin = (o["everyMin"] as? JsonPrimitive)?.intOrNull ?: 60, lastPolledAt = long("lastPolledAt"),
                lastError = o.str("lastError").orEmpty(),
                seen = o.arr("seen").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                mark = long("mark"), taken = (o["taken"] as? JsonPrimitive)?.intOrNull ?: 0, createdAt = long("createdAt"),
                addressed = bool("addressed", false),
                config = (o["config"] as? JsonObject)?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }?.toMap().orEmpty(),
            )
        }
    }
}

/** Reads and writes [FeedSource]s through the memory table. */
class SourceStore(private val memory: MemoryDao) {
    suspend fun all(): List<FeedSource> = memory.bySource(MemorySource.SOURCE).mapNotNull(FeedSource::parse).sortedBy { it.createdAt }
    suspend fun get(id: Long): FeedSource? = all().firstOrNull { it.id == id }

    suspend fun add(source: FeedSource): FeedSource {
        val now = System.currentTimeMillis()
        val id = memory.insert(MemoryEntry(text = source.copy(createdAt = now).toJson(), source = MemorySource.SOURCE, createdAt = now, updatedAt = now))
        return source.copy(id = id, createdAt = now)
    }

    suspend fun save(source: FeedSource) = memory.setText(source.id, source.toJson(), System.currentTimeMillis())
    suspend fun delete(id: Long) = memory.delete(id)
}

/**
 * Sources every install starts with. All of them answered from a plain network on 2026-09-22. The noisy ones start
 * switched off: arXiv's AI list alone is some 200 papers a day, which is a fair test for JEV but not a fair default.
 * Chinese platforms (Weibo, Bilibili, Zhihu) have no feeds of their own and the public RSSHub instance refuses
 * anonymous requests, so none are listed; a self-hosted RSSHub address can be added like any other feed.
 */
object Presets {
    private val BUILT_IN = listOf(
        FeedSource(name = "AIHOT 精选", url = "https://aihot.news/feed.xml", everyMin = 30, preset = true),
        FeedSource(kind = SourceKind.OPENROUTER_MODELS, name = "OpenRouter 新模型", url = "https://openrouter.ai/api/v1/models", everyMin = 180, preset = true),
        FeedSource(name = "少数派", url = "https://sspai.com/feed", everyMin = 120, preset = true),
        FeedSource(name = "Hacker News 首页", url = "https://hnrss.org/frontpage", everyMin = 60, preset = true),
        FeedSource(name = "Product Hunt", url = "https://www.producthunt.com/feed", everyMin = 180, preset = true, enabled = false),
        FeedSource(name = "IT之家", url = "https://www.ithome.com/rss/", everyMin = 60, preset = true, enabled = false),
        FeedSource(name = "arXiv · cs.AI", url = "https://rss.arxiv.org/rss/cs.AI", everyMin = 360, preset = true, enabled = false),
    )

    /**
     * Feeds named at build time in the git-ignored local.properties (EXTRA_FEEDS, `name|url` pairs separated by `;`).
     * A feed address can carry a personal token, and this repository is public. One that points at a built-in feed
     * takes its place rather than sitting beside it.
     */
    private val LOCAL: List<FeedSource> = BuildConfig.EXTRA_FEEDS.split(';').mapNotNull { pair ->
        val name = pair.substringBefore('|', "").trim()
        val url = pair.substringAfter('|', "").trim()
        if (name.isBlank() || !url.startsWith("https://")) null else FeedSource(name = name, url = url, everyMin = 30, preset = true)
    }

    private fun bare(url: String) = url.substringBefore('?')

    val ALL: List<FeedSource> = LOCAL + BUILT_IN.filterNot { builtIn -> LOCAL.any { bare(it.url) == bare(builtIn.url) } }
}

/**
 * A source ready to be filled in: the signals page offers these so that connecting a well-known service is a matter of
 * a tap and, where needed, a token. [needs] tells the user what he still has to supply.
 */
data class SourceTemplate(val title: String, val note: String, val source: FeedSource, val needs: String = "", val secretHint: String = "")

object SourceTemplates {
    private fun json(name: String, url: String, everyMin: Int, addressed: Boolean = false, vararg config: Pair<String, String>) =
        FeedSource(kind = SourceKind.JSON, name = name, url = url, everyMin = everyMin, addressed = addressed, config = mapOf(*config))

    val ALL: List<SourceTemplate> = listOf(
        SourceTemplate(
            "Hugging Face 每日论文", "每天几十篇，JEV 挑和你有关的。国内网络用 hf-mirror.com 这个地址。",
            json("HF 每日论文", "https://hf-mirror.com/api/daily_papers?limit=50", 360, false, SourceConfig.ID to "{paper.id}", SourceConfig.TITLE to "{paper.title}",
                SourceConfig.TEXT to "{paper.summary}", SourceConfig.LINK to "https://huggingface.co/papers/{paper.id}", SourceConfig.DATE to "publishedAt", SourceConfig.FIT to "2.0"),
        ),
        SourceTemplate(
            "体育赛程（UFC）", "TheSportsDB 的公开接口。换联赛就改地址里的 id：英超 4328，NBA 4387，F1 4370。时间是 UTC，助理会换算。",
            json("UFC 赛程", "https://www.thesportsdb.com/api/v1/json/3/eventsnextleague.php?id=4443", 720, false, SourceConfig.ITEMS to "events", SourceConfig.ID to "{idEvent}",
                SourceConfig.TITLE to "{strEvent}", SourceConfig.TEXT to "比赛时间 {dateEvent} {strTime}（UTC）；地点 {strVenue} {strCity} {strCountry}", SourceConfig.LINK to "https://www.thesportsdb.com/event/{idEvent}"),
        ),
        SourceTemplate(
            "地震速报", "USGS 过去一天 4.5 级以上。离你远的 JEV 会判无关。",
            json("地震速报", "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/4.5_day.geojson", 60, false, SourceConfig.ITEMS to "features", SourceConfig.ID to "{id}",
                SourceConfig.TITLE to "{properties.title}", SourceConfig.TEXT to "震级 {properties.mag}，{properties.place}", SourceConfig.LINK to "{properties.url}", SourceConfig.DATE to "properties.time", SourceConfig.FIT to "1.5"),
        ),
        SourceTemplate(
            "汇率变动", "盯一个数：变动超过设定的百分比才告诉你。把地址里的 CNY 和路径里的 JPY 换成你关心的货币。",
            json("人民币兑日元", "https://open.er-api.com/v6/latest/CNY", 360, false, SourceConfig.VALUE to "rates.JPY", SourceConfig.CHANGE to "1.5",
                SourceConfig.LABEL to "人民币兑日元变了：1 元 = {value} 日元（上次告诉你时是 {last}）"),
        ),
        SourceTemplate(
            "中国节假日和调休", "一份公开的日历订阅（.ics）。任何 .ics 地址都能这样接：赛程、发布会、学校校历。",
            // A calendar he chose to follow is wanted whole: whether a holiday is "close to his interests" is the wrong question.
            FeedSource(kind = SourceKind.ICS, name = "节假日和调休", url = "https://www.shuyz.com/githubfiles/china-holiday-calender/master/holidayCal.ics", everyMin = 1440, config = mapOf(SourceConfig.FIT to "0")),
        ),
        SourceTemplate(
            "GitHub 通知", "有人 review 你的 PR、回复你的 issue、@ 你。当作发给你的消息处理。", needs = "一个只勾了 notifications 的访问令牌", secretHint = "ghp_… 或 github_pat_…",
            source = json("GitHub", "https://api.github.com/notifications", 5, true, SourceConfig.ID to "{id}:{updated_at}", SourceConfig.TITLE to "{subject.title}",
                SourceConfig.TEXT to "{repository.full_name} · {subject.type} · 原因：{reason}", SourceConfig.LINK to "{repository.html_url}", SourceConfig.DATE to "updated_at",
                SourceConfig.HEADER to "Authorization", SourceConfig.PREFIX to "Bearer "),
        ),
        SourceTemplate(
            "高德：开车通勤要多久", "盯一个数：傍晚时段超过设定的分钟数才告诉你。", needs = "你自己的高德 Web 服务 key，和地址里起点、终点的经纬度", secretHint = "",
            source = json("下班路上", "https://restapi.amap.com/v3/direction/driving?origin=116.31,39.98&destination=116.48,39.99&key=你的key", 10, false,
                SourceConfig.VALUE to "route.paths[0].duration", SourceConfig.SCALE to "60", SourceConfig.ABOVE to "45", SourceConfig.HOURS to "17-20",
                SourceConfig.LABEL to "现在开车回家要 {value} 分钟"),
        ),
        SourceTemplate(
            "Home Assistant 的一个实体", "盯家里一个传感器的读数。也可以让 Home Assistant 直接推到 ntfy。", needs = "你的 Home Assistant 地址、实体名和长期访问令牌", secretHint = "长期访问令牌",
            source = json("家里的传感器", "https://你的地址/api/states/sensor.xxx", 15, false, SourceConfig.VALUE to "state", SourceConfig.ABOVE to "30",
                SourceConfig.LABEL to "家里的读数到了 {value}", SourceConfig.HEADER to "Authorization", SourceConfig.PREFIX to "Bearer "),
        ),
        SourceTemplate(
            "ntfy 推送入口", "脚本、NAS、训练任务、监控告警，一行 curl 就能推进来：curl -d \"跑完了\" ntfy.sh/你的主题名。主题名等于密码，起个长的随机名字，内容别放敏感信息。",
            FeedSource(kind = SourceKind.NTFY, name = "我的推送", url = "https://ntfy.sh/换成一个长的随机主题名", addressed = true), needs = "一个只有你知道的主题名",
        ),
        SourceTemplate(
            "网页变化", "没有订阅源的页面：公告页、价格页、榜单。正文里出现新内容就算一条。靠脚本渲染或要登录的页面读不到。",
            FeedSource(kind = SourceKind.PAGE, name = "一个网页", url = "https://", everyMin = 180), needs = "网页地址",
        ),
        SourceTemplate(
            "任意 JSON 接口", "自己填路径：快递、航班、价格、内部系统，只要它给 JSON。条目列表用 items / id / title / text / link；盯一个数用 value 加 change、above 或 below。",
            json("我的接口", "https://", 60, false, SourceConfig.ITEMS to "", SourceConfig.ID to "{id}", SourceConfig.TITLE to "{title}", SourceConfig.TEXT to "{summary}", SourceConfig.LINK to "{url}"), needs = "接口地址和字段路径",
        ),
        SourceTemplate(
            "SSE 或 WebSocket 流", "一直连着、来一条算一条的推送。消息是 JSON 的话，用 {路径} 取标题和正文；不是就整条当正文。",
            FeedSource(kind = SourceKind.SSE, name = "我的流", url = "https://", addressed = true, config = mapOf(SourceConfig.TITLE to "{title}", SourceConfig.TEXT to "{message}")), needs = "流的地址",
        ),
        SourceTemplate(
            "邮箱（IMAP，实验性）", "行程单、酒店确认、账单都在邮件里。只读收件箱里的新邮件，当作发给你的消息处理。QQ、163 要在网页版邮箱里开 IMAP 并生成授权码。",
            FeedSource(kind = SourceKind.IMAP, name = "我的邮箱", url = "imaps://imap.qq.com:993", everyMin = 10, addressed = true, config = mapOf(SourceConfig.USER to "你的邮箱地址")),
            needs = "邮箱地址和授权码（不是登录密码）", secretHint = "邮箱授权码",
        ),
    )

    /** Routes on an RSSHub instance of the user's own, for platforms that publish no feeds. Offered once he has filled in its address. */
    val RSSHUB_ROUTES: List<Pair<String, String>> = listOf(
        "微博热搜" to "/weibo/search/hot", "知乎热榜" to "/zhihu/hot", "B 站热门" to "/bilibili/popular/all", "36 氪快讯" to "/36kr/newsflashes",
        "少数派 Matrix" to "/sspai/matrix", "豆瓣正在热映" to "/douban/movie/playing", "抖音热点" to "/douyin/hot",
    )
}
