package com.logan.spellmini.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Lifecycle of one notification inside the pipeline. */
object EventStatus {
    const val FILTERED = "FILTERED"   // dropped by local rules, never sent anywhere
    const val APP_OFF = "APP_OFF"     // user switched this app off, never sent anywhere
    const val QUEUED = "QUEUED"       // waiting for the quiet window / JEV
    const val JUDGED = "JUDGED"
    const val ERROR = "ERROR"
    const val INTEREST = "INTEREST"   // not a notification: one topic of a profile-driven feed run, logged for the trace
    const val SEEN = "SEEN"           // not a notification either: the user dealt with one (see [Handled]); filterReason says how
    const val TASK = "TASK"           // one run of a task or a job, logged so its cost and its silences are as visible as a notification's
}

/**
 * How the user dealt with a notification on his own. Recorded as its own row (status SEEN, same sbnKey) so nothing in
 * the schema changes. It is what lets the assistant stay quiet about things he has already seen, and it is the cheapest
 * relevance label there is: what he opened although it was judged "ignore", what he swiped away although it was raised.
 */
object Handled {
    const val OPENED = "opened"           // tapped the notification
    const val DISMISSED = "dismissed"     // swiped it away
    const val READ_IN_APP = "read_in_app" // the app withdrew it, which chat apps do once the conversation was read
    const val REPLIED = "replied"         // the conversation came back with his own message on top

    /** He has seen the content itself, not just the banner. */
    fun knowsContent(how: String?) = how == OPENED || how == READ_IN_APP || how == REPLIED

    fun label(how: String?) = when (how) {
        OPENED -> "你点开了原通知"
        DISMISSED -> "你划掉了原通知"
        // The app took its notification back. Chat apps do that once the conversation was read, here or on another
        // device, but it is an inference, and the wording says so.
        READ_IN_APP -> "原通知被 App 收回了，多半是你已经看过"
        REPLIED -> "你已经回复了"
        else -> ""
    }
}

object Route {
    const val CHAT = "chat"
    const val FEED = "feed"
    const val IGNORE = "ignore"
    const val REVIEW = "review"
}

/** What finally happened downstream of the JEV verdict. */
object Outcome {
    const val PENDING = "PENDING"   // verdict is in, the chat turn or feed card is still running
    const val CHAT_SENT = "CHAT_SENT"
    const val CHAT_SILENT = "CHAT_SILENT"
    const val FEED_CARD = "FEED_CARD"
    const val FEED_SKIPPED = "FEED_SKIPPED"
    const val CAPPED = "CAPPED"
    const val ERROR = "ERROR"
    const val NONE = "NONE"
}

/** One row per (merged) notification: the trace the user reviews to judge JEV quality. */
@Entity(tableName = "events", indices = [Index("postedAt"), Index("status")])
data class NotifEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sbnKey: String,
    val pkg: String,
    val appName: String,
    val title: String,
    val text: String,
    val category: String? = null,
    val postedAt: Long,
    val mergedCount: Int = 1,
    val synthetic: Boolean = false,
    val status: String,
    val filterReason: String? = null,
    val route: String? = null,
    val routeProbs: String? = null,
    val confidence: Double? = null,
    val urgency: Double? = null,
    val jevModel: String? = null,
    val jevSource: String? = null,
    val jevLatencyMs: Long? = null,
    val jevCostUsd: Double? = null,
    val jevError: String? = null,
    val criteriaVersion: String? = null,
    /** Route after the second judge resolved a `review` verdict; equals [route] otherwise. */
    val finalRoute: String? = null,
    val secondJudgeNote: String? = null,
    val outcome: String? = null,
    val outcomeNote: String? = null,
    val outcomeRefId: Long? = null,
    val downstreamCostUsd: Double? = null,
    val downstreamLatencyMs: Long? = null,
)

object MsgRole {
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

object MsgKind {
    const val TEXT = "text"
    const val CARD = "card"   // confirm card for a write action
    const val NOTE = "note"   // small system-style line: "已记住…", "已打开日历"
}

object CardState {
    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val DENIED = "denied"
    const val FAILED = "failed"

    // On a note row: the action ran directly, with no card. UNDONE means the user took it back afterwards.
    const val DONE = "done"
    const val UNDONE = "undone"
}

/** Values of [MemoryEntry.source] that carry meaning beyond "where this came from". */
object MemorySource {
    const val CHAT = "聊天"

    /** A topic the user asked the feed to keep following. Not a fact about the user, so the profile agent leaves it alone. */
    const val FOLLOW = "关注"

    /** What to bring up or leave out, distilled from his thumbs on proactive messages and from "should have told me". */
    const val RULE = "规则"

    /** A [Task] (recurring job, watch, open loop) kept as JSON. Never shown as a fact and never sent with the profile. */
    const val TASK = "任务"

    /** Sources that are instructions or bookkeeping rather than facts about the user. */
    val NOT_FACTS = setOf(FOLLOW, RULE, TASK)
}

@Entity(tableName = "messages", indices = [Index("createdAt")])
data class ChatMsg(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val kind: String = MsgKind.TEXT,
    val text: String,
    val createdAt: Long,
    /** Set when this message was triggered by a notification. */
    val eventId: Long? = null,
    val sourceLabel: String? = null,
    /** For cards: {"tool": "...", "args": {...}} */
    val cardJson: String? = null,
    val cardState: String? = null,
    val streaming: Boolean = false,
)

@Entity(tableName = "feed", indices = [Index("createdAt")])
data class FeedCard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: Long? = null,
    val emoji: String,
    val title: String,
    val body: String,
    val bulletsJson: String = "[]",
    val reason: String = "",
    /** [{"title": "...", "url": "..."}] */
    val sourcesJson: String = "[]",
    /** ["https://..."] */
    val imagesJson: String = "[]",
    val sourceLabel: String = "",
    val createdAt: Long,
    val liked: Boolean = false,
    val dismissed: Boolean = false,
)

/** "模型记的": profile facts the model maintains. The user-authored profile lives in Settings and is never touched. */
@Entity(tableName = "memory")
data class MemoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "profile_log")
data class ProfileLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val summary: String,
)

/** Per-app switch. Every app defaults to enabled (user decision: send everything). */
@Entity(tableName = "app_rules")
data class AppRule(
    @PrimaryKey val pkg: String,
    val appName: String,
    val enabled: Boolean = true,
    val count: Int = 0,
    val lastSeen: Long = 0,
)
