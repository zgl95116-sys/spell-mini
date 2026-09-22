package com.logan.spellmini.agent

import android.util.Log
import com.logan.spellmini.data.AppDb
import com.logan.spellmini.signals.NowContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Notifications that can wait are not delivered one by one on a silent channel, where he finds twelve banners an hour
 * later; the pipeline holds them (outcome HELD) and they are told together, in one message, at the next natural
 * break: a meeting ended, he picked the phone up after a while, he got home or to work, a call ended. Failing all
 * that, a pile that has waited an hour goes out as soon as he is on the phone, and a pile two hours old goes out
 * regardless. One real day made the case: 165 messages judged "can wait", 61 more cut by the hourly cap, an "arrived
 * at work" among the cut, and him saying the assistant felt quiet.
 */
class Digest(
    private val db: AppDb,
    private val chat: ChatAgent,
    private val now: NowContext,
    private val scope: CoroutineScope,
) {
    private val flushing = Mutex()

    /** A break point announced by the phone. Fire-and-forget: the caller is usually a broadcast receiver. */
    fun flush(reason: String) {
        scope.launch { runCatching { flushNow(reason) }.onFailure { Log.w(TAG, "digest failed", it) } }
    }

    /**
     * Tells him what is held, unless nothing is, or he is still occupied (the meeting that ended was not the only one)
     * and the pile is not yet stale. Returns whether a digest went out.
     */
    suspend fun flushNow(reason: String, force: Boolean = false): Boolean = flushing.withLock {
        val held = db.events().held(MAX_ITEMS)
        if (held.isEmpty()) return false
        val age = System.currentTimeMillis() - held.minOf { it.postedAt }
        if (!force && age < STALE_MS && now.occupied()) {
            Log.i(TAG, "break ($reason) but still occupied; ${held.size} held")
            return false
        }
        chat.onDigest(held, reason) != null
    }

    /** From the scheduler tick, for the days without a clean break. */
    suspend fun tick() {
        val oldest = db.events().oldestHeldAt() ?: return
        val age = System.currentTimeMillis() - oldest
        when {
            age >= STALE_MS -> flushNow("攒了两个小时", force = true)
            age >= HOUR_MS && now.inUse() -> flushNow("攒了一个小时")
        }
    }

    companion object {
        private const val TAG = "SpellDigest"
        private const val HOUR_MS = 3_600_000L
        private const val STALE_MS = 2 * HOUR_MS

        /** More than this in one breath is not a briefing; the rest waits for the next one. */
        const val MAX_ITEMS = 12
    }
}
