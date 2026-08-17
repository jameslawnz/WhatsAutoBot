package com.whatsautobot.app

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

data class PendingMessage(
    val name: String,
    val phone: String,
    val text: String,
)

object WaQueue {
    const val BROADCAST = "com.whatsautobot.app.QUEUE"
    const val EXTRA_STATE = "state"
    const val EXTRA_COUNT = "count"
    const val EXTRA_CURRENT = "current"

    const val STATE_IDLE = "idle"
    const val STATE_WAITING_CHAT = "waiting_chat"
    const val STATE_OPEN_NEXT = "open_next"

    private const val FILE = "whatsautobot_queue"
    private const val KEY_JSON = "queue_json"
    private const val KEY_RUNNING = "queue_running"

    private val messages = ArrayDeque<PendingMessage>()
    var current: PendingMessage? = null
        private set
    var isRunning: Boolean = false
        private set

    fun enqueue(msg: PendingMessage) {
        messages.addLast(msg)
    }

    fun size() = messages.size

    fun peek(): PendingMessage? = messages.firstOrNull()

    fun pop(): PendingMessage? = if (messages.isNotEmpty()) messages.removeFirst() else null

    fun stop() {
        isRunning = false
        messages.clear()
        current = null
    }

    fun setRunning(v: Boolean) {
        isRunning = v
    }

    fun setCurrent(msg: PendingMessage?) {
        current = msg
    }

    fun advance(): PendingMessage? {
        current = pop()
        return current
    }

    fun broadcast(context: Context) {
        val i = Intent(BROADCAST)
            .setPackage(context.packageName)
            .putExtra(EXTRA_STATE, if (isRunning) STATE_OPEN_NEXT else STATE_IDLE)
            .putExtra(EXTRA_COUNT, size())
        context.sendBroadcast(i)
    }

    /** Persist queue + running flag so a killed process can resume mid-campaign. */
    fun save(context: Context) {
        val arr = JSONArray()
        for (m in messages) {
            arr.put(
                JSONObject()
                    .put("name", m.name)
                    .put("phone", m.phone)
                    .put("text", m.text)
            )
        }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_JSON, Crypto.encrypt(arr.toString()))
            .putBoolean(KEY_RUNNING, isRunning)
            .apply()
    }

    /** Restore a persisted queue (no-op if none). Returns true if a runnable queue was restored. */
    fun restore(context: Context): Boolean {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val json = Crypto.decrypt(p.getString(KEY_JSON, "") ?: "")
        messages.clear()
        if (json.isEmpty()) return false
        return try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                messages.addLast(
                    PendingMessage(
                        name = o.getString("name"),
                        phone = o.getString("phone"),
                        text = o.getString("text")
                    )
                )
            }
            isRunning = p.getBoolean(KEY_RUNNING, false)
            current = null
            if (messages.isEmpty()) {
                isRunning = false
                return false
            }
            true
        } catch (e: Exception) {
            messages.clear()
            isRunning = false
            false
        }
    }
}