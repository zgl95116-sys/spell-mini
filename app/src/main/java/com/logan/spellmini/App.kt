package com.logan.spellmini

import android.app.Application
import com.logan.spellmini.agent.ChatAgent
import com.logan.spellmini.agent.FeedAgent
import com.logan.spellmini.agent.ProfileAgent
import com.logan.spellmini.data.AppDb
import com.logan.spellmini.data.ChatMsg
import com.logan.spellmini.data.MemorySource
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

    /** True while our activity is resumed. Android only lets a foreground app start another app's screen. */
    val appInForeground = MutableStateFlow(false)

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
            quietOtherAssistantsOnce()
            chat.rearmTimers()
        }
    }

    /**
     * Another assistant's notifications are its own summaries of things this app already sees first-hand. On a real
     * phone they were a third of all traffic and half of the notification-driven feed cards, all second-hand. They
     * start switched off; the per-app switch in settings turns them back on.
     */
    private suspend fun quietOtherAssistantsOnce() {
        if (settings.assistantAppsQuieted) return
        settings.assistantAppsQuieted = true
        OTHER_ASSISTANTS.forEach { pkg -> db.appRules().get(pkg)?.let { db.appRules().setEnabled(pkg, false) } }
    }

    /** Packages that start switched off the first time they are seen; listed at build time as QUIET_PACKAGES. */
    val OTHER_ASSISTANTS: Set<String> = BuildConfig.QUIET_PACKAGES.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

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

    /**
     * Everything the models know about the user: what he wrote, what was learned, and what he asked the feed to follow.
     * Each part has its own budget. One shared cap used to cut his own text at 1,800 characters (he had written 3,100)
     * and left no room at all for the learned entries.
     */
    suspend fun profileText(): String {
        val mine = maskSecrets(settings.userProfile.trim()).take(OWN_BUDGET)
        val entries = db.memory().list()
        val learned = entries.filter { it.source != MemorySource.FOLLOW }.joinToString("\n") { "- " + it.text }.take(LEARNED_BUDGET)
        val follows = entries.filter { it.source == MemorySource.FOLLOW }.joinToString("\n") { "- " + it.text }
        return listOf(
            mine.takeIf { it.isNotBlank() }?.let { "[written by the user]\n$it" },
            learned.takeIf { it.isNotBlank() }?.let { "[learned by the assistant]\n${maskSecrets(it)}" },
            follows.takeIf { it.isNotBlank() }?.let { "[topics the user asked to keep following]\n$it" },
        ).filterNotNull().joinToString("\n\n")
    }

    /**
     * The profile rides along with every model call. Identity and bank-card numbers have no use there, so they are
     * blanked on the way out; what the user typed stays untouched on the phone.
     */
    private fun maskSecrets(text: String): String = text
        .replace(ID_NUMBER, "〔证件号已隐去〕")
        .replace(CARD_NUMBER, "〔卡号已隐去〕")

    private val ID_NUMBER = Regex("(?<!\\d)\\d{17}[\\dXx](?!\\d)")
    private val CARD_NUMBER = Regex("(?<!\\d)\\d{15,19}(?!\\d)")
    private const val OWN_BUDGET = 4_000
    private const val LEARNED_BUDGET = 1_500
    private const val STARTUP_GRACE_MS = 30_000L
    private const val SCHEDULER_TICK_MS = 5 * 60_000L
}
