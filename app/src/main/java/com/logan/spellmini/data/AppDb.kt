package com.logan.spellmini.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Insert suspend fun insert(event: NotifEvent): Long
    @Update suspend fun update(event: NotifEvent)

    @Query("SELECT * FROM events WHERE id = :id") suspend fun get(id: Long): NotifEvent?

    @Query("SELECT * FROM events WHERE status != 'SEEN' ORDER BY postedAt DESC, id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<NotifEvent>>

    /** The notification behind a key, as it last went through the pipeline. */
    @Query("SELECT * FROM events WHERE sbnKey = :key AND status IN ('QUEUED', 'JUDGED', 'ERROR') ORDER BY postedAt DESC, id DESC LIMIT 1")
    suspend fun latestByKey(key: String): NotifEvent?

    /** The first thing the user did about a notification after it arrived, if anything; filterReason holds a [Handled] value. */
    @Query("SELECT * FROM events WHERE sbnKey = :key AND status = 'SEEN' AND postedAt >= :since ORDER BY postedAt ASC, id ASC LIMIT 1")
    suspend fun handledSince(key: String, since: Long): NotifEvent?

    @Query("SELECT * FROM events WHERE status = 'SEEN' AND postedAt >= :since")
    suspend fun seenSince(since: Long): List<NotifEvent>

    @Query("SELECT * FROM events WHERE status = 'TASK' AND postedAt >= :since")
    suspend fun taskRunsSince(since: Long): List<NotifEvent>

    /** Plain substring search over everything the listener has seen; the assistant's own rows (tasks, topics) are left out. */
    @Query(
        "SELECT * FROM events WHERE status IN ('JUDGED', 'FILTERED', 'ERROR', 'APP_OFF') AND postedAt >= :since " +
            "AND (title LIKE :pattern OR text LIKE :pattern OR appName LIKE :pattern) ORDER BY postedAt DESC LIMIT :limit"
    )
    suspend fun search(pattern: String, since: Long, limit: Int): List<NotifEvent>

    @Query("SELECT * FROM events WHERE outcome = :outcome AND postedAt >= :since ORDER BY postedAt DESC LIMIT :limit")
    suspend fun withOutcomeSince(outcome: String, since: Long, limit: Int): List<NotifEvent>

    /** What the phone itself received. Items of subscribed sources say nothing about his life, so the profile and the open loops never read them. */
    @Query("SELECT * FROM events WHERE status = 'JUDGED' AND pkg NOT LIKE 'feed.%' AND pkg NOT LIKE 'signal.%' ORDER BY postedAt DESC LIMIT :limit")
    suspend fun recentJudged(limit: Int): List<NotifEvent>

    /** Same-source context handed to JEV so it can spot repeats and updates. */
    @Query(
        "SELECT * FROM events WHERE pkg = :pkg AND status = 'JUDGED' AND postedAt >= :since AND id != :excludeId " +
            "ORDER BY postedAt DESC LIMIT :limit"
    )
    suspend fun recentFromApp(pkg: String, since: Long, excludeId: Long, limit: Int): List<NotifEvent>

    /** How often a conversation (same app, same title) reached the phone: the denominator of "how much does he care". */
    @Query("SELECT COUNT(*) FROM events WHERE pkg = :pkg AND title = :title AND status IN ('JUDGED', 'FILTERED', 'ERROR') AND postedAt >= :since")
    suspend fun countConversation(pkg: String, title: String, since: Long): Int

    /** What he did about that conversation's notifications: one [Handled] value per reaction. */
    @Query("SELECT filterReason FROM events WHERE pkg = :pkg AND title = :title AND status = 'SEEN' AND postedAt >= :since")
    suspend fun reactions(pkg: String, title: String, since: Long): List<String>

    /** Everything that arrived under the same name lately, from any app and whatever became of it: someone trying several channels. */
    @Query("SELECT * FROM events WHERE title = :title AND postedAt >= :since AND id != :excludeId AND status != 'SEEN' ORDER BY postedAt DESC LIMIT 20")
    suspend fun sameNameSince(title: String, since: Long, excludeId: Long): List<NotifEvent>

    @Query("SELECT COUNT(*) FROM events WHERE pkg = :pkg AND postedAt >= :since")
    suspend fun countByPkgSince(pkg: String, since: Long): Int

    /** For tests: forgetting a moment's rows also resets its cooldown and its count for the day. */
    @Query("DELETE FROM events WHERE pkg = :pkg") suspend fun deleteByPkg(pkg: String): Int

    @Query("SELECT MAX(postedAt) FROM events WHERE pkg = :pkg")
    suspend fun lastAtByPkg(pkg: String): Long?

    @Query("SELECT COUNT(*) FROM events WHERE pkg = :pkg AND title = :title AND text = :text AND postedAt >= :since")
    suspend fun countSame(pkg: String, title: String, text: String, since: Long): Int

    /** When a conversation (same app, same title) last went through the pipeline; null if it never did. */
    @Query("SELECT MAX(postedAt) FROM events WHERE pkg = :pkg AND title = :title AND status IN ('QUEUED', 'JUDGED', 'ERROR')")
    suspend fun lastHandledAt(pkg: String, title: String): Long?

    @Query("SELECT COUNT(*) FROM events WHERE outcome = :outcome AND postedAt >= :since")
    suspend fun countOutcomeSince(outcome: String, since: Long): Int

    @Query("SELECT COUNT(*) FROM events WHERE outcome = :outcome AND postedAt >= :since AND status != 'INTEREST' AND pkg NOT LIKE 'feed.%'")
    suspend fun countNotificationOutcomeSince(outcome: String, since: Long): Int

    /** Titles of subscribed items already on their way to him as a card or a message, newest first: the repeat check reads them. */
    @Query("SELECT title FROM events WHERE pkg LIKE 'feed.%' AND finalRoute IN ('feed', 'chat') AND postedAt >= :since AND id != :excludeId ORDER BY id DESC LIMIT :limit")
    suspend fun itemTitlesRouted(since: Long, excludeId: Long, limit: Int): List<String>

    /** Items of subscribed sources (their pkg starts with `feed.`) have a cap of their own. */
    @Query("SELECT COUNT(*) FROM events WHERE outcome = :outcome AND postedAt >= :since AND pkg LIKE 'feed.%'")
    suspend fun countItemOutcomeSince(outcome: String, since: Long): Int

    @Query("SELECT COUNT(*) FROM events WHERE outcome = :outcome AND postedAt >= :since AND status = 'INTEREST'")
    suspend fun countInterestOutcomeSince(outcome: String, since: Long): Int

    /** Topics the interest patrol proposed recently, newest first: the memory that keeps batches from repeating. */
    @Query("SELECT * FROM events WHERE status = 'INTEREST' AND postedAt >= :since ORDER BY postedAt DESC LIMIT 60")
    suspend fun recentInterestTopics(since: Long): List<NotifEvent>

    @Query("SELECT COUNT(*) FROM events WHERE status = 'JUDGED' AND id > :afterId AND pkg NOT LIKE 'feed.%' AND pkg NOT LIKE 'signal.%'")
    suspend fun countJudgedAfter(afterId: Long): Int

    @Query("SELECT MAX(id) FROM events") suspend fun maxId(): Long?

    @Query("SELECT * FROM events WHERE outcome = 'HELD' ORDER BY postedAt ASC LIMIT :limit") suspend fun held(limit: Int): List<NotifEvent>
    @Query("SELECT MIN(postedAt) FROM events WHERE outcome = 'HELD'") suspend fun oldestHeldAt(): Long?
    @Query("SELECT COUNT(*) FROM events WHERE outcome = 'HELD'") suspend fun countHeld(): Int

    @Query("SELECT * FROM events ORDER BY id ASC") suspend fun all(): List<NotifEvent>

    @Query("SELECT COUNT(*) FROM events WHERE status != 'SEEN'") fun total(): Flow<Int>

    @Query("SELECT * FROM events WHERE status = 'QUEUED'") suspend fun queued(): List<NotifEvent>

    @Query("SELECT * FROM events WHERE outcome = 'PENDING'") suspend fun unfinished(): List<NotifEvent>

    @Query("DELETE FROM events WHERE postedAt < :before") suspend fun prune(before: Long): Int
}

