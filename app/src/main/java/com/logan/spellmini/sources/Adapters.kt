package com.logan.spellmini.sources

import com.logan.spellmini.data.FeedSource
import com.logan.spellmini.data.SourceConfig
import com.logan.spellmini.net.FeedItem
import com.logan.spellmini.net.Web
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What one look at a source produced: items, and any bookkeeping to write back into its config. */
class Reading(val items: List<FeedItem>, val config: Map<String, String> = emptyMap())

/** `route.paths[0].duration` over a JSON tree. Deliberately small: keys, indexes, nothing else. */
object JsonPath {
    private val STEP = Regex("""([^.\[\]]+)|\[(\d+)\]""")

    fun at(root: JsonElement?, path: String): JsonElement? {
        var node = root
        if (path.isBlank()) return node
        for (step in STEP.findAll(path)) {
            val key = step.groupValues[1]
            node = if (key.isNotEmpty()) (node as? JsonObject)?.get(key) else (node as? JsonArray)?.getOrNull(step.groupValues[2].toInt())
            if (node == null || node is JsonNull) return null
        }
        return node
    }

    fun text(root: JsonElement?, path: String): String = when (val node = at(root, path)) {
        null -> ""
        is JsonPrimitive -> node.content
        else -> node.toString().take(400)
    }

    private val SLOT = Regex("""\{([^\{\}]+)\}""")

    /** `{a.b} · {c}` with each slot replaced by the text at that path; a template without slots is a plain path when it has no spaces. */
    fun fill(root: JsonElement?, template: String, extra: Map<String, String> = emptyMap()): String =
        SLOT.replace(template) { slot -> extra[slot.groupValues[1]] ?: text(root, slot.groupValues[1]) }.trim()
}

object Adapters {
    fun headers(source: FeedSource, secret: String?): Map<String, String> {
        val name = source.config[SourceConfig.HEADER].orEmpty().trim()
        return if (name.isBlank() || secret.isNullOrBlank()) emptyMap() else mapOf(name to source.config[SourceConfig.PREFIX].orEmpty() + secret)
    }

