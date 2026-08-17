package com.whatsautobot.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ErrorLogEntry(
    val timestamp: Long,
    val message: String,
    val details: String,
)

/** Simple persistent error log for debugging send failures. */
object ErrorLog {
    private const val FILE = "whatsautobot_errors"
    private const val KEY_LOG = "log"
    private const val MAX_ENTRIES = 50

    fun log(context: Context, message: String, details: String = "") {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val arr = parseLog(p.getString(KEY_LOG, "") ?: "")
        arr.put(JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("msg", message)
            put("details", details)
        })
        while (arr.length() > MAX_ENTRIES) {
            arr.remove(0)
        }
        p.edit().putString(KEY_LOG, Crypto.encrypt(arr.toString())).apply()
    }

    fun load(context: Context): List<ErrorLogEntry> {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = Crypto.decrypt(p.getString(KEY_LOG, "") ?: "")
        return parseLog(raw).let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ErrorLogEntry(
                    timestamp = o.getLong("ts"),
                    message = o.getString("msg"),
                    details = o.optString("details", ""),
                )
            }
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY_LOG).apply()
    }

    fun format(entry: ErrorLogEntry): String {
        val time = SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(Date(entry.timestamp))
        return "$time — ${entry.message}${if (entry.details.isNotEmpty()) ": ${entry.details}" else ""}"
    }

    private fun parseLog(raw: String): JSONArray {
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }
}