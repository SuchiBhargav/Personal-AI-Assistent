package com.callagent.explain

import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Speaks Hindi aloud. The written line stays on screen if this phone has no Hindi voice. */
class HindiSpeaker(context: android.content.Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var pendingDone: (() -> Unit)? = null
    private var onDone: () -> Unit = {}

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                val done = pendingDone
                pending = null
                pendingDone = null
                handler.post { done?.invoke() }
                return@TextToSpeech
            }
            val result = engine.setLanguage(Locale.forLanguageTag("hi-IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.language = Locale.forLanguageTag("hi")
            }
            engine.setSpeechRate(0.92f)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    handler.post { finishSpeaking() }
                }
                @Deprecated("Required by the listener.")
                override fun onError(utteranceId: String?) {
                    handler.post { finishSpeaking() }
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    handler.post { finishSpeaking() }
                }
            })
            ready = true
            val queued = pending
            val done = pendingDone
            pending = null
            pendingDone = null
            if (queued != null) speak(queued, done ?: {})
        }
    }

    fun speak(text: String, whenDone: () -> Unit = {}) {
        val engine = tts
        if (!ready || engine == null) {
            pending = text
            pendingDone = whenDone
            return
        }
        onDone = whenDone
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "explain")
    }

    fun shutdown() {
        pending = null
        pendingDone = null
        onDone = {}
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun finishSpeaking() {
        val done = onDone
        onDone = {}
        done()
    }
}
