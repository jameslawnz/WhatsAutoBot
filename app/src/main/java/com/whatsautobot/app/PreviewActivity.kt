package com.whatsautobot.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class PreviewActivity : AppCompatActivity() {

    private data class Entry(val name: String, val phone: String, val text: String)

    private lateinit var tvRecipient: TextView
    private lateinit var tvPhone: TextView
    private lateinit var tvMessage: TextView
    private lateinit var tvProgress: TextView
    private lateinit var lvQuick: ListView
    private lateinit var btnUse: Button

    private val entries = mutableListOf<Entry>()
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        tvRecipient = findViewById(R.id.tv_recipient)
        tvPhone = findViewById(R.id.tv_phone)
        tvMessage = findViewById(R.id.tv_message)
        tvProgress = findViewById(R.id.tv_progress)
        lvQuick = findViewById(R.id.lv_quick)
        btnUse = findViewById(R.id.btn_use)

        val template = Prefs.template(this)
        val recipients = Phones.parseRecipients(intent.getStringExtra("recipients") ?: "")
        if (recipients.isEmpty()) {
            tvRecipient.text = "No recipients"
            tvMessage.text = "Go back and add recipients."
            btnUse.isEnabled = false
            findViewById<Button>(R.id.btn_prev).isEnabled = false
            findViewById<Button>(R.id.btn_next).isEnabled = false
            return
        }
        recipients.forEach { (name, phone) ->
            entries.add(Entry(name, phone, template.replace("{name}", name)))
        }

        // Quick list
        lvQuick.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            entries.map { "${it.name} · ${it.phone}" })
        lvQuick.setOnItemClickListener { _, _, pos, _ -> show(pos) }

        findViewById<Button>(R.id.btn_prev).setOnClickListener {
            if (index > 0) show(index - 1)
        }
        findViewById<Button>(R.id.btn_next).setOnClickListener {
            if (index < entries.size - 1) show(index + 1)
        }
        btnUse.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("load_recipients", entries.joinToString("\n") { "${it.name}, ${it.phone}" })
            setResult(RESULT_OK, intent)
            finish()
        }

        show(0)
    }

    private fun show(pos: Int) {
        if (entries.isEmpty()) return
        index = pos
        val e = entries[index]
        tvRecipient.text = e.name
        tvPhone.text = e.phone
        tvMessage.text = e.text
        tvProgress.text = "${index + 1} / ${entries.size}"
        lvQuick.setItemChecked(index, true)
    }
}
