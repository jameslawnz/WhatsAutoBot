package com.whatsautobot.app

/** Shared phone-number helpers (NZ +64 normalisation + recipient parsing). */
object Phones {

    private val PHONE_IN_TEXT = Regex("\\+?\\d{9,13}")

    /** Normalise a raw phone string to E.164 ("+64..."), or null if not plausible. */
    fun normalize(raw: String): String? {
        var digits = raw.trim().filter { it.isDigit() }
        if (digits.isEmpty()) return null
        digits = when {
            digits.startsWith("0") -> "64" + digits.drop(1)
            !digits.startsWith("64") -> "64$digits"
            else -> digits
        }
        return if (digits.length in 9..13) "+$digits" else null
    }

    /** Extract a plausible phone number (9-13 digits) embedded in arbitrary text. */
    fun extract(text: String): String? {
        val m = PHONE_IN_TEXT.find(text.replace(" ", "").replace("-", "")) ?: return null
        val digits = m.value.filter { it.isDigit() }
        return if (digits.length in 9..13) "+$digits" else null
    }

    /** Parse "Name, phone" per line into (name, phone) pairs. */
    fun parseRecipients(raw: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        raw.lineSequence().forEach { line ->
            val l = line.trim()
            if (l.isEmpty()) return@forEach
            val idx = l.lastIndexOf(',')
            if (idx <= 0) return@forEach
            val name = l.substring(0, idx).trim()
            val phone = normalize(l.substring(idx + 1)) ?: return@forEach
            if (name.isNotEmpty()) out.add(name to phone)
        }
        return out
    }
}
