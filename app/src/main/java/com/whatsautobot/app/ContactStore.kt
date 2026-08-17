package com.whatsautobot.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ContactEntry(val name: String, val phone: String, val onWhatsApp: Boolean = true)

data class ContactList(val id: String, val label: String, val source: String, val entries: MutableList<ContactEntry>)

object ContactStore {
    private const val FILE = "whatsautobot_lists"
    private const val KEY_DATA = "lists_json"

    const val SOURCE_PHONE_SCAN = "phone_scan"
    const val SOURCE_GROUP_IMPORT = "group_import"

    /** In-memory cache so repeated load() calls during a scan don't re-parse the store. */
    private var cache: MutableList<ContactList>? = null

    fun prefs(context: Context): android.content.SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): MutableList<ContactList> {
        cache?.let { return it }
        val json = prefs(context).getString(KEY_DATA, null)
        val out = mutableListOf<ContactList>()
        if (json != null) {
            try {
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
            } catch (e: Exception) {
                out.clear()
            }
        }
        cache = out
        return out
    }

    private fun save(context: Context, lists: List<ContactList>) {
        cache = lists.toMutableList()
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
        cache = mutableListOf()
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

    /** Single joined query over the phone table; avoids an N+1 lookup per contact. */
    fun allNumbers(): List<Pair<String, String>> { // (name, number)
        val out = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()
        val projection = arrayOf(
            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val cur = resolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection, null, null,
            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        ) ?: return out
        val nameCol = cur.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val numCol = cur.getColumnIndexOrThrow(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
        while (cur.moveToNext()) {
            val name = cur.getString(nameCol) ?: ""
            val num = Phones.normalize(cur.getString(numCol) ?: "") ?: continue
            if (seen.add(num)) {
                out.add((if (name.isBlank()) num else name) to num)
            }
        }
        cur.close()
        return out
    }
}