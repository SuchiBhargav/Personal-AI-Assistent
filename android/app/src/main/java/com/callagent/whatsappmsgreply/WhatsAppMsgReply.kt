package com.callagent.whatsappmsgreply

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import com.callagent.AssistantHost
import com.callagent.R
import com.callagent.call.CallController
import com.callagent.call.LookupPurpose
import org.json.JSONObject

/** Ask once if the message is right, then open WhatsApp and send it. */
class WhatsAppMsgReply(
    private val activity: ComponentActivity,
    private val host: AssistantHost,
    private val calls: CallController,
) {
    init {
        calls.startWhatsAppMsgReply = ::review
    }

    fun lookup(name: String, message: String) {
        calls.lookup(name, LookupPurpose.WHATSAPP, message)
    }

    fun review(number: String, who: String, message: String) {
        val text = message.trim()
        if (text.isEmpty()) {
            askForWording(number, who)
            return
        }
        host.pending = pending("whatsapp_review", number, who, text)
        host.say(activity.getString(R.string.whatsapp_review, who, text))
    }

    fun onReviewReply(text: String, pending: JSONObject) {
        val number = pending.optString("number")
        val who = pending.optString("who").ifBlank { number }
        val message = pending.optString("message")
        when {
            isYes(text) -> deliver(number, who, message)
            isNo(text) -> askForWording(number, who)
            else -> review(number, who, corrected(text))
        }
    }

    fun onRewrite(text: String, pending: JSONObject) {
        review(pending.optString("number"), pending.optString("who"), text)
    }

    private fun askForWording(number: String, who: String) {
        host.pending = pending("whatsapp_rewrite", number, who, "")
        host.say(activity.getString(R.string.whatsapp_ask_message))
    }

    private fun deliver(number: String, who: String, message: String) {
        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) {
            host.say(activity.getString(R.string.enter_number))
            return
        }
        val packageName = when {
            appInstalled("com.whatsapp") -> "com.whatsapp"
            appInstalled("com.whatsapp.w4b") -> "com.whatsapp.w4b"
            else -> null
        }
        if (packageName == null) {
            host.pending = null
            host.say(activity.getString(R.string.whatsapp_missing))
            return
        }
        if (!WhatsAppMsgReplySender.enabled(activity)) {
            host.pending = pending("whatsapp_review", number, who, message)
            host.say(activity.getString(R.string.whatsapp_enable_send))
            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        host.pending = null
        WhatsAppMsgReplySender.arm(activity, message)
        val uri = Uri.parse("https://wa.me/$digits?text=${Uri.encode(message)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage(packageName) }
        try {
            activity.startActivity(intent)
            host.say(activity.getString(R.string.whatsapp_sending, who))
        } catch (_: ActivityNotFoundException) {
            WhatsAppMsgReplySender.clear(activity)
            host.say(activity.getString(R.string.whatsapp_missing))
        }
    }

    private fun pending(action: String, number: String, who: String, message: String): JSONObject {
        return JSONObject()
            .put("action", action)
            .put("number", number)
            .put("who", who)
            .put("message", message)
    }

    private fun corrected(text: String): String {
        return text.trim().replace(
            Regex("^(?:no|nope|wrong|change it to|change to|make it|say)\\b[:,]?\\s*", RegexOption.IGNORE_CASE),
            "",
        ).trim().ifBlank { text.trim() }
    }

    private fun isYes(text: String): Boolean {
        return YES.matches(text.trim())
    }

    private fun isNo(text: String): Boolean {
        return NO.matches(text.trim())
    }

    private fun appInstalled(packageName: String): Boolean {
        return try {
            activity.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private companion object {
        val YES = Regex(
            "^(?:yes|yeah|yep|yup|ok|okay|correct|right|sure|confirm|send|send it|yes send|yes please|that'?s correct|that is correct|its correct|it is correct|haan|ha|theek|theek hai)\\W*$",
            RegexOption.IGNORE_CASE,
        )
        val NO = Regex(
            "^(?:no|nope|nah|wrong|cancel|stop|don'?t|do not)\\W*$",
            RegexOption.IGNORE_CASE,
        )
    }
}

internal object WhatsAppMsgReplySender {
    private const val PREFS = "whatsapp_send"
    private const val ARMED = "armed"
    private const val MESSAGE = "message"
    private const val AT = "at"

    fun arm(activity: ComponentActivity, message: String) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(ARMED, true)
            .putString(MESSAGE, message)
            .putLong(AT, System.currentTimeMillis())
            .apply()
    }

    fun peek(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ARMED, false)) return null
        val at = prefs.getLong(AT, 0L)
        if (System.currentTimeMillis() - at > 20_000) {
            clear(context)
            return null
        }
        return prefs.getString(MESSAGE, null)?.trim()?.ifBlank { null }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun enabled(activity: ComponentActivity): Boolean {
        val expected = ComponentName(activity, WhatsAppMsgReplySendService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            activity.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
