package com.whatsautobot.app

import android.content.Context
import android.content.Intent

/** State for scanning phone contacts for WhatsApp presence. */
object ScanState {
    const val BROADCAST = "com.whatsautobot.app.SCAN"
    const val EXTRA_STATE = "state"
    const val EXTRA_COUNT = "count"
    const val EXTRA_FOUND = "found"

    private val queue = ArrayDeque<Pair<String, String>>() // (name, phone)
    private val original = mutableListOf<Pair<String, String>>() // preserved snapshot
    var active: Boolean = false
        private set
    var current: Pair<String, String>? = null
        private set
    private val results = mutableMapOf<String, Boolean>()
    private val waNames = mutableMapOf<String, String>()
    var probeCount = 0
    var probeScheduled = false

    /** Bumped every time a new chat is opened; in-flight scan probes from a
     *  previous chat detect the mismatch and abort instead of misclassifying. */
    var generation = 0
        private set

    fun start(list: List<Pair<String, String>>) {
        queue.clear()
        original.clear()
        original.addAll(list)
        queue.addAll(list)
        results.clear()
        active = true
        current = null
        probeCount = 0
        probeScheduled = false
        generation = 0
    }

    fun size() = queue.size

    fun found() = results.count { it.value }

    fun allResults(): List<Triple<String, String, Boolean>> {
        // (name, phone, registered) in original order
        val out = mutableListOf<Triple<String, String, Boolean>>()
        for ((name, phone) in original) {
            val waName = waNames[phone] ?: name
            out.add(Triple(waName, phone, results[phone] ?: false))
        }
        return out
    }

    fun setName(phone: String, waName: String) {
        waNames[phone] = waName
        // Also record the waName into the result for immediate feedback is optional.
    }

    fun pop(): Pair<String, String>? {
        current = queue.removeFirstOrNull()
        generation++
        return current
    }

    fun setResult(phone: String, ok: Boolean) {
        results[phone] = ok
    }

    fun stop() {
        active = false
        queue.clear()
        current = null
        probeScheduled = false
        generation = 0
    }

    fun broadcast(context: Context, state: String) {
        val i = Intent(BROADCAST)
            .setPackage(context.packageName)
            .putExtra(EXTRA_STATE, state)
            .putExtra(EXTRA_COUNT, size())
            .putExtra(EXTRA_FOUND, found())
        context.sendBroadcast(i)
    }
}

/** State for capturing members from a WhatsApp group members screen. */
object CaptureState {
    const val BROADCAST = "com.whatsautobot.app.CAPTURE"
    const val EXTRA_STATE = "state"
    const val EXTRA_COUNT = "count"
    const val EXTRA_LABEL = "label"

    data class Member(val name: String, val phone: String)

    var armed = false
    var active = false

    val members = LinkedHashMap<String, Member>() // key = phone if present else name
    var lastDumpCount = -1
    var stableCount = 0
    var label = "Imported group"

    fun reset() {
        armed = true
        active = false
        members.clear()
        lastDumpCount = -1
        stableCount = 0
        label = "Imported group"
    }

    fun addMember(name: String, phone: String) {
        val n = name.trim()
        val p = phone.trim()
        val key = if (p.isNotEmpty()) p else n
        if (key.isEmpty()) return
        // Prefer phone-keyed; if we already have this phone with a blank name, upgrade it.
        val existing = members[key]
        if (existing == null) {
            members[key] = Member(n, p)
        } else if (existing.name.isEmpty() && n.isNotEmpty()) {
            members[key] = Member(n, p)
        }
    }

    fun disarm() {
        armed = false
        active = false
    }

    fun broadcast(context: Context, state: String) {
        val i = Intent(BROADCAST)
            .setPackage(context.packageName)
            .putExtra(EXTRA_STATE, state)
            .putExtra(EXTRA_COUNT, members.size)
            .putExtra(EXTRA_LABEL, label)
        context.sendBroadcast(i)
    }
}

/** Remembers the most recently captured group list id (consumed by MainActivity). */
object CapturedIdHolder {
    var last: String? = null
}