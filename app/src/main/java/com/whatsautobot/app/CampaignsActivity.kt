package com.whatsautobot.app

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CampaignsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_campaigns)

        val tvInfo = findViewById<TextView>(R.id.tv_campaigns_info)
        val list = findViewById<ListView>(R.id.lv_campaigns)

        val campaigns = CampaignStore.load(this).sortedByDescending { it.startedAt }
        if (campaigns.isEmpty()) {
            tvInfo.text = "No campaigns yet. Send a message from the main screen to start one."
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, emptyList<String>())
        } else {
            val totalSent = campaigns.sumOf { it.sent }
            tvInfo.text = "${campaigns.size} campaign(s), $totalSent messages sent total."
            val rows = campaigns.map { c ->
                val status = when (c.status) {
                    Campaign.STATUS_RUNNING -> "Running"
                    Campaign.STATUS_DONE -> "Done"
                    Campaign.STATUS_STOPPED -> "Stopped"
                    Campaign.STATUS_QUOTA -> "Quota reached"
                    else -> c.status
                }
                val started = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(c.startedAt))
                "$started · $status\n${c.sent} sent · ${c.skipped} skipped · ${c.failed} failed · ${c.total} total"
            }
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
        }

        findViewById<Button>(R.id.btn_campaigns_done).setOnClickListener { finish() }
    }
}