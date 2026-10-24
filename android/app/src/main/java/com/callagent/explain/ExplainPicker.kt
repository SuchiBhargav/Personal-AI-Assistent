package com.callagent.explain

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.callagent.AgentClient
import com.callagent.AgentException
import com.callagent.AgentResult
import com.callagent.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The message WhatsApp has selected the way a forward selection works:
 * the whole bubble, not a few highlighted words.
 */
object WhatsAppMessages {
    private val time = Regex("""(?i)^\d{1,2}:\d{2}(\s?[ap]m)?$""")
    private val messageIds = listOf("message_text", "caption", "media_caption", "conversation_text")

    fun selectedText(root: AccessibilityNodeInfo, screenHeight: Int): String? {
        val marked = ArrayList<AccessibilityNodeInfo>()
        val wordSelections = ArrayList<AccessibilityNodeInfo>()
        collect(root, marked, wordSelections, recycle = false)
        val found = LinkedHashSet<String>()
        for (node in marked) {
            wholeMessage(node, screenHeight)?.let { found.add(it) }
            if (node !== root) node.recycle()
        }
        if (found.isEmpty() && wordSelections.size == 1) {
            wholeMessage(wordSelections[0], screenHeight)?.let { found.add(it) }
        }
        wordSelections.forEach { if (it !== root) it.recycle() }
        return found.joinToString("\n").ifBlank { null }
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        marked: MutableList<AccessibilityNodeInfo>,
        wordSelections: MutableList<AccessibilityNodeInfo>,
        recycle: Boolean,
    ) {
        var keep = false
        try {
            if (!composer(node) && isMarked(node)) {
                marked.add(node)
                keep = true
            } else if (!composer(node) && activeWordSelection(node)) {
                wordSelections.add(node)
                keep = true
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                collect(child, marked, wordSelections, recycle = true)
            }
        } finally {
            if (recycle && !keep) node.recycle()
        }
    }

    /** The row, its check, or the "Selected" label WhatsApp adds for a forward selection. */
    private fun isMarked(node: AccessibilityNodeInfo): Boolean {
        if (node.isChecked || node.isSelected) return true
        if (Build.VERSION.SDK_INT >= 30) {
            val state = node.stateDescription?.toString()?.trim()?.lowercase().orEmpty()
            if (state == "selected" || state == "checked" || state == "चयनित" || state.contains("चुना")) return true
        }
        val described = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        if (described == "selected" || described == "चयनित" || described == "चुना गया") return true
        if (described.startsWith("selected,") || described.startsWith("selected ") || described.endsWith(" selected")) return true
        val id = node.viewIdResourceName.orEmpty().lowercase()
        return (id.contains("check") || id.contains("selection") || id.contains("selected")) &&
            (described.contains("selected") || described.contains("चयनित") || described.contains("चुना"))
    }

    private fun activeWordSelection(node: AccessibilityNodeInfo): Boolean {
        val raw = node.text?.toString().orEmpty()
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        return raw.length >= 2 && start >= 0 && end - start >= 2
    }

    private fun composer(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName.orEmpty().lowercase()
        return id.contains("entry") || id.contains("input") || id.contains("compose") || id.contains("emoji")
    }

    private fun wholeMessage(start: AccessibilityNodeInfo, screenHeight: Int): String? {
        val mark = Rect()
        start.getBoundsInScreen(mark)
        var best: String? = null
        var current: AccessibilityNodeInfo? = start
        var owned = false
        var hops = 0
        while (current != null && hops < 8) {
            val bounds = Rect()
            current.getBoundsInScreen(bounds)
            val tall = bounds.height() > screenHeight * 0.85
            val row = messageIn(current, mark, screenHeight, strict = tall)
            if (row != null) best = row
            val parent = if (tall) null else current.parent
            if (owned) current.recycle()
            current = parent
            owned = parent != null
            if (tall) break
            hops += 1
        }
        if (owned) current?.recycle()
        return best
    }

