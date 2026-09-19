package com.logan.spellmini.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.logan.spellmini.Graph

/**
 * Lets tests drive the app with real Chinese text, which `adb shell input text` cannot type:
 *   am broadcast -a com.logan.spellmini.DEBUG_SEND   -p com.logan.spellmini --es text '你打 10086'
 *   am broadcast -a com.logan.spellmini.DEBUG_NOTIFY -p com.logan.spellmini --es app 微信 --es title 老周 --es text '…'
 * Both go through exactly the same code as the composer and the listener.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text").orEmpty()
        when (intent.action) {
            ACTION_SEND -> if (text.isNotBlank()) Graph.chat.send(text)
            ACTION_NOTIFY -> Graph.pipeline.simulate(
                intent.getStringExtra("app").orEmpty().ifBlank { "模拟" }, intent.getStringExtra("title").orEmpty(), text,
            )
        }
        Log.i("SpellDebug", "${intent.action} accepted (${text.length} chars)")
    }

    companion object {
        const val ACTION_SEND = "com.logan.spellmini.DEBUG_SEND"
        const val ACTION_NOTIFY = "com.logan.spellmini.DEBUG_NOTIFY"
    }
}
