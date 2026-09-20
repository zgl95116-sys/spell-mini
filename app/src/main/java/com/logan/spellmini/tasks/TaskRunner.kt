package com.logan.spellmini.tasks

import android.content.Context
import android.util.Log
import com.logan.spellmini.Graph
import com.logan.spellmini.data.AppDb
import com.logan.spellmini.data.EventStatus
import com.logan.spellmini.data.NotifEvent
import com.logan.spellmini.data.Outcome
import com.logan.spellmini.data.Repeat
import com.logan.spellmini.data.Route
import com.logan.spellmini.data.Settings
import com.logan.spellmini.data.Task
import com.logan.spellmini.data.TaskKind
import com.logan.spellmini.data.TaskStore
import com.logan.spellmini.net.FeedItem
import com.logan.spellmini.net.Web
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** What a task run came to. [marker] is MET / DONE / CHANGED / SAME for watches, RESOLVED / OPEN for loops, empty otherwise. */
data class TaskResult(val marker: String, val text: String, val spoke: Boolean, val costUsd: Double, val latencyMs: Long)

/**
 * Keeps the user's standing jobs going: recurring reports, watches with a condition, and the open loops picked up from
 * notifications. Every run is logged in the trace like a notification, including the runs that ended in silence,
 * because "it checked and nothing changed" is exactly what one wants to be able to verify.
 */
class TaskRunner(private val context: Context, private val db: AppDb, private val settings: Settings) {
    val store = TaskStore(db.memory())
    private val lock = Mutex()

    /** Bumped on every change so the task page redraws; tasks live inside JSON, which Room's flows cannot observe usefully. */
    val changes = MutableStateFlow(0)

    suspend fun create(task: Task): Task {
        val due = if (task.nextAt > 0) task.nextAt else TaskScheduler.next(task)
        val saved = store.add(task.copy(nextAt = due))
        TaskScheduler.arm(context, saved)
        changes.value += 1
        return saved
    }

    suspend fun setPaused(id: Long, paused: Boolean) {
        val task = store.get(id) ?: return
        val next = task.copy(paused = paused, nextAt = if (paused) task.nextAt else TaskScheduler.next(task).takeIf { it > 0 } ?: task.nextAt)
        store.save(next)
        TaskScheduler.arm(context, next)
        changes.value += 1
    }

    suspend fun delete(id: Long) {
        TaskScheduler.cancel(context, id)
        store.delete(id)
        changes.value += 1
    }

    /** AlarmManager forgets everything on reboot. */
    suspend fun rearmAll() = store.all().forEach { TaskScheduler.arm(context, it) }

    suspend fun spentToday(): Double {
        val since = System.currentTimeMillis() - 24 * 3_600_000L
        return db.events().taskRunsSince(since).sumOf { it.downstreamCostUsd ?: 0.0 }
    }

    /** One run. Serialised: two tasks due in the same minute take turns instead of racing for the chat. */
    suspend fun run(id: Long, manual: Boolean = false) = lock.withLock {
        val task = store.get(id) ?: return@withLock
        if (task.paused && !manual) return@withLock
        val now = System.currentTimeMillis()
        val result = runCatching { execute(task, manual) }.getOrElse { error ->
            Log.w(TAG, "task ${task.id} failed", error)
            TaskResult("ERROR", error.message?.take(200) ?: error.javaClass.simpleName, false, 0.0, 0)
        }
        log(task, result, now)
        // Trying a one-shot item out ahead of time ("现在跑一次") must not use it up: it is still due when it is due.
        val early = manual && task.nextAt > now
        val done = (task.repeat == Repeat.ONCE || task.kind == TaskKind.LOOP) && !early
        if (done && result.marker != "ERROR") {
            store.delete(task.id)
        } else {
            val updated = task.copy(
                lastRunAt = now, runs = task.runs + 1,
                lastResult = if (result.marker == "ERROR") task.lastResult else result.text.replace('\n', ' ').take(400),
                // A one-off condition that came true (arrived, on sale, below a price) has done its job; checking on would
                // only repeat the good news. A new release or a new paper is different: the next one is wanted too.
                paused = task.paused || result.marker == "DONE",
                nextAt = if (early) task.nextAt else TaskScheduler.next(task, now),
                seen = task.seen + pendingSeen.remove(task.id).orEmpty(),
            )
            store.save(updated)
            TaskScheduler.arm(context, updated)
        }
        changes.value += 1
    }

    private val pendingSeen = HashMap<Long, List<String>>()

    private suspend fun execute(task: Task, manual: Boolean): TaskResult {
        if (!manual && spentToday() >= settings.autoBudgetCents / 100.0) {
            return TaskResult("SKIPPED", "今天后台任务的花费已到上限 $${"%.2f".format(settings.autoBudgetCents / 100.0)}，这次没跑", false, 0.0, 0)
        }
        var fresh: List<FeedItem> = emptyList()
        if (task.kind == TaskKind.WATCH && task.feed.isNotBlank()) {
            fresh = Web.readFeed(task.feed).filter { it.id !in task.seen }
            pendingSeen[task.id] = fresh.map { it.id }
            // Nothing new in the feed: no model call at all. The first run only takes stock, so a fresh watch does not
            // announce thirty old entries.
            if (fresh.isEmpty()) return TaskResult("SAME", "订阅源里没有新条目", false, 0.0, 0)
            if (task.runs == 0 && task.seen.isEmpty() && !manual) return TaskResult("SAME", "第一次检查：记下了现有的 ${fresh.size} 条，之后只报新的", false, 0.0, 0)
        }
        return Graph.chat.onTask(task, fresh.take(8))
    }

    private suspend fun log(task: Task, result: TaskResult, at: Long) {
        db.events().insert(
            NotifEvent(
                sbnKey = "task:${task.id}:" + UUID.randomUUID(), pkg = "spellmini.task", appName = "在办", title = task.title,
                text = listOf(task.instruction, task.condition.takeIf { it.isNotBlank() }?.let { "条件：$it" }).filterNotNull().joinToString("\n"),
                postedAt = at, status = EventStatus.TASK, route = Route.CHAT, finalRoute = Route.CHAT,
                outcome = when {
                    result.marker == "ERROR" -> Outcome.ERROR
                    result.spoke -> Outcome.CHAT_SENT
                    else -> Outcome.CHAT_SILENT
                },
                outcomeNote = listOf(result.marker.takeIf { it.isNotBlank() }, result.text.replace('\n', ' ').take(160)).filterNotNull().joinToString("｜"),
                downstreamCostUsd = result.costUsd, downstreamLatencyMs = result.latencyMs,
            )
        )
    }

    companion object {
        private const val TAG = "SpellTasks"
        const val MAX_TASKS = 12
        const val MIN_HOURS = 1
    }
}
