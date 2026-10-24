package com.callagent.call

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.callagent.AgentClient
import com.callagent.AssistantHost
import com.callagent.R
import org.json.JSONObject

enum class LookupPurpose { CALL, WHATSAPP }

/** Contact lookup and placing a phone call. */
class CallController(
    private val activity: ComponentActivity,
    private val host: AssistantHost,
) {
    var startWhatsAppMsgReply: (number: String, who: String, message: String) -> Unit = { _, _, _ -> }

    private var pendingLookupName: String? = null
    private var pendingLookupGeneration = 0
    private var lookupPurpose = LookupPurpose.CALL
    private var whatsAppMessage = ""
    private var pendingNumber: String? = null
    private var pendingDisplay: String? = null
    private var pendingCallGeneration = 0
    private var callWhenResumed = false
    private var callPermissionRequestOpen = false

    private val requestContacts =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val name = pendingLookupName
            if (!granted) {
                host.say(activity.getString(R.string.contacts_permission_required))
                return@registerForActivityResult
            }
            if (!name.isNullOrBlank() && pendingLookupGeneration == host.generation) {
                fetchContacts(name, pendingLookupGeneration)
            }
        }

    private val requestCall =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            callPermissionRequestOpen = false
            if (!granted) {
                callWhenResumed = false
                host.say(activity.getString(R.string.call_permission_required))
                return@registerForActivityResult
            }
            if (pendingNumber.isNullOrBlank()) return@registerForActivityResult
            callWhenResumed = true
            deliverCallIfReady()
        }

    fun restore(state: Bundle) {
        pendingLookupName = state.getString(STATE_PENDING_NAME)
        pendingLookupGeneration = state.getInt(STATE_PENDING_LOOKUP_GENERATION)
        pendingNumber = state.getString(STATE_PENDING_NUMBER)
        pendingDisplay = state.getString(STATE_PENDING_DISPLAY)
        pendingCallGeneration = state.getInt(STATE_PENDING_CALL_GENERATION)
    }

    fun save(out: Bundle) {
        out.putString(STATE_PENDING_NAME, pendingLookupName)
        out.putInt(STATE_PENDING_LOOKUP_GENERATION, pendingLookupGeneration)
        out.putString(STATE_PENDING_NUMBER, pendingNumber)
        out.putString(STATE_PENDING_DISPLAY, pendingDisplay)
        out.putInt(STATE_PENDING_CALL_GENERATION, pendingCallGeneration)
    }

    fun onResume() {
        deliverCallIfReady()
    }

    fun lookup(name: String, purpose: LookupPurpose, message: String) {
        pendingLookupName = name
        pendingLookupGeneration = host.generation
        lookupPurpose = purpose
        whatsAppMessage = message
        if (hasPermission(Manifest.permission.READ_CONTACTS)) {
            fetchContacts(name, pendingLookupGeneration)
        } else {
            requestContacts.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    fun dial(number: String, display: String) {
        val dialable = dialableNumber(number)
        if (dialable.isEmpty()) {
            host.say(activity.getString(R.string.enter_number))
            return
        }
        if (subscriberDigits(dialable) == null) {
            val count = dialable.count { it.isDigit() }
            host.say(activity.getString(R.string.invalid_number, count))
            return
        }
        pendingNumber = dialable
        pendingDisplay = display
        pendingCallGeneration = host.generation
        if (hasPermission(Manifest.permission.CALL_PHONE)) {
            callWhenResumed = false
            placeCall(dialable, display)
        } else {
            callWhenResumed = true
            askForCallPermission()
        }
    }

    private fun fetchContacts(name: String, generation: Int) {
        val purpose = lookupPurpose
        val message = whatsAppMessage
        val appContext = activity.applicationContext
        host.io.execute {
            val outcome = try {
                Result.success(ContactLookup.find(appContext, name))
            } catch (error: SecurityException) {
                Result.failure(error)
            } catch (error: Exception) {
                Result.failure(error)
            }
            host.onUi {
                if (!host.alive() || generation != host.generation) return@onUi
                outcome.fold(
                    onSuccess = { result ->
                        when (result) {
                            is LookupResult.Matches -> showMatches(result.numbers, purpose, message)
                            is LookupResult.Similar -> askIfSimilar(result, purpose, message)
                            is LookupResult.MissingNumber -> askForNumber(result.contactName, purpose, message, true)
                            LookupResult.None -> askForNumber(name, purpose, message, false)
                        }
                    },
                    onFailure = { error ->
                        val text = if (error is SecurityException) {
                            R.string.contacts_permission_required
                        } else {
                            R.string.contacts_failed
                        }
                        host.say(activity.getString(text))
                    },
                )
            }
        }
    }

    fun onConfirmContact(text: String, pending: JSONObject) {
        val who = pending.optString("who")
        val query = pending.optString("query").ifBlank { who }
        val message = pending.optString("message")
        val purpose = if (pending.optString("purpose") == "whatsapp") {
            LookupPurpose.WHATSAPP
        } else {
            LookupPurpose.CALL
        }
        when {
            isYes(text) -> {
                host.pending = null
                lookup(who, purpose, message)
            }
            isNo(text) -> askForNumber(query, purpose, message, false)
            else -> {
                host.pending = null
                lookup(text, purpose, message)
            }
        }
    }

    private fun askIfSimilar(result: LookupResult.Similar, purpose: LookupPurpose, message: String) {
        val contacts = result.contacts
        if (contacts.size == 1) {
            val contact = contacts[0]
            host.pending = JSONObject().apply {
                put("action", "confirm_contact")
                put("who", contact.name)
                put("query", result.query)
                put("purpose", if (purpose == LookupPurpose.WHATSAPP) "whatsapp" else "call")
                put("message", message)
            }
            host.say(activity.getString(R.string.similar_contact, contact.name, result.query))
            return
        }
        host.say(activity.getString(R.string.similar_contacts, result.query))
        for (contact in contacts) {
            host.choose(contact.name) {
                showMatches(contact.numbers, purpose, message)
            }
        }
    }

    private fun showMatches(matches: List<ContactNumber>, purpose: LookupPurpose, message: String) {
        if (matches.size == 1) {
            val match = matches[0]
            if (purpose == LookupPurpose.WHATSAPP) {
                startWhatsAppMsgReply(match.number, match.display(), message)
            } else {
                dial(match.number, match.display())
            }
            return
        }
        host.say(activity.getString(R.string.which_number))
        for (match in matches) {
            host.choose("${match.display()}\n${match.number}") {
                if (purpose == LookupPurpose.WHATSAPP) {
                    startWhatsAppMsgReply(match.number, match.display(), message)
                } else {
                    dial(match.number, match.display())
                }
            }
        }
    }

    private fun askForNumber(
        name: String,
        purpose: LookupPurpose,
        message: String,
        missingSavedNumber: Boolean,
    ) {
        val text = when {
            missingSavedNumber -> activity.getString(R.string.no_number_on_contact, name)
            purpose == LookupPurpose.WHATSAPP -> activity.getString(R.string.no_contact_whatsapp, name)
            else -> activity.getString(R.string.no_contact, name)
        }
        host.pending = JSONObject().apply {
            put("action", if (purpose == LookupPurpose.WHATSAPP) "whatsapp" else "call")
            put("contact_name", name)
            if (purpose == LookupPurpose.WHATSAPP) put("message", message)
        }
        host.say(text)
        if (purpose == LookupPurpose.CALL) askForCallPermission()
    }

    private fun isYes(text: String): Boolean {
        return Regex(
            "^(?:yes|yeah|yep|yup|ok|okay|correct|right|sure|confirm|call|send|haan|ha|theek|theek hai)\\W*$",
            RegexOption.IGNORE_CASE,
        ).matches(text.trim())
    }

    private fun isNo(text: String): Boolean {
        return Regex(
            "^(?:no|nope|nah|wrong|cancel|stop|don'?t|do not)\\W*$",
            RegexOption.IGNORE_CASE,
        ).matches(text.trim())
    }

    private fun askForCallPermission() {
        if (hasPermission(Manifest.permission.CALL_PHONE) || callPermissionRequestOpen) return
        callPermissionRequestOpen = true
        requestCall.launch(Manifest.permission.CALL_PHONE)
    }

    private fun deliverCallIfReady() {
        if (!callWhenResumed || !hasPermission(Manifest.permission.CALL_PHONE)) return
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val number = pendingNumber ?: return
        val display = pendingDisplay ?: number
        if (pendingCallGeneration != host.generation) return
        callWhenResumed = false
        placeCall(number, display)
    }

    private fun placeCall(number: String, display: String) {
        val uri = Uri.parse("tel:$number")
        val dialer = try {
            activity.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        } catch (_: SecurityException) {
            null
        }
        val started = startPhoneCall(uri, dialer) || (dialer != null && startPhoneCall(uri, null)) || placeWithTelecom(uri)
        if (!started) {
            openDialer(uri)
            return
        }
        val text = if (AgentClient.runningOnEmulator()) {
            activity.getString(R.string.emulator_call, display)
        } else {
            activity.getString(R.string.calling, display)
        }
        host.say(text)
    }

    private fun startPhoneCall(uri: Uri, packageName: String?): Boolean {
        val intent = Intent(Intent.ACTION_CALL, uri)
        if (!packageName.isNullOrBlank()) intent.setPackage(packageName)
        return try {
            activity.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun placeWithTelecom(uri: Uri): Boolean {
        val telecom = activity.getSystemService(TelecomManager::class.java) ?: return false
        return try {
            telecom.placeCall(uri, Bundle())
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun openDialer(uri: Uri) {
        try {
            activity.startActivity(Intent(Intent.ACTION_DIAL, uri))
            host.say(activity.getString(R.string.opened_dialer))
        } catch (_: ActivityNotFoundException) {
            host.say(activity.getString(R.string.no_phone_app))
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun dialableNumber(number: String): String = buildString {
        for (character in number.trim()) {
            when {
                character.isDigit() -> append(character)
                character == '+' && isEmpty() -> append(character)
            }
        }
    }

    private companion object {
        const val STATE_PENDING_NAME = "pending_name"
        const val STATE_PENDING_LOOKUP_GENERATION = "pending_lookup_generation"
        const val STATE_PENDING_NUMBER = "pending_number"
        const val STATE_PENDING_DISPLAY = "pending_display"
        const val STATE_PENDING_CALL_GENERATION = "pending_call_generation"
    }
}