    /** True when the source is to be left alone at this hour (`hours` = "17-20"). */
    fun asleep(source: FeedSource, hour: Int): Boolean {
        val window = source.config[SourceConfig.HOURS]?.split('-')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 2 } ?: return false
        return if (window[0] <= window[1]) hour !in window[0] until window[1] else hour in window[1] until window[0]
    }

    // ------------------------------------------------------------------ JSON

    suspend fun json(source: FeedSource, secret: String?): Reading {
        val (body, _) = Web.readText(source.url, 4_000_000L, headers(source, secret) + ("Accept" to "application/json"), ownNetwork = true)
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: throw IOException("返回的不是 JSON：${body.take(80)}")
        return if (source.config[SourceConfig.VALUE].isNullOrBlank()) Reading(list(source, root)) else number(source, root)
    }

    private fun list(source: FeedSource, root: JsonElement): List<FeedItem> {
        val c = source.config
        val array = JsonPath.at(root, c[SourceConfig.ITEMS].orEmpty()) as? JsonArray
            ?: throw IOException("在「${c[SourceConfig.ITEMS].orEmpty().ifBlank { "（根）" }}」这个路径上没有找到列表")
        return array.take(200).mapNotNull { node ->
            val title = JsonPath.fill(node, c[SourceConfig.TITLE].orEmpty()).take(160)
            val id = JsonPath.fill(node, c[SourceConfig.ID].orEmpty()).ifBlank { title }
            if (id.isBlank()) return@mapNotNull null
            FeedItem(
                id = id, title = title.ifBlank { id }, link = JsonPath.fill(node, c[SourceConfig.LINK].orEmpty()),
                date = c[SourceConfig.DATE]?.takeIf { it.isNotBlank() }?.let { JsonPath.text(node, it) }.orEmpty(),
                summary = Web.plain(JsonPath.fill(node, c[SourceConfig.TEXT].orEmpty())).replace(Regex("\\s+"), " ").trim().take(600),
            )
        }
    }

    /**
     * One number, and a rule for when it is worth a word: it moved by so many percent since he was last told, or it
     * crossed a line. Crossing is reported once on the way over, not on every look while it stays there.
     */
    private fun number(source: FeedSource, root: JsonElement): Reading {
        val c = source.config
        val raw = JsonPath.text(root, c.getValue(SourceConfig.VALUE)).toDoubleOrNull() ?: throw IOException("「${c[SourceConfig.VALUE]}」这个路径上不是一个数")
        val value = raw / (c[SourceConfig.SCALE]?.toDoubleOrNull()?.takeIf { it != 0.0 } ?: 1.0)
        val shown = if (value >= 100) "%.0f".format(value) else "%.3f".format(value).trimEnd('0').trimEnd('.')
        val last = c[SourceConfig.LAST]?.toDoubleOrNull()
        val lastShown = c[SourceConfig.LAST].orEmpty()
        fun item(why: String) = FeedItem(
            id = "v:$shown:${System.currentTimeMillis() / 3_600_000}", link = "", date = "",
            title = JsonPath.fill(root, c[SourceConfig.LABEL].orEmpty().ifBlank { "${source.name}：{value}" }, mapOf("value" to shown, "last" to lastShown)).take(160), summary = why,
        )
        c[SourceConfig.CHANGE]?.toDoubleOrNull()?.let { percent ->
            if (last == null || last == 0.0) return Reading(emptyList(), mapOf(SourceConfig.LAST to shown))
            val moved = (value - last) / last * 100
            return if (kotlin.math.abs(moved) >= percent) Reading(listOf(item("比上次告诉他时${if (moved > 0) "高" else "低"}了 ${"%.1f".format(kotlin.math.abs(moved))}%")), mapOf(SourceConfig.LAST to shown)) else Reading(emptyList())
        }
        val above = c[SourceConfig.ABOVE]?.toDoubleOrNull()
        val below = c[SourceConfig.BELOW]?.toDoubleOrNull()
        val side = when { above != null && value >= above -> "above"; below != null && value <= below -> "below"; else -> "between" }
        val crossed = side != "between" && side != c[SourceConfig.SIDE]
        val note = mapOf(SourceConfig.SIDE to side, SourceConfig.LAST to shown)
        return Reading(if (crossed) listOf(item(if (side == "above") "升到了你设的 ${c[SourceConfig.ABOVE]} 以上" else "降到了你设的 ${c[SourceConfig.BELOW]} 以下")) else emptyList(), note)
    }

    // ------------------------------------------------------------------ a page without a feed

    /** Lines of readable text that were not there last time. The previous text is kept in a file: it has no business in the database. */
    suspend fun page(source: FeedSource, secret: String?, dir: File): Reading {
        val page = Web.readPage(source.url, headers(source, secret), ownNetwork = true)
        val lines = page.text.lines().map { it.trim() }.filter { it.length >= 12 }
        val file = File(dir.apply { mkdirs() }, "page-${source.id}.txt")
        val before = if (file.exists()) file.readLines().toSet() else null
        file.writeText(lines.joinToString("\n"))
        if (before == null) return Reading(emptyList()) // first look: nothing to compare with
        val added = lines.filter { it !in before }
        if (added.sumOf { it.length } < 40) return Reading(emptyList())
        val text = added.joinToString("\n").take(900)
        return Reading(listOf(FeedItem(id = sha(text), title = "「${page.title.ifBlank { source.name }.take(40)}」有新内容", link = page.url, date = "", summary = text)))
    }

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ a calendar subscription

    suspend fun ics(source: FeedSource, secret: String?): Reading {
        val (body, _) = Web.readText(source.url, 4_000_000L, headers(source, secret), ownNetwork = true)
        if (!body.contains("BEGIN:VCALENDAR")) throw IOException("这个地址不是日历订阅（.ics）")
        // Long lines are folded: a line that starts with a space continues the one before.
        val lines = body.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "").lines()
        val now = System.currentTimeMillis()
        val day = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)
        val time = SimpleDateFormat("M月d日 EEEE HH:mm", Locale.CHINA)
        val events = mutableListOf<Pair<Long, FeedItem>>()
        var fields: MutableMap<String, String>? = null
        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> fields = mutableMapOf()
                line == "END:VEVENT" -> {
                    val f = fields; fields = null
                    val start = f?.get("DTSTART") ?: continue
                    val at = icsTime(start, f["DTSTART;TZID"]) ?: continue
                    if (at < now || at > now + HORIZON_MS) continue
                    val allDay = start.length == 8
                    val title = unescape(f["SUMMARY"].orEmpty()).take(120)
                    if (title.isBlank()) continue
                    val where = unescape(f["LOCATION"].orEmpty()).takeIf { it.isNotBlank() }?.let { "；地点 $it" }.orEmpty()
                    val more = unescape(f["DESCRIPTION"].orEmpty()).replace(Regex("\\s+"), " ").take(240)
                    events += at to FeedItem(
                        id = (f["UID"] ?: title) + "|" + start, title = title, link = f["URL"].orEmpty(), date = "",
                        summary = "时间：${(if (allDay) day else time).format(Date(at))}（已换算成手机所在时区）$where。$more".trim(),
                    )
                }
                fields != null -> {
                    val name = line.substringBefore(':')
                    val value = line.substringAfter(':', "")
                    val key = name.substringBefore(';')
                    fields[key] = value
                    Regex("TZID=([^;:]+)").find(name)?.let { fields["$key;TZID"] = it.groupValues[1] }
                }
            }
        }
        return Reading(events.sortedBy { it.first }.map { it.second }.take(60))
    }

    private fun icsTime(value: String, zone: String?): Long? = runCatching {
        when {
            value.length == 8 -> SimpleDateFormat("yyyyMMdd", Locale.US).parse(value)?.time
            value.endsWith("Z") -> SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value)?.time
            else -> SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply { zone?.let { timeZone = TimeZone.getTimeZone(it) } }.parse(value)?.time
        }
    }.getOrNull()

    private fun unescape(text: String) = text.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    private const val HORIZON_MS = 60L * 24 * 3_600_000L
}
