package com.callagent.alarm

import com.callagent.MainActivity

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object AlarmScheduler {
    private const val PREFS = "assistant_alarm"
    private const val REQUEST = 41

    data class Saved(val hour: Int, val minute: Int, val triggerAt: Long, val label: String)

    fun current(context: Context): Saved? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("set", false)) return null
        val triggerAt = prefs.getLong("triggerAt", 0L)
        if (triggerAt <= System.currentTimeMillis()) {
            clear(context)
            return null
        }
        return Saved(
            hour = prefs.getInt("hour", 0),
            minute = prefs.getInt("minute", 0),
            triggerAt = triggerAt,
            label = prefs.getString("label", "Alarm") ?: "Alarm",
        )
    }

    fun schedule(context: Context, hour: Int, minute: Int, label: String) {
        val triggerAt = nextTrigger(hour, minute)
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAt, openAppIntent(context)),
            alarmIntent(context, label),
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("set", true)
            .putInt("hour", hour)
            .putInt("minute", minute)
            .putLong("triggerAt", triggerAt)
            .putString("label", label)
            .apply()
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context))
        clear(context)
    }

    fun restore(context: Context) {
        val saved = current(context) ?: return
        schedule(context, saved.hour, saved.minute, saved.label)
    }

    private fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun nextTrigger(hour: Int, minute: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DATE, 1)
        }
        return calendar.timeInMillis
    }

    private fun alarmIntent(context: Context, label: String = "Alarm"): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).putExtra(EXTRA_LABEL, label)
        return PendingIntent.getBroadcast(
            context,
            REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(context: Context): PendingIntent {
        return PendingIntent.getActivity(
            context,
            REQUEST + 1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    const val EXTRA_LABEL = "label"
}
