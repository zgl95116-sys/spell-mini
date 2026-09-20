package com.logan.spellmini.sources

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class CalendarEvent(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val location: String)

/** Read-only view of the phone's calendars. Everything degrades to "no access" when the permission was not granted. */
object CalendarSource {
    fun allowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun between(context: Context, from: Long, to: Long, limit: Int = 40): List<CalendarEvent> {
        if (!allowed(context)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        val columns = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
        )
        return runCatching {
            context.contentResolver.query(uri, columns, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cursor ->
                buildList {
                    while (cursor.moveToNext() && size < limit) {
                        add(CalendarEvent(cursor.getString(0).orEmpty(), cursor.getLong(1), cursor.getLong(2), cursor.getInt(3) == 1, cursor.getString(4).orEmpty()))
                    }
                }
            }
        }.getOrNull().orEmpty()
    }

    private val day = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)
    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

    /** One line per event, with the date written out so the model never has to work out a weekday. */
    fun describe(events: List<CalendarEvent>): String = events.joinToString("\n") { event ->
        val begin = Instant.ofEpochMilli(event.begin).atZone(ZoneId.systemDefault())
        val end = Instant.ofEpochMilli(event.end).atZone(ZoneId.systemDefault())
        val time = if (event.allDay) "全天" else "${clock.format(begin)}–${clock.format(end)}"
        "- ${day.format(begin)} $time ${event.title.ifBlank { "（无标题）" }}" + event.location.takeIf { it.isNotBlank() }?.let { " @ $it" }.orEmpty()
    }
}
