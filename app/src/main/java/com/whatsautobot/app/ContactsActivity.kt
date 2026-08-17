package com.whatsautobot.app

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ContactsActivity : AppCompatActivity() {

    private lateinit var tvInfo: TextView
    private lateinit var list: ListView

    private var currentListId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contacts)

        tvInfo = findViewById(R.id.tv_contacts_info)
        list = findViewById(R.id.lv_contacts)
        val btnUse = findViewById<Button>(R.id.btn_use_in_sender)

        val lists = ContactStore.all(this).filter { it.source != "" }
        if (lists.isEmpty()) {
            tvInfo.text = "No saved contacts yet. Import from a phone scan or a WhatsApp group in the main screen."
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, emptyList<String>())
            btnUse.isEnabled = false
            return
        }

        tvInfo.text = "${lists.sumOf { it.entries.size }} contacts across ${lists.size} list(s). Tap a list to view its contacts."
        // Show list names; set a tag with the list id.
        val names = lists.map { it.label }
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getView(position, convertView, parent)
                v.setTag(lists[position].id)
                return v
            }
        }
        list.adapter = adapter

        var selected: ContactList? = null
        list.setOnItemClickListener { _, v, _, _ ->
            v.tag?.toString()?.let { id ->
                val l = ContactStore.listOf(this, id)
                if (l != null) {
                    selected = l
                    currentListId = id
                    showEntries(l)
                }
            }
        }

        btnUse.setOnClickListener {
            val l = selected ?: lists.firstOrNull()
            if (l == null) return@setOnClickListener
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("load_list_id", l.id)
            setResult(RESULT_OK, intent)
            finish()
            Toast.makeText(this, "Loaded \"${l.label}\" into the sender", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_clear_selected).setOnClickListener {
            val id = currentListId ?: return@setOnClickListener
            ContactStore.removeList(this, id)
            Toast.makeText(this, "List deleted", Toast.LENGTH_SHORT).show()
            recreate()
        }

        findViewById<Button>(R.id.btn_rename_selected).setOnClickListener {
            val id = currentListId ?: return@setOnClickListener
            val list = ContactStore.listOf(this, id) ?: return@setOnClickListener
            val input = android.widget.EditText(this)
            input.setText(list.label)
            input.setSingleLine(true)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Rename list")
                .setMessage("Give this contact list a name:")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val label = input.text.toString().trim()
                    if (label.isNotEmpty()) {
                        ContactStore.renameList(this, id, label)
                        Toast.makeText(this, "Renamed to \"$label\"", Toast.LENGTH_SHORT).show()
                        recreate()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showEntries(l: ContactList) {
        tvInfo.text = "${l.label} — ${l.entries.size} contact(s)"
        val rows = l.entries.map {
            val flag = if (l.source == ContactStore.SOURCE_PHONE_SCAN) (if (it.onWhatsApp) " · on WhatsApp" else " · not on WhatsApp") else ""
            val name = it.name.ifBlank { it.phone }
            val phone = it.phone.ifBlank { "(no number)" }
            "$name, $phone$flag"
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }
}
