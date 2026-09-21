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
    /** Items handed to triage so far. */
    val taken: Int = 0,
    val createdAt: Long = 0,
) {
    fun toJson(): String = buildJsonObject {
        put("kind", kind); put("name", name); put("url", url); put("enabled", enabled); put("preset", preset)
        put("everyMin", everyMin); put("lastPolledAt", lastPolledAt); put("lastError", lastError)
        putJsonArray("seen") { seen.takeLast(MAX_SEEN).forEach { add(it) } }
        put("mark", mark); put("taken", taken); put("createdAt", createdAt)
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
