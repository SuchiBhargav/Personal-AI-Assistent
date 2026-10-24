package com.callagent.explain

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.callagent.AssistantHost
import com.callagent.R
import com.callagent.whatsappmsgreply.WhatsAppMsgReplySendService

/** Starts message selection. The screen is not recorded. */
class ExplainController(
    private val activity: ComponentActivity,
    private val host: AssistantHost,
) {
    private val accessibilitySettings =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (accessibilityEnabled()) requestMicThenStart()
            else host.say(activity.getString(R.string.explain_accessibility))
        }

    private val micPermission =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) host.say(activity.getString(R.string.explain_mic_denied))
            begin()
        }

    fun start() {
        if (!accessibilityEnabled()) {
            host.say(activity.getString(R.string.explain_accessibility))
            val settings = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            try {
                accessibilitySettings.launch(settings)
            } catch (_: ActivityNotFoundException) {
                host.say(activity.getString(R.string.explain_accessibility))
            }
            return
        }
        requestMicThenStart()
    }

    private fun requestMicThenStart() {
        val allowed = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
        if (allowed != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        begin()
    }

    private fun begin() {
        val service = WhatsAppMsgReplySendService.connected
        if (service == null) {
            host.say(activity.getString(R.string.explain_accessibility))
            return
        }
        service.beginExplain()
    }

    private fun accessibilityEnabled(): Boolean {
        val expected = ComponentName(activity, WhatsAppMsgReplySendService::class.java)
        val enabled = Settings.Secure.getString(
            activity.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }
}