@Dao
interface MessageDao {
    @Insert suspend fun insert(msg: ChatMsg): Long
    @Update suspend fun update(msg: ChatMsg)

    @Query("SELECT * FROM messages WHERE id = :id") suspend fun get(id: Long): ChatMsg?

    @Query("SELECT * FROM messages ORDER BY createdAt ASC, id ASC")
    fun all(): Flow<List<ChatMsg>>

    @Query("SELECT * FROM (SELECT * FROM messages ORDER BY createdAt DESC, id DESC LIMIT :limit) ORDER BY createdAt ASC, id ASC")
    suspend fun lastN(limit: Int): List<ChatMsg>

    @Query("SELECT * FROM messages WHERE kind = 'card' ORDER BY id DESC LIMIT :limit")
    suspend fun recentCards(limit: Int): List<ChatMsg>

    /** Everything the assistant did or proposed to do: confirm cards plus the notes left by directly executed actions. */
    @Query("SELECT * FROM messages WHERE cardJson IS NOT NULL AND kind IN ('card', 'note') ORDER BY id DESC LIMIT :limit")
    suspend fun recentActions(limit: Int): List<ChatMsg>

    @Query("UPDATE messages SET cardJson = :json WHERE id = :id")
    suspend fun setAttachments(id: Long, json: String)

    /** Messages the assistant sent on its own about a notification. */
    @Query("SELECT * FROM messages WHERE eventId IS NOT NULL AND sourceLabel IS NOT NULL AND kind = 'text' AND createdAt >= :since")
    suspend fun proactiveSince(since: Long): List<ChatMsg>

