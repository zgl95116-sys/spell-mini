package com.logan.spellmini.pipeline

import android.app.PendingIntent
import android.util.Log
import com.logan.spellmini.data.AppDb
import com.logan.spellmini.data.AppRule
import com.logan.spellmini.data.Criteria
import com.logan.spellmini.data.EventStatus
import com.logan.spellmini.data.NotifEvent
import com.logan.spellmini.data.Outcome
import com.logan.spellmini.data.Route
import com.logan.spellmini.data.Settings
import com.logan.spellmini.net.OpenRouter
import com.logan.spellmini.net.dbl
import com.logan.spellmini.net.obj
import com.logan.spellmini.net.str
import com.logan.spellmini.notify.RawNotification
import com.logan.spellmini.notify.SpellListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Result of whatever ran after the verdict, written back onto the trace row. */
data class Downstream(
    val outcome: String,
    val note: String? = null,
    val refId: Long? = null,
    val costUsd: Double? = null,
    val latencyMs: Long? = null,
)

/**
 * notification -> local filter -> merge bursts -> JEV verdict -> chat / feed / ignore.
 * Every step leaves a row in `events`, so a wrong or missing reaction can always be traced back.
 */
class Pipeline(
    private val db: AppDb,
    private val settings: Settings,
    private val api: OpenRouter,
    private val scope: CoroutineScope,
    private val profileText: suspend () -> String,
) {
    var onChat: (suspend (NotifEvent) -> Downstream)? = null
    var onFeed: (suspend (NotifEvent) -> Downstream)? = null

    /** Resolves a `review` verdict or a failed JEV call; returns the final route and a short note. */
    var onSecondJudge: (suspend (NotifEvent) -> Pair<String, String>)? = null
    var afterJudged: (suspend () -> Unit)? = null

    /**
     * Lines seen so far for one notification key. Chat apps resend the previous turns with every update, so merging
     * whole snapshots would repeat them; de-duplicating by line keeps each message once, in order.
     */
    private class Pending(val eventId: Long, val firstSeen: Long, val lines: LinkedHashSet<String>, var updates: Int = 1, var job: Job? = null)

    private fun linesOf(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private val pending = HashMap<String, Pending>()
    private val lock = Mutex()
    private val jevSlots = Semaphore(4)
    private val feedSlots = Semaphore(2)
    private val chatGate = Mutex()
    private val lastSkipLog = HashMap<String, Long>()

    fun ingest(raw: RawNotification, contentIntent: PendingIntent?) {
        scope.launch {
            runCatching { ingestLocked(raw, contentIntent) }.onFailure { Log.e(TAG, "ingest failed", it) }
        }
    }

    /** Debug panel entry: pushes a fake notification through the exact same path as a real one. */
    fun simulate(appName: String, title: String, text: String) = ingest(
        RawNotification(
            key = "synthetic:" + UUID.randomUUID(), pkg = "synthetic." + appName.hashCode(), appName = appName,
            title = title, text = text, category = null, postedAt = System.currentTimeMillis(),
            ongoing = false, groupSummary = false, synthetic = true,
        ),
        null,
    )

    /**
     * Notifications waiting for their quiet window only live in memory. After a process kill they would sit at
     * "等待中" forever, so on start the recent ones are judged and older ones are marked as lost.
     */
    fun recoverOnStart() {
        scope.launch {
            val now = System.currentTimeMillis()
            runCatching {
                db.events().prune(now - RETENTION_MS)
                db.events().unfinished().forEach { event ->
                    // Not re-run: the turn may already have produced a message before the process died.
                    db.events().update(event.copy(outcome = Outcome.ERROR, outcomeNote = "进程被系统结束，下游没跑完"))
                }
                db.events().queued().forEach { event ->
                    if (now - event.postedAt <= RECOVER_WINDOW_MS) judge(event.id)
                    else db.events().update(event.copy(status = EventStatus.ERROR, jevError = "进程被系统结束，这条没来得及判断"))
                }
            }.onFailure { Log.e(TAG, "recovery failed", it) }
        }
    }

    fun rejudge(eventId: Long) {
        scope.launch { runCatching { judge(eventId) }.onFailure { Log.e(TAG, "rejudge failed", it) } }
    }

    private suspend fun ingestLocked(raw: RawNotification, contentIntent: PendingIntent?) = lock.withLock {
        val now = System.currentTimeMillis()
        val rule = db.appRules().get(raw.pkg)
        if (!raw.synthetic) {
            db.appRules().upsert(
                (rule ?: AppRule(raw.pkg, raw.appName)).let { it.copy(appName = raw.appName, count = it.count + 1, lastSeen = now) }
            )
        }

        val filterReason = localFilter(raw)
        val skip: Pair<String, String>? = when {
            filterReason != null -> EventStatus.FILTERED to filterReason
            rule?.enabled == false -> EventStatus.APP_OFF to "该 App 已在设置中关闭"
            !settings.pipelineEnabled -> EventStatus.FILTERED to "总开关已关闭"
            !api.hasKey -> EventStatus.ERROR to "安装包里没有 OpenRouter key"
            else -> null
        }
        if (skip != null) {
            logSkipped(raw, skip.first, skip.second, now)
            return@withLock
        }

        val existing = pending[raw.key]
        if (existing != null) {
            // Same notification updated while we were waiting: fold the new text in and restart the quiet window.
            if (!existing.lines.addAll(linesOf(raw.text))) return@withLock
            existing.updates += 1
            db.events().get(existing.eventId)?.let {
                db.events().update(
                    it.copy(text = existing.lines.toList().takeLast(MAX_MERGED_LINES).joinToString("\n"), mergedCount = existing.updates)
                )
            }
            schedule(raw.key, existing)
            return@withLock
        }

        if (db.events().countSame(raw.pkg, raw.title, raw.text, now - DUPLICATE_WINDOW_MS) > 0) {
            logSkipped(raw, EventStatus.FILTERED, "10 分钟内完全相同的内容", now)
            return@withLock
        }

        val id = db.events().insert(
            NotifEvent(
                sbnKey = raw.key, pkg = raw.pkg, appName = raw.appName, title = raw.title, text = raw.text,
                category = raw.category, postedAt = raw.postedAt, synthetic = raw.synthetic, status = EventStatus.QUEUED,
            )
        )
        contentIntent?.let { synchronized(SpellListenerService.contentIntents) { SpellListenerService.contentIntents[id] = it } }
        val entry = Pending(id, now, LinkedHashSet(linesOf(raw.text)))
        pending[raw.key] = entry
        schedule(raw.key, entry)
    }

    /** Ongoing notifications (music, downloads) update constantly; log each distinct skip at most once per 10 minutes. */
    private suspend fun logSkipped(raw: RawNotification, status: String, reason: String, now: Long) {
        val throttleKey = raw.key + "|" + reason
        if (now - (lastSkipLog[throttleKey] ?: 0) < DUPLICATE_WINDOW_MS) return
        if (lastSkipLog.size > 500) lastSkipLog.clear()
        lastSkipLog[throttleKey] = now
        db.events().insert(
            NotifEvent(
                sbnKey = raw.key, pkg = raw.pkg, appName = raw.appName, title = raw.title, text = raw.text,
                category = raw.category, postedAt = raw.postedAt, synthetic = raw.synthetic,
                status = status, filterReason = reason,
            )
        )
    }

    private fun schedule(key: String, entry: Pending) {
        entry.job?.cancel()
        val remaining = (entry.firstSeen + settings.maxWaitMs - System.currentTimeMillis()).coerceAtLeast(0)
        val wait = minOf(settings.quietWindowMs, remaining)
        entry.job = scope.launch {
            delay(wait)
            lock.withLock { if (pending[key] === entry) pending.remove(key) }
            runCatching { judge(entry.eventId) }.onFailure { Log.e(TAG, "judge failed", it) }
        }
    }

    private fun localFilter(raw: RawNotification): String? = when {
        raw.pkg in SYSTEM_NOISE -> "系统界面通知"
        raw.ongoing -> "常驻或进行中的通知"
        raw.groupSummary -> "分组摘要（内容在子通知里）"
        raw.category in NOISY_CATEGORIES -> "类别 ${raw.category}"
        raw.title.isBlank() && raw.text.isBlank() -> "没有文字内容"
        else -> null
    }

    private suspend fun judge(eventId: Long) {
        var event = db.events().get(eventId) ?: return
        val criteria = settings.criteria
        val state = buildState(event)
        val questions = buildQuestions(criteria)

        val verdict = runCatching { jevSlots.withPermit { api.decide(state, questions) } }
        event = verdict.fold(
            onSuccess = { result ->
                val route = result.answers.obj("route")
                event.copy(
                    status = EventStatus.JUDGED,
                    route = route?.str("choice") ?: Route.REVIEW,
                    routeProbs = route?.obj("probabilities")?.toString(),
                    confidence = route?.dbl("confidence"),
                    urgency = result.answers.obj("urgency")?.dbl("score"),
                    jevModel = result.model, jevSource = JEV_SOURCE,
                    jevLatencyMs = result.latencyMs, jevCostUsd = result.costUsd, jevError = null,
                    criteriaVersion = criteria.version,
                )
            },
            onFailure = { error ->
                Log.w(TAG, "JEV failed for event $eventId", error)
                // A failed verdict must not silently drop a possibly important notification: send it to the second judge.
                event.copy(
                    status = EventStatus.ERROR, route = Route.REVIEW, jevSource = JEV_SOURCE,
                    jevError = error.message?.take(300) ?: error.javaClass.simpleName, criteriaVersion = criteria.version,
                )
            },
        )

        var finalRoute = event.route ?: Route.REVIEW
        var note: String? = null
        if (finalRoute == Route.REVIEW) {
            val second = onSecondJudge?.let { judgeFn -> runCatching { judgeFn(event) }.getOrNull() }
            finalRoute = second?.first ?: Route.IGNORE
            note = second?.second ?: "二判不可用，按忽略处理"
        }
        // Chat turns run one at a time, so a verdict can wait a while; say so instead of leaving the row blank.
        val waiting = finalRoute == Route.CHAT || finalRoute == Route.FEED
        event = event.copy(finalRoute = finalRoute, secondJudgeNote = note, outcome = if (waiting) Outcome.PENDING else null)
        db.events().update(event)

        val downstream = runCatching { dispatch(event, finalRoute) }
            .getOrElse { Downstream(Outcome.ERROR, it.message?.take(300) ?: it.javaClass.simpleName) }
        db.events().update(
            event.copy(
                outcome = downstream.outcome, outcomeNote = downstream.note, outcomeRefId = downstream.refId,
                downstreamCostUsd = downstream.costUsd, downstreamLatencyMs = downstream.latencyMs,
            )
        )
        runCatching { afterJudged?.invoke() }
    }

    private suspend fun dispatch(event: NotifEvent, route: String): Downstream {
        val now = System.currentTimeMillis()
        return when (route) {
            // Checked under a lock together with the turn itself: verdicts arrive in parallel, and counting only
            // finished turns would let a burst (the first sweep) slip past the cap.
            Route.CHAT -> chatGate.withLock {
                if (db.events().countOutcomeSince(Outcome.CHAT_SENT, System.currentTimeMillis() - HOUR_MS) >= settings.chatPerHourCap) {
                    Downstream(Outcome.CAPPED, "已达每小时主动消息上限 ${settings.chatPerHourCap}，这条没有交给主模型")
                } else {
                    onChat?.invoke(event) ?: Downstream(Outcome.NONE, "Chat 尚未接入")
                }
            }
            Route.FEED -> when {
                db.events().countNotificationOutcomeSince(Outcome.FEED_CARD, now - DAY_MS) >= settings.feedPerDayCap ->
                    Downstream(Outcome.CAPPED, "已达每日 Feed 上限 ${settings.feedPerDayCap}")
                else -> feedSlots.withPermit { onFeed?.invoke(event) ?: Downstream(Outcome.NONE, "Feed 尚未接入") }
            }
            else -> Downstream(Outcome.NONE)
        }
    }

    private suspend fun buildState(event: NotifEvent): JsonObject {
        val now = System.currentTimeMillis()
        val recent = db.events().recentFromApp(event.pkg, now - 2 * HOUR_MS, event.id, 5)
        val profile = profileText().ifBlank { "(empty: the user has not described themselves yet)" }
        return buildJsonObject {
            // JEV is weak at date arithmetic, so every relative time is computed here.
            put("now", SimpleDateFormat(CLOCK_PATTERN, Locale.CHINA).format(Date(now)))
            put("user_profile", profile)
            putJsonArray("recent_from_same_app") {
                recent.forEach { past ->
                    add(buildJsonObject {
                        put("minutes_ago", (now - past.postedAt) / 60_000)
                        put("title", past.title.take(80))
                        put("text", past.text.take(160))
                        put("routed", past.finalRoute ?: past.route ?: "unknown")
                    })
                }
            }
            putJsonObject("new_notification") {
                put("app", event.appName)
                put("category", event.category ?: "unknown")
                put("title", event.title)
                put("text", event.text.take(1_500))
                put("merged_updates", event.mergedCount)
                put("minutes_ago", (now - event.postedAt) / 60_000)
            }
        }
    }

    private fun buildQuestions(criteria: Criteria): JsonObject = buildJsonObject {
        putJsonObject("route") {
            put("type", "choice")
            put("instructions", Criteria.COMMON + criteria.instructions)
            putJsonObject("criteria") {
                put(Route.CHAT, criteria.chat)
                put(Route.FEED, criteria.feed)
                put(Route.IGNORE, criteria.ignore)
                put(Route.REVIEW, criteria.review)
            }
        }
        // Rides along in the same request; only decides whether our own proactive notification makes a sound.
        putJsonObject("urgency") {
            put("type", "score")
            put("instructions", Criteria.COMMON + Criteria.URGENCY_INSTRUCTIONS)
            put("criteria", buildJsonArray { Criteria.URGENCY_LEVELS.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }
    }

    companion object {
        private const val TAG = "SpellPipeline"
        private const val JEV_SOURCE = "openrouter"
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 24 * HOUR_MS
        private const val DUPLICATE_WINDOW_MS = 600_000L
        private const val MAX_MERGED_LINES = 12
        private const val RECOVER_WINDOW_MS = 600_000L
        private const val RETENTION_MS = 14 * DAY_MS
        private const val CLOCK_PATTERN = "yyyy-MM-dd HH:mm EEEE"
        private val SYSTEM_NOISE = setOf("android", "com.android.systemui")
        private val NOISY_CATEGORIES = setOf("transport", "progress", "service", "sys", "navigation", "stopwatch", "workout")
    }
}
