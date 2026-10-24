package com.callagent.whatsappmsgreply

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.callagent.explain.ExplainPicker

/** Presses WhatsApp's send button, and explains a message selected for forward. */
class WhatsAppMsgReplySendService : AccessibilityService() {
    private var picker: ExplainPicker? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        picker?.close()
        picker = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        sendConfirmedMessage()
        picker?.noteSelection(event)
    }

    override fun onInterrupt() = Unit

    fun beginExplain() {
        val current = picker
        if (current == null) {
            picker = ExplainPicker(this) { picker = null }
        }
        picker?.start()
    }

    private fun sendConfirmedMessage() {
        val message = WhatsAppMsgReplySender.peek(this) ?: return
        val root = rootInActiveWindow ?: return
        val packageName = root.packageName?.toString() ?: return
        if (packageName != "com.whatsapp" && packageName != "com.whatsapp.w4b") return
        val typed = find(root, "$packageName:id/entry")?.text?.toString()?.trim().orEmpty()
        if (!typed.equals(message, ignoreCase = true)) return
        val send = find(root, "$packageName:id/send") ?: return
        if (click(send)) WhatsAppMsgReplySender.clear(this)
    }

    private fun find(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        val matches = root.findAccessibilityNodeInfosByViewId(viewId)
        return matches.firstOrNull()
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 4) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            hops += 1
        }
        return false
    }

    companion object {
        @Volatile
        var connected: WhatsAppMsgReplySendService? = null
    }
}
