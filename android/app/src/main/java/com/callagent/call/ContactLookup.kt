package com.callagent.call

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
data class ContactNumber(
    val contactId: Long,
    val name: String,
    val number: String,
    val label: String,
    val preferred: Boolean,
) {
    fun display(): String = if (label.isBlank()) name else "$name ($label)"
}

data class SimilarContact(
    val name: String,
    val numbers: List<ContactNumber>,
)

sealed class LookupResult {
    data class Matches(val numbers: List<ContactNumber>) : LookupResult()
    data class Similar(val query: String, val contacts: List<SimilarContact>) : LookupResult()
    data class MissingNumber(val contactName: String) : LookupResult()
    data object None : LookupResult()
}

object ContactLookup {
    fun find(context: Context, query: String): LookupResult {
        val needle = query.trim()
        if (needle.isEmpty()) return LookupResult.None

        val people = loadPeople(context, needle)
        if (people.isEmpty()) return LookupResult.None
        for (nickname in loadNicknames(context)) {
            val person = people[nickname.contactId] ?: continue
            val score = nameScore(needle, nickname.name)
            if (score > person.score) person.score = score
        }

        val exact = people.values.filter { sameName(it.name, needle) || it.score == EXACT }
        if (exact.isNotEmpty()) {
            val withNumbers = exact.filter { it.hasPhone }
            if (withNumbers.isEmpty()) return LookupResult.MissingNumber(exact.first().name)
            val numbers = withNumbers.flatMap { loadNumbers(context, it.id, it.name) }
            if (numbers.isEmpty()) return LookupResult.MissingNumber(exact.first().name)
            return LookupResult.Matches(sortNumbers(numbers))
        }

        val similar = people.values
            .filter { it.hasPhone && it.score >= SIMILAR }
            .sortedByDescending { it.score }
            .take(5)
            .mapNotNull { person ->
                val numbers = loadNumbers(context, person.id, person.name)
                if (numbers.isEmpty()) null else SimilarContact(person.name, sortNumbers(numbers))
            }
        if (similar.isNotEmpty()) return LookupResult.Similar(needle, similar)
        return LookupResult.None
    }

    private data class Person(
        val id: Long,
        val name: String,
        val hasPhone: Boolean,
        var score: Int,
    )

    private data class NicknameHit(val contactId: Long, val name: String)

    private fun loadPeople(context: Context, query: String): MutableMap<Long, Person> {
        val people = linkedMapOf<Long, Person>()
        val projection = arrayOf(
            Contacts._ID,
            Contacts.DISPLAY_NAME_PRIMARY,
            Contacts.HAS_PHONE_NUMBER,
        )
        context.contentResolver.query(Contacts.CONTENT_URI, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Contacts._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(Contacts.DISPLAY_NAME_PRIMARY)
            val phoneIndex = cursor.getColumnIndexOrThrow(Contacts.HAS_PHONE_NUMBER)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)?.trim().orEmpty()
                if (name.isEmpty()) continue
                val id = cursor.getLong(idIndex)
                people[id] = Person(
                    id = id,
                    name = name,
                    hasPhone = cursor.getInt(phoneIndex) == 1,
                    score = nameScore(query, name),
                )
            }
        }
        return people
    }

    private fun loadNicknames(context: Context): List<NicknameHit> {
        val hits = mutableListOf<NicknameHit>()
        context.contentResolver.query(
            Data.CONTENT_URI,
            arrayOf(Data.CONTACT_ID, Nickname.NAME),
            "${Data.MIMETYPE} = ?",
            arrayOf(Nickname.CONTENT_ITEM_TYPE),
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Data.CONTACT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(Nickname.NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)?.trim().orEmpty()
                if (name.isEmpty()) continue
                hits += NicknameHit(cursor.getLong(idIndex), name)
            }
        }
        return hits
    }

    private fun loadNumbers(context: Context, contactId: Long, name: String): List<ContactNumber> {
        val projection = arrayOf(
            Phone.NUMBER,
            Phone.NORMALIZED_NUMBER,
            Phone.TYPE,
            Phone.LABEL,
            Phone.IS_SUPER_PRIMARY,
            Phone.IS_PRIMARY,
        )
        val numbers = mutableListOf<ContactNumber>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            projection,
            "${Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndexOrThrow(Phone.NUMBER)
            val normalizedIndex = cursor.getColumnIndex(Phone.NORMALIZED_NUMBER)
            val typeIndex = cursor.getColumnIndexOrThrow(Phone.TYPE)
            val labelIndex = cursor.getColumnIndexOrThrow(Phone.LABEL)
            val superIndex = cursor.getColumnIndexOrThrow(Phone.IS_SUPER_PRIMARY)
            val primaryIndex = cursor.getColumnIndexOrThrow(Phone.IS_PRIMARY)
            while (cursor.moveToNext()) {
                val raw = cursor.getString(numberIndex)?.trim().orEmpty()
                val normalized = if (normalizedIndex >= 0) {
                    cursor.getString(normalizedIndex)?.trim().orEmpty()
                } else {
                    ""
                }
                val number = normalized.ifBlank { raw }
                if (number.isEmpty()) continue
                val label = Phone.getTypeLabel(
                    context.resources,
                    cursor.getInt(typeIndex),
                    cursor.getString(labelIndex),
                )?.toString().orEmpty()
                numbers += ContactNumber(
                    contactId = contactId,
                    name = name,
                    number = number,
                    label = label,
                    preferred = cursor.getInt(superIndex) == 1 || cursor.getInt(primaryIndex) == 1,
                )
            }
        }
        return numbers
    }

    private fun sortNumbers(numbers: List<ContactNumber>): List<ContactNumber> {
        return numbers
            .distinctBy { it.contactId to it.number.filter(Char::isDigit) }
            .sortedWith(
                compareBy<ContactNumber> { it.name.lowercase() }
                    .thenBy { if (it.preferred) 0 else 1 }
                    .thenBy { it.label.lowercase() },
            )
    }

    private const val EXACT = 100
    private const val SIMILAR = 50
}

internal fun sameName(contactName: String, query: String): Boolean {
    return normalizeName(contactName) == normalizeName(query)
}

internal fun nameScore(query: String, contactName: String): Int {
    val left = normalizeName(query)
    val right = normalizeName(contactName)
    if (left.isEmpty() || right.isEmpty()) return -1
    if (left == right) return 100
    val queryWords = left.split(" ")
    val nameWords = right.split(" ")
    val distances = queryWords.map { word ->
        nameWords.minOf { editDistance(word, it) }
    }
    val closest = distances.count { it <= 1 }
    if (closest == 0) return -1
    val near = distances.count { it <= 2 }
    if (queryWords.size >= 2 && near < queryWords.size && nameWords.size > 1) return -1
    return (80 - distances.sum() * 10).coerceAtLeast(50)
}

private fun normalizeName(value: String): String {
    return value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}

internal fun editDistance(left: String, right: String): Int {
    if (left == right) return 0
    if (left.isEmpty()) return right.length
    if (right.isEmpty()) return left.length
    var previous = IntArray(right.length + 1) { it }
    var current = IntArray(right.length + 1)
    for (i in left.indices) {
        current[0] = i + 1
        for (j in right.indices) {
            val cost = if (left[i] == right[j]) 0 else 1
            current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[right.length]
}
