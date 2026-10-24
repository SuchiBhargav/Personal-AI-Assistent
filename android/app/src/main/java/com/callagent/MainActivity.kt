package com.callagent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.callagent.alarm.AlarmController
import com.callagent.call.CallController
import com.callagent.call.LookupPurpose
import com.callagent.call.phoneAttemptDigits
import com.callagent.call.phoneNumberIn
import com.callagent.call.subscriberDigits
import com.callagent.explain.ExplainBus
import com.callagent.explain.ExplainController
import com.callagent.explain.wantsScreenExplanation
import com.callagent.whatsappmsgreply.WhatsAppMsgReply
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Chat screen. Each feature lives in its own package: call, whatsappmsgreply, alarm, explain. */
class MainActivity : ComponentActivity(), AssistantHost {
    override val io: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "assistant").apply { isDaemon = true }
    }

    private val chat = ChatTranscript(this)
    private val calls = CallController(this, this)
    private val whatsAppMsgReply = WhatsAppMsgReply(this, this, calls)
    private val alarms = AlarmController(this, this)
    private val explain = ExplainController(this, this)
    private val explanationListener: (String) -> Unit = { text ->
        runOnUiThread {
            if (!isDestroyed) chat.agent(text)
        }
    }

    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button
    private lateinit var micButton: ImageButton

    private val speech =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (spoken.isEmpty()) {
                say(getString(R.string.voice_empty))
                return@registerForActivityResult
            }
            messageInput.setText(spoken)
            onSend()
        }

    private var requestGeneration = 0
    override var pending: JSONObject? = null
    override val generation: Int get() = requestGeneration

    override fun say(message: String) = chat.agent(message)

    override fun choose(label: String, onClick: () -> Unit) = chat.choice(label, onClick)

    override fun onUi(block: () -> Unit) = runOnUiThread(block)

    override fun alive(): Boolean = !isDestroyed

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        chat.bind()
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        micButton = findViewById(R.id.micButton)

        if (savedInstanceState != null) {
            requestGeneration = savedInstanceState.getInt(STATE_GENERATION)
            savedInstanceState.getString(STATE_PENDING)?.let { raw ->
                pending = try {
                    JSONObject(raw)
                } catch (_: JSONException) {
                    null
                }
            }
            calls.restore(savedInstanceState)
            savedInstanceState.getStringArrayList(STATE_LINES)?.let { chat.restore(it) }
        }
        if (chat.lines.isEmpty()) chat.agent(getString(R.string.greeting))
        ExplainBus.onExplanation = explanationListener

        sendButton.setOnClickListener { onSend() }
        findViewById<Button>(R.id.explainButton).setOnClickListener { explain.start() }
        micButton.setOnClickListener { onMic() }
        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                onSend()
                true
            } else {
                false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        calls.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_GENERATION, requestGeneration)
        outState.putString(STATE_PENDING, pending?.toString())
        outState.putStringArrayList(STATE_LINES, chat.lines)
        calls.save(outState)
    }

    override fun onDestroy() {
        if (ExplainBus.onExplanation === explanationListener) {
            ExplainBus.onExplanation = null
        }
        super.onDestroy()
        io.shutdownNow()
    }

    private fun onSend() {
        val text = messageInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        messageInput.text?.clear()
        chat.user(text)
        val pendingNow = pending
        when (pendingNow?.optString("action")) {
            "whatsapp_review" -> {
                whatsAppMsgReply.onReviewReply(text, pendingNow)
                return
            }
            "whatsapp_rewrite" -> {
                whatsAppMsgReply.onRewrite(text, pendingNow)
                return
            }
            "confirm_contact" -> {
                calls.onConfirmContact(text, pendingNow)
                return
            }
        }
        if (wantsScreenExplanation(text)) {
            pending = null
            explain.start()
            return
        }
        val typedNumber = phoneNumberIn(text)
        val attempt = phoneAttemptDigits(text)
        val waitingForCall = pendingNow?.optString("action") == "call"
        if (attempt != null && subscriberDigits(attempt) == null && (waitingForCall || text.contains(Regex("(?i)\\bcall\\b")))) {
            if (!waitingForCall) {
                pending = JSONObject().apply { put("action", "call") }
            }
            say(getString(R.string.invalid_number, attempt.length))
            return
        }
        if (typedNumber != null && pendingNow != null) {
            when (pendingNow.optString("action")) {
                "call" -> {
                    pending = null
                    calls.dial(typedNumber, typedNumber)
                    return
                }
                "whatsapp" -> {
                    val message = pendingNow.optString("message")
                    if (message.isNotBlank()) {
                        val who = pendingNow.optString("contact_name").ifBlank { typedNumber }
                        whatsAppMsgReply.review(typedNumber, who, message)
                        return
                    }
                }
            }
        }
        val history = chat.history(12)
        val thinking = chat.thinking()
        val generation = ++requestGeneration
        setComposingEnabled(false)
        io.execute {
            val outcome = try {
                Result.success(AgentClient.interpret(AgentClient.baseUrl(), text, pendingNow, history))
            } catch (error: IOException) {
                Result.failure(error)
            } catch (error: AgentException) {
                Result.failure(error)
            }
            onUi {
                if (!alive()) return@onUi
                chat.remove(thinking)
                if (generation != requestGeneration) return@onUi
                setComposingEnabled(true)
                outcome.fold(
                    onSuccess = ::handleAgentResult,
                    onFailure = { error ->
                        val message = if (error is AgentException) {
                            R.string.bad_agent_reply
                        } else {
                            R.string.agent_unreachable
                        }
                        say(getString(message))
                    },
                )
            }
        }
    }

    private fun onMic() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        if (intent.resolveActivity(packageManager) == null) {
            say(getString(R.string.voice_missing))
            return
        }
        try {
            speech.launch(intent)
        } catch (_: ActivityNotFoundException) {
            say(getString(R.string.voice_missing))
        }
    }

    private fun setComposingEnabled(enabled: Boolean) {
        sendButton.isEnabled = enabled
        micButton.isEnabled = enabled
    }

    private fun handleAgentResult(result: AgentResult) {
        when (result) {
            is AgentResult.Clarify -> {
                pending = result.pending
                say(result.message)
            }
            is AgentResult.Call -> {
                pending = null
                say(result.reply)
                calls.lookup(result.contactName, LookupPurpose.CALL, "")
            }
            is AgentResult.Dial -> {
                if (subscriberDigits(result.number) == null) {
                    pending = JSONObject().apply { put("action", "call") }
                    say(getString(R.string.invalid_number, result.number.count { it.isDigit() }))
                } else {
                    pending = null
                    say(result.reply)
                    calls.dial(result.number, result.number)
                }
            }
            is AgentResult.WhatsApp -> {
                pending = null
                val number = result.number
                if (!number.isNullOrBlank()) {
                    whatsAppMsgReply.review(number, result.contactName ?: number, result.message)
                } else {
                    whatsAppMsgReply.lookup(result.contactName.orEmpty(), result.message)
                }
            }
            is AgentResult.Alarm -> {
                pending = null
                alarms.set(result.hour, result.minute, result.label)
            }
            is AgentResult.DisableAlarm -> {
                pending = null
                alarms.disable()
            }
            is AgentResult.ExplainScreen -> {
                pending = null
                explain.start()
            }
            is AgentResult.Explain -> {
                pending = null
                say(result.explanation)
            }
        }
    }

    companion object {
        private const val STATE_GENERATION = "generation"
        private const val STATE_PENDING = "pending_json"
        private const val STATE_LINES = "chat_lines"
    }
}
