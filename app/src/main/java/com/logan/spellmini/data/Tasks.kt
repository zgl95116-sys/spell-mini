package com.logan.spellmini.data

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

object TaskKind {
    /** Runs on a schedule and reports every time: a morning brief, a weekly digest. */
    const val RECURRING = "recurring"

    /** Checks something on a schedule and only speaks when a condition is met or something really changed. */
    const val WATCH = "watch"

    /** Something the notification stream led us to expect (a parcel, a promised reply). Checked once when it is due. */
    const val LOOP = "loop"
}

object Repeat {
    const val DAILY = "daily"
    const val WEEKDAYS = "weekdays"
    const val WEEKLY = "weekly"
    const val HOURS = "hours"
    const val ONCE = "once"
}

/**
 * Something the assistant keeps doing or keeps an eye on. Stored as JSON in a [MemoryEntry] with source
 * [MemorySource.TASK], so the database schema stays as it is; [id] is that entry's id.
 */
data class Task(
    val id: Long = 0,
    val kind: String,
    val title: String,
    val instruction: String,
    /** Watches only: what has to be true for the user to be told. */
    val condition: String = "",
    /** Watches only, optional: an RSS or Atom feed to read instead of searching the web. */
    val feed: String = "",
    val repeat: String,
    /** "HH:mm" for daily, weekdays and weekly; unused for [Repeat.HOURS]. */
    val at: String = "",
    /** 1 (Monday) to 7, for [Repeat.WEEKLY]. */
    val weekday: Int = 0,
    val everyHours: Int = 0,
    val nextAt: Long = 0,
    val lastRunAt: Long = 0,
    val lastResult: String = "",
    /** Feed item ids already reported, newest last, so a feed watch only ever mentions an item once. */
    val seen: List<String> = emptyList(),
    val paused: Boolean = false,
    val runs: Int = 0,
    /** Who set it up: the user in chat, or the assistant from the notification stream. */
    val origin: String = "chat",
    /** Loops only: the app and conversation the expectation came from, for looking the matter up again. */
    val app: String = "",
    val about: String = "",
    val createdAt: Long = 0,
) {
    fun toJson(): String = buildJsonObject {
        put("kind", kind); put("title", title); put("instruction", instruction); put("condition", condition); put("feed", feed)
        put("repeat", repeat); put("at", at); put("weekday", weekday); put("everyHours", everyHours)
        put("nextAt", nextAt); put("lastRunAt", lastRunAt); put("lastResult", lastResult)
        putJsonArray("seen") { seen.takeLast(MAX_SEEN).forEach { add(it) } }
        put("paused", paused); put("runs", runs); put("origin", origin); put("app", app); put("about", about); put("createdAt", createdAt)
    }.toString()

    companion object {
        private const val MAX_SEEN = 60

        fun parse(entry: MemoryEntry): Task? {
            val o = runCatching { Json.parseToJsonElement(entry.text) as? JsonObject }.getOrNull() ?: return null
            fun long(key: String) = (o[key] as? JsonPrimitive)?.longOrNull ?: 0L
            fun int(key: String) = (o[key] as? JsonPrimitive)?.intOrNull ?: 0
            return Task(
                id = entry.id, kind = o.str("kind") ?: return null, title = o.str("title").orEmpty(), instruction = o.str("instruction").orEmpty(),
                condition = o.str("condition").orEmpty(), feed = o.str("feed").orEmpty(), repeat = o.str("repeat") ?: Repeat.ONCE,
                at = o.str("at").orEmpty(), weekday = int("weekday"), everyHours = int("everyHours"), nextAt = long("nextAt"),
                lastRunAt = long("lastRunAt"), lastResult = o.str("lastResult").orEmpty(),
                seen = o.arr("seen").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                paused = (o["paused"] as? JsonPrimitive)?.booleanOrNull == true, runs = int("runs"), origin = o.str("origin") ?: "chat",
                app = o.str("app").orEmpty(), about = o.str("about").orEmpty(), createdAt = long("createdAt"),
            )
        }
    }
}

/** Reads and writes [Task]s through the memory table. */
class TaskStore(private val memory: MemoryDao) {
    suspend fun all(): List<Task> = memory.bySource(MemorySource.TASK).mapNotNull(Task::parse)
    suspend fun get(id: Long): Task? = all().firstOrNull { it.id == id }

    suspend fun add(task: Task): Task {
        val now = System.currentTimeMillis()
        val id = memory.insert(MemoryEntry(text = task.copy(createdAt = now).toJson(), source = MemorySource.TASK, createdAt = now, updatedAt = now))
        return task.copy(id = id, createdAt = now)
    }

    suspend fun save(task: Task) = memory.setText(task.id, task.toJson(), System.currentTimeMillis())
    suspend fun delete(id: Long) = memory.delete(id)
}
