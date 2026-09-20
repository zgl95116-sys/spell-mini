package com.logan.spellmini.pipeline

import android.app.Notification
import android.app.PendingIntent
import android.util.Log
import com.logan.spellmini.Graph
import com.logan.spellmini.data.AppDb
import com.logan.spellmini.data.AppRule
import com.logan.spellmini.data.Criteria
import com.logan.spellmini.data.EventStatus
import com.logan.spellmini.data.Handled
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

    /** The user dealt with a notification himself; the chat agent marks its message and withdraws our own notification. */
    var onHandled: (suspend (NotifEvent, String) -> Unit)? = null

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

    /** Newest message time seen per notification key; only touched under [lock]. */
    private val newestSeen = HashMap<String, Long>()

    fun ingest(raw: RawNotification, contentIntent: PendingIntent?, replyAction: Notification.Action? = null) {
        scope.launch {
            runCatching { ingestLocked(raw, contentIntent, replyAction) }.onFailure { Log.e(TAG, "ingest failed", it) }
        }
    }

    /** Called by the listener when a notification leaves the shade because of something the user did. */
    fun onRemoved(key: String, how: String, postedAgain: () -> Boolean) {
        scope.launch {
            runCatching {
                if (how == Handled.READ_IN_APP) {
                    delay(REPOST_GRACE_MS)
                    if (postedAgain()) return@launch
                }
                recordHandled(key, how)
            }.onFailure { Log.w(TAG, "could not record removal", it) }
        }
    }

    /** One row per notification: the first thing he did is the one that says how quickly he got to it. */
    private suspend fun recordHandled(key: String, how: String) {
        val event = db.events().latestByKey(key) ?: return
        val earlier = db.events().handledSince(key, event.postedAt)
        // A reply outranks whatever came first: "opened" says he saw it, "replied" says the matter is closed.
        if (earlier != null && !(how == Handled.REPLIED && earlier.filterReason != Handled.REPLIED)) return
        db.events().insert(
            NotifEvent(
                sbnKey = key, pkg = event.pkg, appName = event.appName, title = event.title, text = "",
                postedAt = System.currentTimeMillis(), status = EventStatus.SEEN, filterReason = how,
            )
        )
        runCatching { onHandled?.invoke(event, how) }
    }

    /** The newest copy of a notification holds the handles that still work, so they replace the earlier ones. */
    private fun keepHandles(eventId: Long, contentIntent: PendingIntent?, replyAction: Notification.Action?) {
        contentIntent?.let { synchronized(SpellListenerService.contentIntents) { SpellListenerService.contentIntents[eventId] = it } }
        replyAction?.let { synchronized(SpellListenerService.replyActions) { SpellListenerService.replyActions[eventId] = it } }
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

    private suspend fun ingestLocked(raw: RawNotification, contentIntent: PendingIntent?, replyAction: Notification.Action?) = lock.withLock {
        val now = System.currentTimeMillis()
        // Another assistant's notifications start switched off (see Graph.OTHER_ASSISTANTS); everything else starts on.
        val rule = db.appRules().get(raw.pkg) ?: AppRule(raw.pkg, raw.appName, enabled = raw.pkg !in Graph.OTHER_ASSISTANTS)
        if (!raw.synthetic) db.appRules().upsert(rule.copy(appName = raw.appName, count = rule.count + 1, lastSeen = now))

        val filterReason = localFilter(raw) ?: repost(raw)
        val skip: Pair<String, String>? = when {
            filterReason != null -> EventStatus.FILTERED to filterReason
            !rule.enabled -> EventStatus.APP_OFF to "该 App 已在设置中关闭"
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
            keepHandles(existing.eventId, contentIntent, replyAction)
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
        keepHandles(id, contentIntent, replyAction)
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

    /**
     * Messaging apps rebuild their whole notification group whenever one message arrives: every unread conversation is
     * posted again, word for word, and after a quick reply the conversation comes back with the user's own line on top.
     * On the test phone one new SMS re-posted nine conversations and each was treated as news. A conversation is only
     * news when its newest message is someone else's and is newer than the last time we handled that conversation.
     */
    private suspend fun repost(raw: RawNotification): String? {
        val newestAt = raw.latestMessageAt ?: return null
        if (raw.latestFromUser) {
            recordHandled(raw.key, Handled.REPLIED)
            return "最新一条是你自己发的"
        }
        // Exact while the process lives: the same newest message again means nothing new.
        val seen = newestSeen[raw.key]
        if (newestSeen.size > 500) newestSeen.clear()
        newestSeen[raw.key] = maxOf(seen ?: 0L, newestAt)
        if (seen != null) return if (newestAt <= seen) REPOSTED else null
        // After a restart only the trace is left. Two messages seconds apart can carry timestamps on either side of
        // the moment the first was handled, hence the margin.
        val handledAt = db.events().lastHandledAt(raw.pkg, raw.title) ?: return null
        return if (newestAt + RESTART_MARGIN_MS <= handledAt) REPOSTED else null
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
        // JEV is there to protect recall. "ignore" by a nose (a colleague's @-mention came out ignore 0.54 / chat 0.46)
        // is not a verdict to drop a notification on; on a real phone this happened about twice a day.
        val chatChance = verdict.getOrNull()?.answers?.obj("route")?.obj("probabilities")?.dbl(Route.CHAT) ?: 0.0
        val closeCall = finalRoute == Route.IGNORE && chatChance >= CLOSE_CALL
        if (finalRoute == Route.REVIEW || closeCall) {
            val second = onSecondJudge?.let { judgeFn -> runCatching { judgeFn(event) }.getOrNull() }
            finalRoute = second?.first ?: Route.IGNORE
            note = (if (closeCall) "JEV 判忽略但 chat 概率 ${"%.2f".format(chatChance)}，交主模型复核：" else "") + (second?.second ?: "二判不可用，按忽略处理")
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
        private const val CLOSE_CALL = 0.40
        private const val RESTART_MARGIN_MS = 60_000L
        private const val REPOST_GRACE_MS = 3_000L
        private const val REPOSTED = "没有新消息，只是被 App 重新贴出"
        private val SYSTEM_NOISE = setOf("android", "com.android.systemui")
        // "call" is the ringing or ongoing call itself: by the time a model has looked at it, it is over. Missed calls
        // arrive under their own category and still go through.
        private val NOISY_CATEGORIES = setOf("transport", "progress", "service", "sys", "navigation", "stopwatch", "workout", "call")
    }
}
