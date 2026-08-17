package com.whatsautobot.app

import android.content.Context
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.random.Random

/** Pure, testable throttle settings. */
class ThrottlePolicy(
    val baseDelayMs: Long,
    val jitterMs: Long,
    val dailyMax: Int,
    val windowStartHour: Int, // 0-23
    val windowEndHour: Int,   // 0-23 (inclusive)
    val dedupDays: Int,
    private val rng: Random = Random.Default,
) {
    /** Randomised inter-message delay to avoid uniform-timing ban fingerprints. */
    fun nextDelayMs(): Long =
        baseDelayMs + if (jitterMs > 0) rng.nextLong(0, jitterMs + 1) else 0L

    fun inWindow(hour: Int): Boolean = hour in windowStartHour..windowEndHour

    fun overQuota(sentToday: Int): Boolean = sentToday >= dailyMax

    fun dedupWindowMs(now: Long): Long = dedupDays * 24L * 3600_000L
}

/** Persists and enforces the throttle policy. */
object Throttler {
    private const val FILE = "whatsautobot_throttle"
    private const val KEY_DAY = "day"
    private const val KEY_COUNT = "count"
    private const val KEY_SENT = "sent"

    private const val DEFAULT_BASE_MS = 3000L
    private const val DEFAULT_JITTER_MS = 2000L
    private const val DEFAULT_DAILY_MAX = 100
    private const val DEFAULT_START_HOUR = 9
    private const val DEFAULT_END_HOUR = 21
    private const val DEFAULT_DEDUP_DAYS = 30

    fun policy(context: Context): ThrottlePolicy {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return ThrottlePolicy(
            baseDelayMs = p.getLong("base_delay_ms", DEFAULT_BASE_MS),
            jitterMs = p.getLong("jitter_ms", DEFAULT_JITTER_MS),
            dailyMax = p.getInt("daily_max", DEFAULT_DAILY_MAX),
            windowStartHour = p.getInt("window_start_hour", DEFAULT_START_HOUR),
            windowEndHour = p.getInt("window_end_hour", DEFAULT_END_HOUR),
            dedupDays = p.getInt("dedup_days", DEFAULT_DEDUP_DAYS),
        )
    }

    fun savePolicy(context: Context, policy: ThrottlePolicy) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putLong("base_delay_ms", policy.baseDelayMs)
            .putLong("jitter_ms", policy.jitterMs)
            .putInt("daily_max", policy.dailyMax)
            .putInt("window_start_hour", policy.windowStartHour)
            .putInt("window_end_hour", policy.windowEndHour)
            .putInt("dedup_days", policy.dedupDays)
            .apply()
    }

    /** Today's sent count, resetting automatically when the day changes. */
    fun sentToday(context: Context): Int {
        dailyReset(context)
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_COUNT, 0)
    }

    fun recordSend(context: Context) {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        dailyReset(context)
        p.edit().putInt(KEY_COUNT, p.getInt(KEY_COUNT, 0) + 1).apply()
    }

    /** Overall gate: inside send window AND under the daily quota. */
    fun canSendNow(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val pol = policy(context)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return pol.inWindow(hour) && !pol.overQuota(sentToday(context))
    }

    fun wasSentRecently(context: Context, phone: String, now: Long = System.currentTimeMillis()): Boolean {
        val sent = sentMap(context)
        val ts = sent.optLong(phone, 0L)
        return ts > 0 && (now - ts) < policy(context).dedupWindowMs(now)
    }

    fun recordSentTo(context: Context, phone: String, now: Long = System.currentTimeMillis()) {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val sent = sentMap(context)
        sent.put(phone, now)
        p.edit().putString(KEY_SENT, Crypto.encrypt(sent.toString())).apply()
    }

    /** Keep the sent-map bounded: prune entries older than the dedup window. */
    fun prune(context: Context, now: Long = System.currentTimeMillis()) {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val sent = sentMap(context)
        val keep = JSONObject()
        val cutoff = now - policy(context).dedupWindowMs(now)
        val keys = sent.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (sent.optLong(k, 0L) >= cutoff) keep.put(k, sent.optLong(k, 0L))
        }
        p.edit().putString(KEY_SENT, Crypto.encrypt(keep.toString())).apply()
    }

    private fun sentMap(context: Context): JSONObject {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = Crypto.decrypt(p.getString(KEY_SENT, "") ?: "")
        return if (raw.isEmpty()) JSONObject() else try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun dailyReset(context: Context) {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
        val stored = p.getString(KEY_DAY, "")
        if (stored == today) return
        p.edit().putString(KEY_DAY, today).putInt(KEY_COUNT, 0).apply()
    }
}