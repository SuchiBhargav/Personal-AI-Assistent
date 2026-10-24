package com.callagent

import android.os.Build
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed class AgentResult {
    data class Call(val contactName: String, val reply: String) : AgentResult()
    data class Dial(val number: String, val reply: String) : AgentResult()
    data class WhatsApp(
        val contactName: String?,
        val number: String?,
        val message: String,
        val reply: String,
    ) : AgentResult()
    data class Alarm(val hour: Int, val minute: Int, val label: String, val reply: String) : AgentResult()
    data class DisableAlarm(val reply: String) : AgentResult()
    data class Explain(val explanation: String) : AgentResult()
    data class ExplainScreen(val reply: String) : AgentResult()
    data class Clarify(val message: String, val pending: JSONObject?) : AgentResult()
}

class AgentException : Exception()

object AgentClient {
    fun baseUrl(): String {
        val host = if (isEmulator()) "10.0.2.2" else "127.0.0.1"
        return "http://$host:8000"
    }

    fun runningOnEmulator(): Boolean = isEmulator()

    fun interpret(
        baseUrl: String,
        text: String,
        pending: JSONObject?,
        history: JSONArray,
    ): AgentResult {
        val endpoint = URL("${baseUrl.trimEnd('/')}/interpret")
        val connection = endpoint.openConnection() as HttpURLConnection
        try {
            val payload = JSONObject().put("text", text).put("history", history)
            if (pending != null) payload.put("pending", pending)
            val body = payload.toString().toByteArray(Charsets.UTF_8)
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 180000
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { stream -> stream.write(body) }

            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            } ?: throw IOException("Empty response")
            val response = stream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
            if (code !in 200..299 && !response.trimStart().startsWith("{")) {
                throw IOException("HTTP $code")
            }
            return parse(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(body: String): AgentResult {
        val json = try {
            JSONObject(body)
        } catch (_: JSONException) {
            throw AgentException()
        }
        val reply = json.optString("reply").trim().ifBlank { json.optString("message").trim() }
        return when (json.optString("action")) {
            "call" -> {
                val name = json.optString("contact_name").trim()
                if (name.isEmpty()) throw AgentException()
                AgentResult.Call(name, reply.ifBlank { "Looking up $name." })
            }
            "dial" -> {
                val number = json.optString("number").trim()
                if (number.isEmpty()) throw AgentException()
                AgentResult.Dial(number, reply.ifBlank { "Calling $number." })
            }
            "whatsapp" -> {
                val message = json.optString("message").trim()
                val name = json.optString("contact_name").trim().ifEmpty { null }
                val number = json.optString("number").trim().ifEmpty { null }
                if (message.isEmpty() || (name == null && number == null)) throw AgentException()
                AgentResult.WhatsApp(name, number, message, reply.ifBlank { "Opening WhatsApp." })
            }
            "disable_alarm" -> AgentResult.DisableAlarm(reply.ifBlank { "Checking the alarm." })
            "explain" -> {
                val explanation = json.optString("explanation").trim().ifBlank { reply }
                if (explanation.isEmpty()) throw AgentException()
                AgentResult.Explain(explanation)
            }
            "explain_screen" -> AgentResult.ExplainScreen(reply)
            "set_alarm" -> {
                if (!json.has("hour") || !json.has("minute")) throw AgentException()
                val hour = json.getInt("hour")
                val minute = json.getInt("minute")
                if (hour !in 0..23 || minute !in 0..59) throw AgentException()
                val label = json.optString("label").trim().ifBlank { "Alarm" }
                AgentResult.Alarm(hour, minute, label, reply.ifBlank { "Setting an alarm." })
            }
            "clarify" -> {
                val message = json.optString("message").trim()
                if (message.isEmpty()) throw AgentException()
                AgentResult.Clarify(message, json.optJSONObject("pending"))
            }
            else -> throw AgentException()
        }
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.startsWith("unknown")
            || Build.MODEL.contains("google_sdk")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK built for")
            || Build.MANUFACTURER.contains("Genymotion")
            || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
            || Build.PRODUCT.contains("sdk")
            || Build.HARDWARE.contains("goldfish")
            || Build.HARDWARE.contains("ranchu")
    }
}
