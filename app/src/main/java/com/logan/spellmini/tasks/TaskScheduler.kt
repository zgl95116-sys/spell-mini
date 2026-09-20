package com.logan.spellmini.tasks

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.logan.spellmini.actions.ReminderReceiver
import com.logan.spellmini.data.Repeat
import com.logan.spellmini.data.Task
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** When a task runs next, and the alarm that makes it happen. One alarm per task; the receiver hands over to the runner. */
object TaskScheduler {
    /** No background checks between midnight and this hour unless the user picked the time himself. */
    private const val QUIET_UNTIL_HOUR = 7

    private fun timeOf(task: Task): LocalTime = runCatching { LocalTime.parse(task.at) }.getOrDefault(LocalTime.of(8, 0))

    /** The first moment after [after] at which this task is due; 0 when it does not repeat. */
    fun next(task: Task, after: Long = System.currentTimeMillis()): Long {
        val zone = ZoneId.systemDefault()
        val from = LocalDateTime.ofInstant(Instant.ofEpochMilli(after), zone)
        fun onOrAfter(accept: (DayOfWeek) -> Boolean): Long {
            var day = from.toLocalDate()
            repeat(9) {
                val candidate = day.atTime(timeOf(task))
                if (candidate.isAfter(from) && accept(day.dayOfWeek)) return candidate.atZone(zone).toInstant().toEpochMilli()
                day = day.plusDays(1)
            }
            return 0
        }
        return when (task.repeat) {
            Repeat.DAILY -> onOrAfter { true }
            Repeat.WEEKDAYS -> onOrAfter { it != DayOfWeek.SATURDAY && it != DayOfWeek.SUNDAY }
            Repeat.WEEKLY -> onOrAfter { it.value == task.weekday.coerceIn(1, 7) }
            Repeat.HOURS -> {
                val due = from.plusHours(task.everyHours.coerceAtLeast(1).toLong())
                val awake = if (due.hour < QUIET_UNTIL_HOUR) due.toLocalDate().atTime(QUIET_UNTIL_HOUR, 5) else due
                awake.atZone(zone).toInstant().toEpochMilli()
            }
            else -> 0
        }
    }

    private fun intent(context: Context, taskId: Long): PendingIntent = PendingIntent.getBroadcast(
        context, ("task|$taskId").hashCode(),
        Intent(context, ReminderReceiver::class.java).putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Same PendingIntent every time, so arming twice (app start, reboot) never doubles a task up. */
    fun arm(context: Context, task: Task) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (task.paused || task.nextAt <= 0) return alarms.cancel(intent(context, task.id))
        val at = maxOf(task.nextAt, System.currentTimeMillis() + 2_000) // a task that came due while we were down runs now
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context, task.id))
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context, task.id))
        }
    }

    fun cancel(context: Context, taskId: Long) = context.getSystemService(AlarmManager::class.java).cancel(intent(context, taskId))
}
