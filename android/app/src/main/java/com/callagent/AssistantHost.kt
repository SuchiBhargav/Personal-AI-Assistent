package com.callagent

import org.json.JSONObject
import java.util.concurrent.Executor

/** What a feature needs from the chat screen. */
interface AssistantHost {
    val generation: Int
    val io: Executor
    var pending: JSONObject?
    fun say(message: String)
    fun choose(label: String, onClick: () -> Unit)
    fun onUi(block: () -> Unit)
    fun alive(): Boolean
}
