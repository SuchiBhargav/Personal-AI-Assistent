package com.callagent.call

/** The 10-digit number inside a local number, a leading 0, or a 91 country code. */
fun subscriberDigits(number: String): String? {
    val digits = number.filter { it.isDigit() }
    return when {
        digits.length == 10 -> digits
        digits.length == 11 && digits.startsWith("0") -> digits.drop(1)
        digits.length == 12 && digits.startsWith("91") -> digits.drop(2)
        else -> null
    }
}

/** A phone number typed after the assistant asked for one. Only a 10-digit number is returned. */
fun phoneNumberIn(text: String): String? {
    val digits = phoneAttemptDigits(text) ?: return null
    if (subscriberDigits(digits) == null) return null
    val match = Regex("""\+?\d[\d\s().-]{5,}\d""").find(text) ?: return null
    return (if (match.value.trimStart().startsWith("+")) "+" else "") + digits
}

/** Digits the user offered as a phone number, including a number that is too short. */
fun phoneAttemptDigits(text: String): String? {
    if (text.count { it.isLetter() } > 12) return null
    val match = Regex("""\+?\d[\d\s().-]{4,}\d""").find(text) ?: return null
    val digits = match.value.filter { it.isDigit() }
    if (digits.length < 7) return null
    return digits
}
