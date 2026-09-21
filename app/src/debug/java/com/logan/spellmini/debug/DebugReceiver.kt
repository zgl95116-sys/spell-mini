package com.logan.spellmini.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.logan.spellmini.Graph
import kotlinx.coroutines.launch

/**
 * Lets tests drive the app with real Chinese text, which `adb shell input text` cannot type:
 *   am broadcast -a com.logan.spellmini.DEBUG_SEND   -p com.logan.spellmini --es text '你打 10086'
 *   am broadcast -a com.logan.spellmini.DEBUG_NOTIFY -p com.logan.spellmini --es app 微信 --es title 老周 --es text '…'
 *   am broadcast -a com.logan.spellmini.DEBUG_PROFILE -p com.logan.spellmini --es text '我是…，最近在关注…'
 *   am broadcast -a com.logan.spellmini.DEBUG_LOOPS   -p com.logan.spellmini
 * The first two go through exactly the same code as the composer and the listener; the third fills in the
 * user-written profile, which a fresh test install would otherwise lack; the last runs the open-loops pass now
 * instead of waiting three hours for it.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text").orEmpty()
        when (intent.action) {
            ACTION_SEND -> if (text.isNotBlank()) Graph.chat.send(text)
            ACTION_PROFILE -> if (text.isNotBlank()) Graph.settings.userProfile = text
            // A share started from adb cannot grant read access to a gallery picture, so tests hand over a file that
            // is already inside the app's own cache. Everything after the copy is the same code as a real share.
            ACTION_SHARE_IMAGE -> {
                Graph.pendingShare.value = com.logan.spellmini.share.Shared(text.ifBlank { null }, intent.getStringExtra("path"))
                context.startActivity(
                    Intent(context, com.logan.spellmini.MainActivity::class.java).putExtra(com.logan.spellmini.MainActivity.EXTRA_OPEN_CHAT, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
            // An item of a subscribed source without waiting for a real feed to publish one: same entry the poller uses.
            ACTION_ITEM -> Graph.scope.launch {
                val source = intent.getStringExtra("source").orEmpty().ifBlank { "测试源" }
                val link = intent.getStringExtra("link").orEmpty().ifBlank { "https://example.com/" + System.nanoTime() }
                Graph.pipeline.ingestItem(
                    pkg = com.logan.spellmini.sources.Subscriptions.PKG_PREFIX + "debug", appName = com.logan.spellmini.sources.Subscriptions.LABEL + source,
                    key = com.logan.spellmini.sources.Subscriptions.keyFor(0, link), title = intent.getStringExtra("title").orEmpty(), text = text, category = null,
                )
            }
            ACTION_POLL -> Graph.scope.launch { Log.i("SpellDebug", "poll: " + runCatching { Graph.sources.pollNow() }.getOrElse { it.message }) }
            ACTION_LOOPS -> Graph.scope.launch { runCatching { Graph.loops.runNow() }.onFailure { Log.w("SpellDebug", "loop pass failed", it) } }
            ACTION_NOTIFY -> Graph.pipeline.simulate(
                intent.getStringExtra("app").orEmpty().ifBlank { "模拟" }, intent.getStringExtra("title").orEmpty(), text,
            )
        }
        Log.i("SpellDebug", "${intent.action} accepted (${text.length} chars)")
    }

    companion object {
        const val ACTION_SEND = "com.logan.spellmini.DEBUG_SEND"
        const val ACTION_NOTIFY = "com.logan.spellmini.DEBUG_NOTIFY"
        const val ACTION_PROFILE = "com.logan.spellmini.DEBUG_PROFILE"
        const val ACTION_LOOPS = "com.logan.spellmini.DEBUG_LOOPS"
        const val ACTION_SHARE_IMAGE = "com.logan.spellmini.DEBUG_SHARE_IMAGE"
        const val ACTION_ITEM = "com.logan.spellmini.DEBUG_ITEM"
        const val ACTION_POLL = "com.logan.spellmini.DEBUG_POLL"
    }
}
