package com.whatsautobot.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Campaign(
    val id: String,
    val name: String,
    val template: String,
    val total: Int,
    val sent: Int,
    val skipped: Int,
    val failed: Int,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long,
) {
    val done: Boolean get() = status == STATUS_DONE
    val running: Boolean get() = status == STATUS_RUNNING

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_DONE = "done"
        const val STATUS_STOPPED = "stopped"
        const val STATUS_QUOTA = "quota"
    }
}

/** Persistent send history / campaign records. */
object CampaignStore {
    private const val FILE = "whatsautobot_campaigns"
    private const val KEY_DATA = "campaigns_json"
    private const val KEY_CURRENT = "current_campaign_id"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun currentId(context: Context): String? =
        prefs(context).getString(KEY_CURRENT, null)

    fun setCurrentId(context: Context, id: String?) {
        prefs(context).edit().putString(KEY_CURRENT, id).apply()
    }

    fun load(context: Context): List<Campaign> {
        val raw = Crypto.decrypt(prefs(context).getString(KEY_DATA, "") ?: "")
        if (raw.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<Campaign>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Campaign(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        template = o.getString("template"),
                        total = o.getInt("total"),
                        sent = o.getInt("sent"),
                        skipped = o.getInt("skipped"),
                        failed = o.getInt("failed"),
                        status = o.getString("status"),
                        startedAt = o.getLong("startedAt"),
                        finishedAt = o.getLong("finishedAt"),
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, campaigns: List<Campaign>) {
        val arr = JSONArray()
        for (c in campaigns) {
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("template", c.template)
                    .put("total", c.total)
                    .put("sent", c.sent)
                    .put("skipped", c.skipped)
                    .put("failed", c.failed)
                    .put("status", c.status)
                    .put("startedAt", c.startedAt)
                    .put("finishedAt", c.finishedAt)
            )
        }
        prefs(context).edit().putString(KEY_DATA, Crypto.encrypt(arr.toString())).apply()
    }

    fun current(context: Context): Campaign? {
        val id = currentId(context) ?: return null
        return load(context).firstOrNull { it.id == id }
    }

    /** Start (or restart) a campaign, returning its id. */
    fun start(context: Context, name: String, template: String, total: Int): String {
        val id = "camp_" + System.currentTimeMillis()
        val campaign = Campaign(
            id = id,
            name = name,
            template = template,
            total = total,
            sent = 0,
            skipped = 0,
            failed = 0,
            status = Campaign.STATUS_RUNNING,
            startedAt = System.currentTimeMillis(),
            finishedAt = 0L,
        )
        val campaigns = load(context).toMutableList()
        campaigns.add(0, campaign)
        save(context, campaigns)
        setCurrentId(context, id)
        return id
    }

    private fun update(context: Context, id: String, transform: (Campaign) -> Campaign) {
        val campaigns = load(context).map { if (it.id == id) transform(it) else it }
        save(context, campaigns)
    }

    fun increment(context: Context, id: String, sent: Int = 0, skipped: Int = 0, failed: Int = 0) {
        update(context, id) {
            it.copy(
                sent = it.sent + sent,
                skipped = it.skipped + skipped,
                failed = it.failed + failed
            )
        }
    }

    fun finish(context: Context, id: String, status: String = Campaign.STATUS_DONE) {
        update(context, id) {
            it.copy(status = status, finishedAt = System.currentTimeMillis())
        }
        setCurrentId(context, null)
    }

    fun defaultName(context: Context): String =
        SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US).format(Date())
}