    @Query("UPDATE messages SET text = :text, streaming = :streaming WHERE id = :id")
    suspend fun setText(id: Long, text: String, streaming: Boolean)

    @Query("UPDATE messages SET cardState = :state WHERE id = :id")
    suspend fun setCardState(id: Long, state: String)

    @Query("DELETE FROM messages WHERE id = :id") suspend fun delete(id: Long)

    @Query("UPDATE messages SET streaming = 0 WHERE streaming = 1") suspend fun clearStaleStreaming()

    @Query("DELETE FROM messages") suspend fun clear()

    @Query("SELECT * FROM messages ORDER BY id ASC") suspend fun everything(): List<ChatMsg>
}

@Dao
interface FeedDao {
    @Insert suspend fun insert(card: FeedCard): Long

    @Query("SELECT * FROM feed WHERE dismissed = 0 ORDER BY createdAt DESC, id DESC")
    fun visible(): Flow<List<FeedCard>>

    @Query("SELECT * FROM feed WHERE id = :id") suspend fun get(id: Long): FeedCard?

    @Query("SELECT * FROM feed ORDER BY id ASC") suspend fun everything(): List<FeedCard>

    @Query("SELECT title FROM feed ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentTitles(limit: Int): List<String>

    /** Includes cards he removed: something he did not want once should not come back reworded. */
    @Query("SELECT * FROM feed ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentForDedupe(limit: Int): List<FeedCard>

    @Query("SELECT sourcesJson FROM feed ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentSources(limit: Int): List<String>

    @Query("SELECT title FROM feed WHERE liked = 1 ORDER BY createdAt DESC LIMIT :limit")
    suspend fun likedTitles(limit: Int): List<String>

    @Query("SELECT title FROM feed WHERE dismissed = 1 ORDER BY createdAt DESC LIMIT :limit")
    suspend fun dismissedTitles(limit: Int): List<String>

    @Query("UPDATE feed SET liked = :liked WHERE id = :id") suspend fun setLiked(id: Long, liked: Boolean)
    @Query("UPDATE feed SET dismissed = 1 WHERE id = :id") suspend fun dismiss(id: Long)

    @Query("SELECT * FROM feed WHERE createdAt >= :since ORDER BY createdAt DESC") suspend fun since(since: Long): List<FeedCard>
    @Query("UPDATE feed SET bulletsJson = :bullets, sourcesJson = :sources WHERE id = :id") suspend fun extend(id: Long, bullets: String, sources: String)
}

@Dao
interface MemoryDao {
    @Insert suspend fun insert(entry: MemoryEntry): Long

    @Query("SELECT * FROM memory ORDER BY updatedAt DESC") fun all(): Flow<List<MemoryEntry>>
    @Query("SELECT * FROM memory ORDER BY updatedAt DESC") suspend fun list(): List<MemoryEntry>

    @Query("UPDATE memory SET text = :text, updatedAt = :now WHERE id = :id")
    suspend fun setText(id: Long, text: String, now: Long)

    @Query("DELETE FROM memory WHERE id = :id") suspend fun delete(id: Long)

    /** Topics the user asked to keep an eye on are stored as memory entries with this source. */
    @Query("SELECT * FROM memory WHERE source = :source ORDER BY updatedAt DESC") suspend fun bySource(source: String): List<MemoryEntry>
    @Query("SELECT * FROM memory WHERE source = :source ORDER BY updatedAt DESC") fun watchBySource(source: String): Flow<List<MemoryEntry>>

    @Insert suspend fun log(entry: ProfileLog): Long
    @Query("SELECT * FROM profile_log ORDER BY time DESC LIMIT 30") fun logs(): Flow<List<ProfileLog>>

    @Query("SELECT * FROM profile_log ORDER BY time ASC") suspend fun allLogs(): List<ProfileLog>
}

@Dao
interface AppRuleDao {
    @Query("SELECT * FROM app_rules WHERE pkg = :pkg") suspend fun get(pkg: String): AppRule?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(rule: AppRule)
    @Query("SELECT * FROM app_rules ORDER BY count DESC") fun all(): Flow<List<AppRule>>

    @Query("SELECT * FROM app_rules ORDER BY count DESC") suspend fun list(): List<AppRule>
    @Query("UPDATE app_rules SET enabled = :enabled WHERE pkg = :pkg") suspend fun setEnabled(pkg: String, enabled: Boolean)
}

@Database(
    entities = [NotifEvent::class, ChatMsg::class, FeedCard::class, MemoryEntry::class, ProfileLog::class, AppRule::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun events(): EventDao
    abstract fun messages(): MessageDao
    abstract fun feed(): FeedDao
    abstract fun memory(): MemoryDao
    abstract fun appRules(): AppRuleDao

    companion object {
        fun create(context: Context): AppDb =
            Room.databaseBuilder(context, AppDb::class.java, "spellmini.db")
                // Demo-only: a schema change wipes local data instead of migrating it.
                .fallbackToDestructiveMigration()
                .build()
    }
}
