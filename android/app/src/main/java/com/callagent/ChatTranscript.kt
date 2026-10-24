package com.callagent

import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject

/** Chat bubbles and the history sent to the language model. */
class ChatTranscript(private val activity: ComponentActivity) {
    private lateinit var transcript: ScrollView
    private lateinit var messages: LinearLayout
    val lines = ArrayList<String>()

    fun bind() {
        transcript = activity.findViewById(R.id.transcript)
        messages = activity.findViewById(R.id.messages)
    }

    fun user(text: String) {
        add(text, fromUser = true, store = true)
    }

    fun agent(text: String) {
        add(text, fromUser = false, store = true)
    }

    fun thinking(): TextView {
        return add(activity.getString(R.string.thinking), fromUser = false, store = false)
    }

    fun remove(view: View) {
        messages.removeView(view)
    }

    fun choice(label: String, onClick: () -> Unit) {
        val button = Button(activity)
        button.isAllCaps = false
        button.text = label
        button.setOnClickListener { onClick() }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = dp(8)
        button.layoutParams = params
        messages.addView(button)
        scrollToEnd()
    }

    fun history(limit: Int): JSONArray {
        val history = JSONArray()
        lines.dropLast(1).takeLast(limit).forEach { line ->
            if (line.length < 3) return@forEach
            val role = if (line.startsWith("u\n")) "user" else "assistant"
            history.put(JSONObject().put("role", role).put("text", line.substring(2)))
        }
        return history
    }

    fun restore(saved: List<String>) {
        lines.clear()
        lines.addAll(saved)
        saved.forEach { line ->
            add(line.substring(2), fromUser = line.startsWith("u\n"), store = false)
        }
    }

    private fun add(text: String, fromUser: Boolean, store: Boolean): TextView {
        if (store) lines.add((if (fromUser) "u\n" else "a\n") + text)
        val view = activity.layoutInflater.inflate(R.layout.item_message, messages, false) as TextView
        view.text = text
        view.setBackgroundResource(if (fromUser) R.drawable.bg_user else R.drawable.bg_agent)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.gravity = if (fromUser) Gravity.END else Gravity.START
        params.topMargin = dp(8)
        params.marginStart = if (fromUser) dp(48) else 0
        params.marginEnd = if (fromUser) 0 else dp(48)
        view.layoutParams = params
        messages.addView(view)
        scrollToEnd()
        return view
    }

    private fun scrollToEnd() {
        transcript.post { transcript.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
