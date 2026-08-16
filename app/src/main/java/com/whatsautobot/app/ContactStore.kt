package com.whatsautobot.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ContactEntry(val name: String, val phone: String, val onWhatsApp: Boolean = true)

data class ContactList(val id: String, val label: String, val source: String, val entries: MutableList<ContactEntry>)

object ContactStore {
    private const val FILE = "whatsautobot_lists"
    private const val KEY_DATA = "lists_json"

    private const val SOURCE_PHONE_SCAN = "phone_scan"
    private const val SOURCE_GROUP_IMPORT = "group_import"

    fun prefs(context: Context): android.content.SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): MutableList<ContactList> {
        val json = prefs(context).getString(KEY_DATA, null)
        val out = mutableListOf<ContactList>()
        if (json == null) return out
        return try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val entries = JSONArray(o.getString("entries"))
                val list = ContactList(
                    id = o.getString("id"),
                    label = o.getString("label"),
                    source = o.getString("source"),
                    entries = mutableListOf()
                )
                for (j in 0 until entries.length()) {
                    val en = entries.getJSONObject(j)
                    list.entries.add(
                        ContactEntry(
                            name = en.optString("name"),
                            phone = en.optString("phone"),
                            onWhatsApp = en.optBoolean("onWhatsApp", true)
                        )
                    )
                }
                out.add(list)
            }
            out
        } catch (e: Exception) {
            out
        }
    }

    private fun save(context: Context, lists: List<ContactList>) {
        val arr = JSONArray()
        for (l in lists) {
            val o = JSONObject()
            o.put("id", l.id)
            o.put("label", l.label)
            o.put("source", l.source)
            val entries = JSONArray()
            for (e in l.entries) {
                val eo = JSONObject()
                eo.put("name", e.name)
                eo.put("phone", e.phone)
                eo.put("onWhatsApp", e.onWhatsApp)
                entries.put(eo)
            }
            o.put("entries", entries.toString())
            arr.put(o)
        }
        prefs(context).edit().putString(KEY_DATA, arr.toString()).apply()
    }

    fun listOf(context: Context, id: String): ContactList? =
        load(context).firstOrNull { it.id == id }

    fun upsert(context: Context, list: ContactList) {
        val lists = load(context)
        // Don't treat this same list's previous entries as duplicates-of-others,
        // so refreshing an existing scan/group still updates its own numbers.
        val existingPhones = lists
            .filter { it.id != list.id }
            .flatMap { it.entries.map { e -> e.phone } }
            .toHashSet()
        // Deduplicate within the incoming list too.
        val seen = hashSetOf<String>()
        val deduped = mutableListOf<ContactEntry>()
        for (e in list.entries) {
            val key = e.phone
            if (key in existingPhones || !seen.add(key)) {
                // already present elsewhere in the store -> skip re-adding
                continue
            }
            deduped.add(e)
        }
        list.entries.clear()
        list.entries.addAll(deduped)
        val idx = lists.indexOfFirst { it.id == list.id }
        if (idx >= 0) {
            lists[idx] = list
        } else {
            lists.add(list)
        }
        save(context, lists)
    }

    fun all(context: Context): List<ContactList> = load(context)

    fun clearAll(context: Context) {
        prefs(context).edit().remove(KEY_DATA).apply()
    }

    fun removeList(context: Context, id: String) {
        val lists = load(context).filterNot { it.id == id }
        save(context, lists)
    }

    fun renameList(context: Context, id: String, newLabel: String) {
        val lists = load(context).map { if (it.id == id) it.copy(label = newLabel) else it }
        save(context, lists)
    }

    fun totalEntries(context: Context): Int = load(context).sumOf { it.entries.size }

    fun totalPhoneOnWhatsApp(context: Context): Int =
        load(context).filter { it.source == SOURCE_PHONE_SCAN }
            .sumOf { it.entries.count { e -> e.onWhatsApp } }
}

/** Reads the phone's address book. */
class ContactReader(context: Context) {
    private val resolver = context.contentResolver

    fun allNumbers(): List<Pair<String, String>> { // (name, number)
        val out = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()
        val projection = arrayOf(
            android.provider.ContactsContract.Contacts._ID,
            android.provider.ContactsContract.Contacts.DISPLAY_NAME
        )
        val cur = resolver.query(
            android.provider.ContactsContract.Contacts.CONTENT_URI,
            projection, null, null,
            android.provider.ContactsContract.Contacts.DISPLAY_NAME
        ) ?: return out
        while (cur.moveToNext()) {
            val cid = cur.getString(0)
            val name = cur.getString(1) ?: ""
            val p = resolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null,
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                arrayOf(cid), null
            )
            if (p != null) {
                val numCol = p.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (p.moveToNext()) {
                    val raw = p.getString(numCol) ?: continue
                    val num = normalize(raw)
                    if (num.isNotEmpty() && seen.add(num)) {
                        out.add((if (name.isBlank()) num else name) to num)
                    }
                }
                p.close()
            }
        }
        cur.close()
        return out
    }

    private fun normalize(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.startsWith("64") && digits.length in 9..12 -> "+$digits"
            digits.startsWith("0") && digits.length in 8..11 -> "+64${digits.substring(1)}"
            digits.startsWith("+") -> "+$digits"
            digits.length in 8..10 -> "+64$digits"
            else -> ""
        }
    }
}

/** Parses "Name, phone" lines from the recipients field. */
object RecipientParser {
    fun parse(raw: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        raw.lineSequence().forEach { line ->
            val l = line.trim()
            if (l.isEmpty()) return@forEach
            val idx = l.lastIndexOf(',')
            if (idx <= 0) return@forEach
            val name = l.substring(0, idx).trim()
            var phone = l.substring(idx + 1).trim()
            if (phone.startsWith("0") && phone.length in 9..11) phone = "+64${phone.substring(1)}"
            else if (!phone.startsWith("+")) phone = "+64$phone"
            val digits = phone.filter { it.isDigit() }
            if (name.isNotEmpty() && digits.length in 9..13) {
                out.add(name to phone)
            }
        }
        return out
    }
}