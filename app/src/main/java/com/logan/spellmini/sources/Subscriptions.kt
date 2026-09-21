package com.logan.spellmini.sources

import android.util.Log
import com.logan.spellmini.data.AppDb
import com.logan.spellmini.data.FeedSource
import com.logan.spellmini.data.NotifEvent
import com.logan.spellmini.data.Presets
import com.logan.spellmini.data.Settings
import com.logan.spellmini.data.SourceKind
import com.logan.spellmini.data.SourceStore
import com.logan.spellmini.net.FeedItem
import com.logan.spellmini.net.Web
import com.logan.spellmini.net.arr
import com.logan.spellmini.net.obj
import com.logan.spellmini.net.str
import com.logan.spellmini.pipeline.Pipeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The world-side inputs: feeds and public lists the user subscribed to. Each new item is handed to the pipeline as if
 * it were a notification, so triage, the trace, his rules and the feed writer all apply unchanged. What makes this
 * affordable is JEV: reading every item of a busy feed costs about a hundredth of a cent each, so nothing has to be
 * pre-filtered by keyword and the judgment is the same one that reads his notifications.
 */
class Subscriptions(private val db: AppDb, private val settings: Settings, private val pipeline: Pipeline) {
    val store = SourceStore(db.memory())
    private val pollLock = Mutex()

    /** True while a round of polling runs; the feed header shows it. */
    val polling = MutableStateFlow(false)

    /** Adds the presets this install has not been offered yet. A preset the user removed stays removed. */
    suspend fun seedPresets() {
        val offered = settings.offeredPresets
        val existing = store.all().map { bare(it.url) }.toSet()
        Presets.ALL.filter { bare(it.url) !in offered && bare(it.url) !in existing }.forEach { store.add(it) }
        settings.offeredPresets = offered + Presets.ALL.map { bare(it.url) }
    }

