package com.whatsautobot.app

import android.net.Uri

/** Shared phone-number helpers (NZ +64 normalisation + recipient parsing). */
object Phones {

    private val PHONE_IN_TEXT = Regex("\\+?\\d{9,13}")

    /** Normalise a raw phone string to E.164, or null if not plausible.
     *  Numbers that already carry a country code (11+ digits not starting with
     *  64) are left untouched; only local-format numbers get the +64 prefix. */
    fun normalize(raw: String): String? {
        var digits = raw.trim().filter { it.isDigit() }
        if (digits.isEmpty()) return null
        digits = when {
            digits.startsWith("0") -> "64" + digits.drop(1) // NZ local "021..."
            digits.startsWith("64") -> digits               // already E.164-ish
            digits.length in 8..10 -> "64$digits"           // short → assume NZ local
            else -> digits                                  // has a country code, keep it
        }
        return if (digits.length in 9..13) "+$digits" else null
    }

    /** Extract a plausible phone number (9-13 digits) embedded in arbitrary text. */
    fun extract(text: String): String? {
        val m = PHONE_IN_TEXT.find(text.replace(" ", "").replace("-", "")) ?: return null
        return normalize(m.value)
    }

    /** Parse "Name, phone" per line into (name, phone) pairs. A line with just a
     *  number (no comma) uses the number as the display name. */
    fun parseRecipients(raw: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        raw.lineSequence().forEach { line ->
            val l = line.trim()
            if (l.isEmpty()) return@forEach
            val idx = l.lastIndexOf(',')
            val name: String
            val phoneRaw: String
            if (idx > 0) {
                name = l.substring(0, idx).trim()
                phoneRaw = l.substring(idx + 1)
            } else {
                name = ""
                phoneRaw = l
            }
            val phone = normalize(phoneRaw) ?: return@forEach
            out.add((if (name.isNotEmpty()) name else phone) to phone)
        }
        return out
    }

    /** Build a wa.me deep link; text is URL-encoded when given. */
    fun waMe(phone: String, text: String? = null): Uri {
        val base = "https://wa.me/${phone.replace("+", "")}"
        val uri = if (text.isNullOrEmpty()) base else "$base?text=${Uri.encode(text)}"
        return Uri.parse(uri)
    }
}
