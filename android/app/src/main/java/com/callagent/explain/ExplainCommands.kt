package com.callagent.explain

private val phrases = listOf(
    "iska matlab batao",
    "isaka matlab batao",
    "iska matlab",
    "isaka matlab",
    "matlab batao",
    "samjha do",
    "samajha do",
    "samjhaao",
    "samajhao",
    "samjhao",
    "samajho",
    "samjho",
    "explain",
    "इसका मतलब बताओ",
    "इसका मतलब",
    "मतलब बताओ",
    "समझा दो",
    "समझाओ",
    "समझो",
)

/** The phrases that start a screen explanation, or ask for the boxed message. */
fun wantsScreenExplanation(text: String): Boolean {
    val spoken = text.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (spoken.isEmpty() || spoken.length > 80) return false
    return phrases.any { phrase -> spoken == phrase || spoken.contains(phrase) }
}
