package com.callagent.explain

/** Lets the capture service add the spoken Hindi to the chat if it is still open. */
object ExplainBus {
    var onExplanation: ((String) -> Unit)? = null
}