    /** The one message bubble that lines up with the selection, even when the check sits beside it. */
    private fun messageIn(node: AccessibilityNodeInfo, mark: Rect, screenHeight: Int, strict: Boolean): String? {
        val pkg = node.packageName?.toString().orEmpty()
        val found = ArrayList<Pair<String, Rect>>()
        if (pkg.isNotEmpty()) {
            for (name in messageIds) {
                val nodes = try {
                    node.findAccessibilityNodeInfosByViewId("$pkg:id/$name")
                } catch (_: Exception) {
                    null
                } ?: continue
                for (child in nodes) {
                    val text = spokenText(child)
                    if (text != null) {
                        val bounds = Rect()
                        child.getBoundsInScreen(bounds)
                        found.add(text to bounds)
                    }
                    child.recycle()
                }
                if (found.isNotEmpty()) break
            }
        }
        val overlapping = found.filter { verticallyOverlaps(mark, it.second) }
        when {
            overlapping.size == 1 -> return overlapping[0].first
            overlapping.size > 1 && !strict -> return overlapping.maxBy { it.second.height() }.first
            found.size == 1 && !strict -> return found[0].first
            found.isNotEmpty() -> return null
        }
        if (strict) return null
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.height() <= 0 || bounds.height() > screenHeight * 0.5) return null
        if (!verticallyOverlaps(bounds, mark) && !bounds.contains(mark.centerX(), mark.centerY())) return null
        return spokenText(node)
    }

    private fun spokenText(node: AccessibilityNodeInfo): String? {
        val raw = node.text?.toString()?.trim().orEmpty().ifBlank {
            node.contentDescription?.toString()?.trim().orEmpty()
        }
        val cleaned = raw
            .replace(Regex("(?i)^(selected|चयनित|चुना गया)[,.:\\s]+"), "")
            .replace(Regex("(?i)[,\\s]+(selected|चयनित|चुना गया)$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.takeIf { usable(it) }
    }

    private fun verticallyOverlaps(first: Rect, second: Rect): Boolean {
        return first.top < second.bottom && second.top < first.bottom
    }

    private fun usable(text: String): Boolean {
        if (text.length < 2 || time.matches(text)) return false
        val lower = text.lowercase()
        if (lower == "online" || lower.startsWith("typing") || lower.startsWith("last seen")) return false
        if (lower == "delivered" || lower == "read" || lower == "sent" || lower == "forwarded") return false
        if (lower == "forward" || lower == "star" || lower == "delete" || lower == "copy" || lower == "reply") return false
        if (lower == "selected" || lower == "चयनित" || lower == "चुना गया") return false
        if (lower == "today" || lower == "yesterday" || text == "आज" || text == "कल") return false
        return true
    }
}

/**
 * A green समझो button. She selects a WhatsApp message the way she would forward it,
 * then taps the button. The screen is not recorded.
 */
class ExplainPicker(
    private val service: AccessibilityService,
    private val onClosed: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val speaker = HindiSpeaker(service)
    private var worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "explain").apply { isDaemon = true }
    }
    private val screenHeight = service.resources.displayMetrics.heightPixels
    private var panel: LinearLayout? = null
    private var status: TextView? = null
    private var armed = false
    private var closed = false
    private var busy = false
    private var speech: SpeechRecognizer? = null
    private var shouldListen = false
    private var cachedSelection: String? = null

    fun start() {
        if (closed) return
        armed = true
        cachedSelection = null
        showPanel()
        openWhatsApp()
        val line = service.getString(R.string.explain_ready)
        status?.text = line
        publish(line)
        speaker.speak(line) { listen() }
    }

    fun noteSelection(event: AccessibilityEvent?) {
        if (!armed || closed || busy) return
        val source = event?.source ?: return
        try {
            val pkg = source.packageName?.toString().orEmpty()
            if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
            val text = WhatsAppMessages.selectedText(source, screenHeight) ?: return
            cachedSelection = text
        } finally {
            source.recycle()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        armed = false
        shouldListen = false
        handler.removeCallbacksAndMessages(null)
        stopListening()
        try {
            speech?.destroy()
        } catch (_: Exception) {
        }
        speech = null
        removePanel()
        speaker.shutdown()
        worker.shutdownNow()
        onClosed()
    }

    private fun showPanel() {
        if (panel != null) return
        val label = TextView(service).apply {
            setTextColor(Color.BLACK)
            textSize = 16f
            maxLines = 6
            setPadding(dp(12), dp(8), dp(12), dp(4))
            maxWidth = service.resources.displayMetrics.widthPixels - dp(24)
        }
        status = label
        val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(service.getString(R.string.explain_smjho), green = true) { explainSelected() })
        row.addView(button(service.getString(R.string.explain_close), green = false) { close() })
        val box = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(label)
            addView(row)
        }
        val params = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        params.gravity = Gravity.BOTTOM or Gravity.END
        params.x = dp(12)
        params.y = dp(88)
        windowManager.addView(box, params)
        panel = box
    }

    private fun explainSelected() {
        if (busy || closed) return
        val known = readSelected() ?: cachedSelection
        if (known.isNullOrBlank()) {
            val line = service.getString(R.string.explain_none)
            status?.text = line
            speaker.speak(line) { listen() }
            return
        }
        busy = true
        stopListening()
        status?.text = service.getString(R.string.explain_reading)
        if (worker.isShutdown) {
            worker = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "explain").apply { isDaemon = true }
            }
        }
        worker.execute {
            val outcome = try {
                AgentClient.interpret(
                    AgentClient.baseUrl(),
                    known.take(4000),
                    JSONObject().put("action", "explain"),
                    JSONArray(),
                )
            } catch (_: IOException) {
                null
            } catch (_: AgentException) {
                null
            }
            handler.post {
                busy = false
                if (closed) return@post
                val spoken = when (outcome) {
                    is AgentResult.Explain -> outcome.explanation
                    is AgentResult.Clarify -> outcome.message
                    else -> service.getString(R.string.explain_failed)
                }
                status?.text = if (outcome == null) service.getString(R.string.agent_unreachable) else spoken
                publish(status?.text?.toString().orEmpty())
                speaker.speak(if (outcome == null) service.getString(R.string.explain_failed) else spoken) { listen() }
            }
        }
    }

    private fun removePanel() {
        panel?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
        }
        panel = null
        status = null
    }

    private fun readSelected(): String? {
        var found: String? = null
        for (window in service.windows) {
            val root = window.root ?: continue
            try {
                val pkg = root.packageName?.toString()
                if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") continue
                val text = WhatsAppMessages.selectedText(root, screenHeight) ?: continue
                if (text.length > (found?.length ?: 0)) found = text
            } finally {
                root.recycle()
            }
        }
        if (!found.isNullOrBlank()) return found
        val active = service.rootInActiveWindow ?: return null
        try {
            val pkg = active.packageName?.toString()
            if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return null
            return WhatsAppMessages.selectedText(active, screenHeight)
        } finally {
            active.recycle()
        }
    }

    private fun openWhatsApp() {
        val launch = service.packageManager.getLaunchIntentForPackage("com.whatsapp")
            ?: service.packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
            ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            service.startActivity(launch)
        } catch (_: Exception) {
        }
    }

    private fun listen() {
        if (!armed || busy || closed) return
        if (!SpeechRecognizer.isRecognitionAvailable(service)) return
        shouldListen = true
        val recognizer = speech ?: SpeechRecognizer.createSpeechRecognizer(service).also { created ->
            created.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val heard = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (wantsScreenExplanation(heard)) explainSelected()
                }
                override fun onResults(results: android.os.Bundle?) {
                    val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (wantsScreenExplanation(heard)) explainSelected()
                    else if (shouldListen && !busy) handler.postDelayed({ listen() }, 300)
                }
                override fun onError(error: Int) {
                    if (!shouldListen || busy || closed) return
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) return
                    handler.postDelayed({ listen() }, 500)
                }
            })
            speech = created
            created
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        }
        try {
            recognizer.cancel()
            recognizer.startListening(intent)
        } catch (_: Exception) {
        }
    }

    private fun stopListening() {
        shouldListen = false
        try {
            speech?.cancel()
        } catch (_: Exception) {
        }
    }

    private fun overlayParams(width: Int, height: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
    }

    private fun button(label: String, green: Boolean, onClick: () -> Unit): Button {
        return Button(service).apply {
            text = label
            isAllCaps = false
            textSize = 18f
            if (green) {
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#1B6B3A"))
            }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) onClick()
                true
            }
            minimumWidth = dp(if (green) 132 else 72)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
        }
    }

    private fun publish(text: String) {
        try {
            ExplainBus.onExplanation?.invoke(text)
        } catch (_: Exception) {
        }
    }

    private fun dp(value: Int): Int {
        return (value * service.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }
}
