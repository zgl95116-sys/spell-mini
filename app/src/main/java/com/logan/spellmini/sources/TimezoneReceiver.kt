package com.logan.spellmini.sources

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.logan.spellmini.Graph
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * The phone's time zone changing is the cheapest "you have landed" signal there is: no location permission, no
 * polling. The assistant answers it with one arrival note (time difference, money, getting into town, weather).
 */
class TimezoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        val now = TimeZone.getDefault().id
        val before = Graph.settings.lastTimezone
        Graph.settings.lastTimezone = now
        // Same offset (or the very first reading) is not a journey.
        if (before.isBlank() || before == now || TimeZone.getTimeZone(before).rawOffset == TimeZone.getTimeZone(now).rawOffset) return
        Graph.scope.launch { Graph.chat.onArrival(before, now) }
    }
}