    /** Called from the scheduler tick: polls whichever sources are due. Nights are left alone, like the interest patrol. */
    suspend fun pollDue() {
        if (!settings.subscriptionsEnabled || !settings.pipelineEnabled) return
        if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < QUIET_UNTIL_HOUR) return
        val now = System.currentTimeMillis()
        val due = store.all().filter { it.enabled && now - it.lastPolledAt >= it.everyMin * 60_000L }
        if (due.isNotEmpty()) pollAll(due)
    }

    /** "立即检查": every enabled source, whatever its interval. Returns a one-line summary for the UI. */
    suspend fun pollNow(): String = pollAll(store.all().filter { it.enabled })

    private suspend fun pollAll(sources: List<FeedSource>): String {
        if (!pollLock.tryLock()) return "上一轮还在检查"
        polling.value = true
        try {
            var taken = 0
            val failed = mutableListOf<String>()
            sources.forEach { source ->
                runCatching { poll(source) }.onSuccess { taken += it }.onFailure { error ->
                    Log.w(TAG, "poll failed: ${source.name}", error)
                    failed += source.name
                    store.get(source.id)?.let { store.save(it.copy(lastPolledAt = System.currentTimeMillis() - (it.everyMin - RETRY_MIN) * 60_000L, lastError = error.message?.take(120) ?: error.javaClass.simpleName)) }
                }
            }
            return listOfNotNull(
                "检查了 ${sources.size} 个订阅源，${if (taken == 0) "没有新条目" else "$taken 条新内容交给了分流"}",
                failed.takeIf { it.isNotEmpty() }?.let { "读不了：${it.joinToString("、")}" },
            ).joinToString("；")
        } finally {
            polling.value = false
            pollLock.unlock()
        }
    }

    /**
     * One source, one round. Returns how many items went to triage. The first round only takes stock, apart from a
     * taste of the newest few: a feed's backlog is not news, but an empty result right after subscribing looks broken.
     */
    private suspend fun poll(source: FeedSource): Int {
        val items = when (source.kind) {
            SourceKind.OPENROUTER_MODELS -> models(source.url)
            else -> Web.readFeedFull(source.url, MAX_ITEMS).items
        }
        val now = System.currentTimeMillis()
        val byTime = source.kind == SourceKind.OPENROUTER_MODELS
        val first = if (byTime) source.mark == 0L else source.seen.isEmpty()
        val known = source.seen.toSet()
        val unseen = if (byTime) items.filter { (parseDate(it.date) ?: 0L) > source.mark } else items.filter { it.id.isNotBlank() && it.id !in known }
        val fresh = if (first) {
            unseen.filter { item -> parseDate(item.date)?.let { now - it < FIRST_TASTE_MAX_AGE_MS } ?: true }.take(FIRST_TASTE)
        } else {
            // An edited post often comes back under a new id; something dated weeks ago is not news either way.
            unseen.filter { item -> parseDate(item.date)?.let { now - it < STALE_MS } ?: true }.take(MAX_PER_ROUND)
        }
        // Oldest first, so the trace and the feed read in the order things happened.
        fresh.reversed().forEach { item ->
            pipeline.ingestItem(
                pkg = pkgFor(source), appName = LABEL + source.name, key = keyFor(source.id, item.link),
                title = item.title, text = describe(item), category = item.category.ifBlank { null },
            )
        }
        // Everything in this round counts as seen, including what the first round skipped as backlog.
        store.get(source.id)?.let { current ->
            store.save(
                current.copy(
                    lastPolledAt = now, lastError = "", taken = current.taken + fresh.size,
                    seen = if (byTime) emptyList() else (current.seen + unseen.map { it.id }).distinct(),
                    mark = if (byTime) maxOf(current.mark, items.maxOfOrNull { parseDate(it.date) ?: 0L } ?: 0L) else current.mark,
                )
            )
        }
        return fresh.size
    }

    private fun describe(item: FeedItem): String {
        val published = parseDate(item.date)?.let { "发布于 " + SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(it)) }
        val tail = listOfNotNull(published, item.author.takeIf { it.isNotBlank() }?.let { "作者 $it" }).joinToString(" · ")
        return listOf(item.summary, tail.takeIf { it.isNotBlank() }?.let { "（$it）" }).filterNotNull().filter { it.isNotBlank() }.joinToString("\n")
    }

    /** OpenRouter's model list, newest first, shaped like feed items. */
    private suspend fun models(url: String): List<FeedItem> {
        val (body, _) = Web.readText(url, MODELS_MAX_BYTES)
        val data = (runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: throw IOException("模型列表不是预期的 JSON")).arr("data")
            ?: throw IOException("模型列表里没有 data")
        return data.mapNotNull { it as? JsonObject }.mapNotNull { model ->
            val id = model.str("id") ?: return@mapNotNull null
            val created = (model["created"] as? JsonPrimitive)?.longOrNull ?: 0L
            val pricing = model.obj("pricing")
            fun perMillion(key: String) = pricing?.str(key)?.toDoubleOrNull()?.let { "$" + "%.2f".format(it * 1_000_000) }
            val price = listOfNotNull(perMillion("prompt")?.let { "输入 $it" }, perMillion("completion")?.let { "输出 $it" }).joinToString(" / ")
            val context = (model["context_length"] as? JsonPrimitive)?.contentOrNull
            FeedItem(
                id = id, title = "OpenRouter 上新：${model.str("name") ?: id}", link = "https://openrouter.ai/$id",
                date = if (created > 0) ISO.format(Date(created * 1000)) else "",
                summary = listOfNotNull(
                    model.str("description")?.replace(Regex("\\s+"), " ")?.take(200),
                    context?.let { "上下文 $it token" }, price.takeIf { it.isNotBlank() }?.let { "每百万 token：$it" },
                ).joinToString("；"),
                category = "模型",
            ) to created
        }.sortedByDescending { it.second }.map { it.first }
    }

    // ------------------------------------------------------------------ managing the list

    /**
     * Subscribes to a feed address, or to a site that advertises one. Returns the source, or a reason in plain words.
     * Reading the feed once up front is the validation: an address that cannot be read is not kept.
     */
    suspend fun subscribe(input: String, name: String? = null): Result<FeedSource> = runCatching {
        val address = input.trim().let { if (it.startsWith("http", ignoreCase = true)) it else "https://$it" }
        store.all().firstOrNull { bare(it.url) == bare(address) }?.let { existing ->
            if (!existing.enabled) store.save(existing.copy(enabled = true))
            return@runCatching existing.copy(enabled = true)
        }
        val (url, title) = discover(address)
        store.all().firstOrNull { bare(it.url) == bare(url) }?.let { existing ->
            if (!existing.enabled) store.save(existing.copy(enabled = true))
            return@runCatching existing.copy(enabled = true)
        }
        if (store.all().size >= MAX_SOURCES) throw IOException("订阅源已经有 $MAX_SOURCES 个，到上限了，先删掉几个")
        store.add(FeedSource(name = (name?.trim().takeUnless { it.isNullOrBlank() } ?: title.ifBlank { host(url) }).take(24), url = url))
    }

    /** The address itself if it is a feed; otherwise the feed the page points at, then the usual places. */
    private suspend fun discover(address: String): Pair<String, String> {
        runCatching { Web.readFeedFull(address, 3) }.onSuccess { return address to it.title }
        val candidates = mutableListOf<String>()
        runCatching { Web.readHtml(address) }.onSuccess { (html, finalUrl) ->
            FEED_LINK.findAll(html).forEach { tag ->
                HREF.find(tag.value)?.groupValues?.get(1)?.let { candidates += resolve(finalUrl, it) }
            }
        }
        val root = address.removeSuffix("/")
        candidates += COMMON_PATHS.map { root + it }
        candidates.distinct().take(MAX_DISCOVERY).forEach { candidate ->
            runCatching { Web.readFeedFull(candidate, 3) }.onSuccess { return candidate to it.title }
        }
        throw IOException("在 $address 没找到 RSS 或 Atom 订阅源；给我订阅源本身的地址试试")
    }

    private fun resolve(base: String, href: String): String = when {
        href.startsWith("http", ignoreCase = true) -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/') + href
        else -> base.substringBeforeLast('/') + "/" + href
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        store.get(id)?.let { store.save(it.copy(enabled = enabled)) }
    }

    /** Presets are switched off rather than deleted; what the user added himself goes for good. */
    suspend fun remove(id: Long) {
        val source = store.get(id) ?: return
        if (source.preset) store.save(source.copy(enabled = false)) else store.delete(id)
    }

    suspend fun find(nameOrId: String): FeedSource? {
        val all = store.all()
        return all.firstOrNull { it.id.toString() == nameOrId.trim().removePrefix("#") }
            ?: all.firstOrNull { it.name.equals(nameOrId.trim(), ignoreCase = true) }
            ?: all.firstOrNull { it.name.contains(nameOrId.trim(), ignoreCase = true) || nameOrId.contains(it.name, ignoreCase = true) }
    }

    companion object {
        private const val TAG = "SpellSources"

        /** `pkg` of an item's trace row is this plus the source id; the label is what the user sees on rows, cards and messages. */
        const val PKG_PREFIX = "feed."
        const val LABEL = "订阅 · "

        private const val KEY_PREFIX = "feed|"
        private const val QUIET_UNTIL_HOUR = 7
        private const val MAX_ITEMS = 120
        private const val MAX_PER_ROUND = 40
        private const val FIRST_TASTE = 3
        private const val FIRST_TASTE_MAX_AGE_MS = 72 * 3_600_000L
        private const val RETRY_MIN = 10
        private const val MAX_SOURCES = 24
        private const val STALE_MS = 14 * 24 * 3_600_000L
        private const val MAX_DISCOVERY = 6
        private const val MODELS_MAX_BYTES = 6_000_000L

        private val FEED_LINK = Regex("""(?is)<link\b[^>]*type\s*=\s*["']application/(?:rss|atom)\+xml["'][^>]*>""")
        private val HREF = Regex("""(?i)href\s*=\s*["']([^"']+)["']""")
        private val COMMON_PATHS = listOf("/feed", "/rss", "/feed.xml", "/atom.xml", "/rss.xml", "/index.xml")
        private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }

        fun isItem(event: NotifEvent): Boolean = event.pkg.startsWith(PKG_PREFIX)

        /**
         * A source the user added himself is marked in the pkg of its rows (`feed.u12` rather than `feed.12`), the one
         * place the pipeline can read it without a lookup. He asked for that source by name, so its items only have to
         * be on topic at all, not as close to him as the items of a broad preset feed.
         */
        private const val OWN_PICK = "u"
        fun pkgFor(source: FeedSource) = PKG_PREFIX + (if (source.preset) "" else OWN_PICK) + source.id
        fun isOwnPick(event: NotifEvent): Boolean = event.pkg.startsWith(PKG_PREFIX + OWN_PICK)
        fun isItemLabel(label: String?): Boolean = label?.startsWith(LABEL) == true

        /** The key of an item's trace row carries its link, the one thing about it that has no column of its own. */
        fun keyFor(sourceId: Long, link: String) = "$KEY_PREFIX$sourceId|$link"
        fun linkOf(event: NotifEvent): String? =
            event.sbnKey.takeIf { it.startsWith(KEY_PREFIX) }?.substringAfter(KEY_PREFIX)?.substringAfter('|')?.takeIf { it.startsWith("http") }

        private fun bare(url: String) = url.substringBefore('?').removeSuffix("/")
        private fun host(url: String) = url.substringAfter("://").substringBefore('/').removePrefix("www.")

        private val DATE_FORMATS = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        )

        /** RSS dates are RFC 822, Atom's are ISO 8601; a date that cannot be read is simply unknown. */
        fun parseDate(text: String): Long? {
            if (text.isBlank()) return null
            DATE_FORMATS.forEach { pattern ->
                runCatching { SimpleDateFormat(pattern, Locale.US).apply { isLenient = true }.parse(text.trim())?.time }.getOrNull()?.let { return it }
            }
            return null
        }
    }
}
