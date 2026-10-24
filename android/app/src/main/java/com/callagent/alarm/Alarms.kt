package com.callagent.alarm

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.callagent.AssistantHost
import com.callagent.R
import java.util.Calendar

/** Set and turn off the assistant alarm, and mirror it into the Clock app. */
class AlarmController(
    private val activity: ComponentActivity,
    private val host: AssistantHost,
) {
    private var pendingAlarmHour = -1
    private var pendingAlarmMinute = -1
    private var pendingAlarmLabel = "Alarm"

    private val requestNotifications =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (pendingAlarmHour >= 0) {
                val hour = pendingAlarmHour
                val minute = pendingAlarmMinute
                val label = pendingAlarmLabel
                pendingAlarmHour = -1
                commit(hour, minute, label)
            }
        }

    fun set(hour: Int, minute: Int, label: String) {
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            pendingAlarmHour = hour
            pendingAlarmMinute = minute
            pendingAlarmLabel = label
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        commit(hour, minute, label)
    }

    fun disable() {
        if (AlarmScheduler.current(activity) == null) {
            host.say(activity.getString(R.string.no_alarm))
        } else {
            AlarmScheduler.cancel(activity)
            host.say(activity.getString(R.string.alarm_off))
        }
    }

    private fun commit(hour: Int, minute: Int, label: String) {
        var saved = false
        try {
            AlarmScheduler.schedule(activity, hour, minute, label)
            saved = true
        } catch (_: SecurityException) {
        }
        val clock = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_VIBRATE, true)
        }
        val openedClock = try {
            activity.startActivity(clock)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
        if (saved || openedClock) {
            host.say(activity.getString(R.string.alarm_set, formatTime(hour, minute)))
        } else {
            host.say(activity.getString(R.string.alarm_missing))
        }
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        return DateFormat.getTimeFormat(activity).format(calendar.time)
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    }
}
