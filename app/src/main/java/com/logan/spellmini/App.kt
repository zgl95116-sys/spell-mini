package com.logan.spellmini

import android.app.Application
import com.logan.spellmini.agent.ChatAgent
import com.logan.spellmini.agent.FeedAgent
import com.logan.spellmini.agent.ProfileAgent
import com.logan.spellmini.data.AppDb
import com.logan.spellmini.data.ChatMsg
import com.logan.spellmini.data.MsgRole
import com.logan.spellmini.data.Settings
import com.logan.spellmini.net.OpenRouter
import com.logan.spellmini.pipeline.Pipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}

/** Process-wide singletons. A demo-sized service locator instead of a DI framework. */
object Graph {
    lateinit var app: Application
        private set
    lateinit var db: AppDb
        private set
    lateinit var settings: Settings
        private set
    lateinit var api: OpenRouter
        private set
    lateinit var pipeline: Pipeline
        private set
    lateinit var chat: ChatAgent
        private set
    lateinit var feed: FeedAgent
        private set
    lateinit var profile: ProfileAgent
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val listenerConnected = MutableStateFlow(false)

    /** True while the chat tab is on screen; proactive messages then skip the system notification. */
    val chatOnScreen = MutableStateFlow(false)

    fun init(application: Application) {
        app = application
        db = AppDb.create(application)
        settings = Settings(application)
        api = OpenRouter(settings)
        chat = ChatAgent(application, db, api, scope, ::profileText)
        feed = FeedAgent(db, settings, api, ::profileText)
        profile = ProfileAgent(db, settings, api)
        pipeline = Pipeline(db, settings, api, scope, ::profileText).apply {
            onChat = chat::onTrigger
            onFeed = feed::generate
            onSecondJudge = chat::secondJudge
            afterJudged = profile::maybeAutoRun
        }
        pipeline.recoverOnStart()
        // Profile-driven feed. A plain loop is enough: the notification listener keeps this process alive, and a
        // missed tick (deep sleep) simply runs on the next wake-up.
        scope.launch {
            delay(STARTUP_GRACE_MS)
            while (true) {
                feed.refreshIfDue()
                delay(SCHEDULER_TICK_MS)
            }
        }
        scope.launch {
            db.messages().clearStaleStreaming()
            greetOnce()
        }
    }

    /** The fixed three-line opening from the product doc, adapted to point the user at the two setup steps. */
    private suspend fun greetOnce() {
        if (settings.greeted) return
        settings.greeted = true
        val now = System.currentTimeMillis()
        listOf(
            "你好，我是你的个人助手 Spell。",
            "我会留意你手机上的通知：要紧的事及时告诉你，能帮上忙的直接帮你办；你可能感兴趣的，我会查一查，整理到 Feed 里。",
            "先点右上角的球，在「设置」里开一下通知使用权；再到「画像」里跟我说说你自己，我会判断得更准。",
        ).forEachIndexed { index, line ->
            db.messages().insert(ChatMsg(role = MsgRole.ASSISTANT, text = line, createdAt = now + index))
        }
    }

    /** Both profile columns, bounded so the JEV state stays small. */
    suspend fun profileText(): String {
        val mine = settings.userProfile.trim()
        val learned = db.memory().list().joinToString("\n") { "- " + it.text }
        return listOf(
            mine.takeIf { it.isNotBlank() }?.let { "[written by the user]\n$it" },
            learned.takeIf { it.isNotBlank() }?.let { "[learned by the assistant]\n$it" },
        ).filterNotNull().joinToString("\n\n").take(PROFILE_BUDGET)
    }

    private const val PROFILE_BUDGET = 1_800
    private const val STARTUP_GRACE_MS = 30_000L
    private const val SCHEDULER_TICK_MS = 5 * 60_000L
}
