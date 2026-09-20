package com.logan.spellmini.sources

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.logan.spellmini.Graph
import com.logan.spellmini.notify.SpellListenerService

/**
 * What is playing: song, podcast, video title. A notification listener may read media sessions without any further
 * permission, and it is an interest signal the notification stream lacks (nobody gets a notification for the podcast
 * they chose themselves). Only titles are kept, a short list, and only for the feed's topic planning.
 */
object MediaSignals {
    private const val TAG = "SpellMedia"
    private val main = Handler(Looper.getMainLooper())
    private val watched = HashMap<String, MediaController.Callback>()

    fun attach(context: Context) {
        if (!Graph.settings.mediaSignalEnabled) return
        runCatching {
            val manager = context.getSystemService(MediaSessionManager::class.java)
            val me = ComponentName(context, SpellListenerService::class.java)
            val onChange = MediaSessionManager.OnActiveSessionsChangedListener { controllers -> watch(controllers.orEmpty()) }
            manager.addOnActiveSessionsChangedListener(onChange, me, main)
            watch(manager.getActiveSessions(me))
        }.onFailure { Log.w(TAG, "media sessions unavailable", it) }
    }

    private fun watch(controllers: List<MediaController>) {
        controllers.forEach { controller ->
            note(controller)
            if (watched.containsKey(controller.packageName)) return@forEach
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = note(controller)
                override fun onPlaybackStateChanged(state: PlaybackState?) = note(controller)
            }
            watched[controller.packageName] = callback
            runCatching { controller.registerCallback(callback, main) }
        }
    }

    private fun note(controller: MediaController) {
        if (controller.playbackState?.state != PlaybackState.STATE_PLAYING) return
        val data = controller.metadata ?: return
        val title = data.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty().ifBlank { return }
        val by = (data.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: data.getString(MediaMetadata.METADATA_KEY_ALBUM)).orEmpty().trim()
        val app = runCatching {
            val pm = Graph.app.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(controller.packageName, 0)).toString()
        }.getOrDefault(controller.packageName)
        Graph.settings.rememberMedia("$app：$title" + if (by.isNotBlank()) " — $by" else "")
    }
}
