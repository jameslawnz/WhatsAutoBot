package com.whatsautobot.app

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var etTemplate: EditText
    private lateinit var etRecipients: EditText
    private lateinit var etReply: EditText
    private lateinit var btnToggleReply: Button
    private lateinit var tvLists: TextView
    private var replyEnabled = false

    private val queueReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(WaQueue.EXTRA_STATE)
            val count = intent.getIntExtra(WaQueue.EXTRA_COUNT, 0)
            tvStatus.text = when (state) {
                WaQueue.STATE_OPEN_NEXT -> "Sending... $count left in queue"
                else -> "Status: idle"
            }
        }
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(ScanState.EXTRA_STATE)
            val count = intent.getIntExtra(ScanState.EXTRA_COUNT, 0)
            val found = intent.getIntExtra(ScanState.EXTRA_FOUND, 0)
            tvStatus.text = when (state) {
                "scan_next" -> "Scanning contacts... $count left, $found on WhatsApp"
                "scan_found" -> "Found a WhatsApp contact ($found so far)..."
                "scan_done" -> "Scan complete. $found on WhatsApp, $count skipped."
                "scan_skip" -> "Scanning... $found on WhatsApp so far"
                else -> tvStatus.text.toString()
            }
            if (state == "scan_done") {
                loadListIntoRecipients(ContactStore.SOURCE_PHONE_SCAN, onlyOnWhatsApp = true)
            }
            refreshLists()
        }
    }

    private val captureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(CaptureState.EXTRA_STATE)
            val count = intent.getIntExtra(CaptureState.EXTRA_COUNT, 0)
            val label = intent.getStringExtra(CaptureState.EXTRA_LABEL)
            tvStatus.text = when (state) {
                "capture_start" -> "Capturing group members... found $count so far"
                "capture_scan" -> "Capturing group members... found $count"
                "capture_done" -> "Imported $count members from \"$label\""
                else -> tvStatus.text.toString()
            }
            if (state == "capture_done") {
                CapturedIdHolder.last?.let { loadListIntoRecipients(it) }
            }
            refreshLists()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tv_status)
        etTemplate = findViewById(R.id.et_template)
        etRecipients = findViewById(R.id.et_recipients)
        etReply = findViewById(R.id.et_reply_template)
        btnToggleReply = findViewById(R.id.btn_toggle_reply)
        tvLists = findViewById(R.id.tv_lists)

        etTemplate.setText(Prefs.template(this))
        etRecipients.setText(Prefs.recipientsRaw(this))
        etReply.setText(Prefs.replyTemplate(this))
        replyEnabled = Prefs.autoReply(this)
        updateReplyButton()

        if (etTemplate.text.isNullOrBlank()) {
            etTemplate.setText("Hi {name}, this is a test message from your WhatsApp automation app.")
        }
        if (etReply.text.isNullOrBlank()) {
            etReply.setText("Hi {sender}, I received your message and will get back to you shortly.")
        }

        registerReceiver(queueReceiver, IntentFilter(WaQueue.BROADCAST), RECEIVER_NOT_EXPORTED)
        registerReceiver(scanReceiver, IntentFilter(ScanState.BROADCAST), RECEIVER_NOT_EXPORTED)
        registerReceiver(captureReceiver, IntentFilter(CaptureState.BROADCAST), RECEIVER_NOT_EXPORTED)

        waitPermissionLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                pendingOnGranted?.invoke()
            } else {
                Toast.makeText(this, "Contact permission needed to scan your phone contacts", Toast.LENGTH_SHORT).show()
                openAppSettings()
            }
            pendingOnGranted = null
        }

        viewContactsLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val listId = result.data?.getStringExtra("load_list_id")
            if (listId != null) {
                loadListIntoRecipients(listId)
            }
        }

        previewLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val recipients = result.data?.getStringExtra("load_recipients")
            if (recipients != null) {
                etRecipients.setText(recipients)
            }
        }

        if (replyEnabled) {
            sendBroadcast(Intent(WaQueue.BROADCAST))
        }
        refreshLists()

        findViewById<Button>(R.id.btn_test).setOnClickListener {
            val template = etTemplate.text.toString()
            if (template.isBlank()) {
                Toast.makeText(this, "Set a message template first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveInputs()
            // One test message, ONLY to the test number.
            val test = PendingMessage(name = "Test", phone = "+6421668078", text = personalise(template, "Test"))
            WaQueue.stop()
            WaQueue.enqueue(test)
            WaQueue.setRunning(true)
            AutoMode.current = AutoMode.SEND
            sendBroadcast(Intent(WaQueue.BROADCAST))
            Toast.makeText(this, "Sending test to +64 216 680 78", Toast.LENGTH_SHORT).show()
            openChatForCurrent()
        }

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            val template = etTemplate.text.toString()
            if (template.isBlank()) {
                Toast.makeText(this, "Set a message template first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveInputs()
            val list = parseRecipients()
            if (list.isEmpty()) {
                Toast.makeText(this, "No valid recipients", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            WaQueue.stop()
            list.forEach { (name, phone) ->
                WaQueue.enqueue(PendingMessage(name, phone, personalise(template, name)))
            }
            WaQueue.setRunning(true)
            AutoMode.current = AutoMode.SEND
            tvStatus.text = "Queue ready: ${WaQueue.size()} message(s). Starting..."
            sendBroadcast(Intent(WaQueue.BROADCAST))
            openChatForCurrent()
        }

        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            WaQueue.stop()
            ScanState.stop()
            CaptureState.disarm()
            AutoMode.current = AutoMode.NONE
            tvStatus.text = "Status: idle"
            Toast.makeText(this, "Queue stopped", Toast.LENGTH_SHORT).show()
        }

        btnToggleReply.setOnClickListener {
            replyEnabled = !replyEnabled
            Prefs.get(this).edit().putBoolean(Prefs.KEY_AUTO_REPLY, replyEnabled).apply()
            etReply.text?.let { Prefs.get(this).edit().putString(Prefs.KEY_REPLY_TEMPLATE, it.toString()).apply() }
            updateReplyButton()
            Toast.makeText(this, if (replyEnabled) "Auto-respond enabled" else "Auto-respond disabled", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_scan_contacts).setOnClickListener {
            requestContactPermission {
                startContactScan()
            }
        }

        findViewById<Button>(R.id.btn_capture_group).setOnClickListener {
            startGroupCapture()
        }

        findViewById<Button>(R.id.btn_view_contacts).setOnClickListener {
            viewContactsLauncher.launch(Intent(this, ContactsActivity::class.java))
        }

        findViewById<Button>(R.id.btn_preview).setOnClickListener {
            saveInputs()
            val recipients = etRecipients.text.toString()
            if (recipients.isBlank()) {
                Toast.makeText(this, "Add recipients first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, PreviewActivity::class.java)
                .putExtra("recipients", recipients)
            previewLauncher.launch(intent)
        }

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btn_notifications).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    private fun openChatForCurrent() {
        // The accessibility service needs to be enabled; it drives the rest.
        val msg = WaQueue.peek() ?: return
        WaQueue.setCurrent(msg)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Phones.waMe(msg.phone, msg.text)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "Open WhatsApp first: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun personalise(template: String, name: String): String =
        template.replace("{name}", name)

    // "Name, +64216..." per line
    private fun parseRecipients(): List<Pair<String, String>> =
        Phones.parseRecipients(etRecipients.text.toString())

    private fun saveInputs() {
        Prefs.get(this).edit()
            .putString(Prefs.KEY_TEMPLATE, etTemplate.text.toString())
            .putString(Prefs.KEY_RECIPIENTS, etRecipients.text.toString())
            .putString(Prefs.KEY_REPLY_TEMPLATE, etReply.text.toString())
            .apply()
    }

    private fun updateReplyButton() {
        btnToggleReply.text = if (replyEnabled) "Disable auto-respond" else "Enable auto-respond"
    }

    private lateinit var viewContactsLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
    private lateinit var previewLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
    private lateinit var waitPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>
    private var pendingOnGranted: (() -> Unit)? = null

    private fun requestContactPermission(onGranted: () -> Unit) {
        val perm = android.Manifest.permission.READ_CONTACTS
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
            return
        }
        pendingOnGranted = onGranted
        waitPermissionLauncher.launch(perm)
    }

    private fun startContactScan() {
        val contacts = try {
            ContactReader(this).allNumbers()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not read contacts: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }
        if (contacts.isEmpty()) {
            Toast.makeText(this, "No phone contacts found", Toast.LENGTH_SHORT).show()
            return
        }
        WaQueue.stop()
        ScanState.start(contacts)
        AutoMode.current = AutoMode.SCAN
        tvStatus.text = "Scanning ${contacts.size} phone contacts for WhatsApp..."
        ScanState.broadcast(this, "scan_next")
        openFirstScanChat()
    }

    private fun openFirstScanChat() {
        val item = ScanState.pop() ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Phones.waMe(item.second)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "Open WhatsApp first: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startGroupCapture() {
        WaQueue.stop()
        ScanState.stop()
        AutoMode.current = AutoMode.CAPTURE
        CaptureState.reset()
        tvStatus.text = "Ready. In WhatsApp, open a group and tap 'View all members'."
        Toast.makeText(this, "Open a WhatsApp group and view its members list to auto-capture.", Toast.LENGTH_LONG).show()
    }

    private fun refreshLists() {
        val lists = ContactStore.all(this).filter { it.source != "" }
        if (lists.isEmpty()) {
            tvLists.text = ""
            return
        }
        val text = lists.joinToString("\n") { l ->
            val onW = if (l.source == ContactStore.SOURCE_PHONE_SCAN)
                " (${l.entries.count { it.onWhatsApp }} on WhatsApp)" else ""
            "• ${l.label}: ${l.entries.size} contact(s)$onW"
        }
        tvLists.text = text
    }

    private fun loadListIntoRecipients(listId: String, onlyOnWhatsApp: Boolean = false) {
        val list = ContactStore.listOf(this, listId) ?: return
        val entries = list.entries.filter { it.phone.isNotBlank() && (!onlyOnWhatsApp || it.onWhatsApp) }
        if (entries.isEmpty()) return
        etRecipients.setText(
            entries.joinToString("\n") { "${it.name}, ${it.phone}" }
        )
        tvStatus.text = "Loaded ${entries.size} recipients into the list."
        Toast.makeText(this, "Loaded ${entries.size} recipients.", Toast.LENGTH_SHORT).show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, WaAutoSendService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.lastIndexOf('/') != -1 && expected.flattenToString().equals(it, true) }
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabled.split(':').any { it.contains("WaAutoRespondService") }
    }

    private fun checkPermissionsAndPrompt() {
        val missing = mutableListOf<String>()

        if (!isAccessibilityEnabled()) missing.add("Accessibility")
        if (!isNotificationAccessEnabled()) missing.add("Notification access")
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            missing.add("Contacts")
        }

        if (missing.isEmpty()) return

        val advice = missing.joinToString(", ")
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Permissions needed")
            .setMessage(
                "To run the WhatsApp automation, please enable:\n\n$advice\n\n" +
                "Accessibility lets the app drive WhatsApp automatically. " +
                "Notification access enables auto-respond."
            )
        if (missing.contains("Accessibility")) {
            dialog.setPositiveButton("Enable Accessibility") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        if (missing.contains("Notification access")) {
            dialog.setNegativeButton("Notification access") { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        if (missing.contains("Contacts")) {
            dialog.setNeutralButton("Allow contacts") { _, _ ->
                waitPermissionLauncher.launch(android.Manifest.permission.READ_CONTACTS)
            }
        }
        dialog.setOnDismissListener { _ ->
            val remaining = mutableListOf<String>()
            if (!isAccessibilityEnabled()) remaining.add("Accessibility")
            if (!isNotificationAccessEnabled()) remaining.add("Notification access")
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                remaining.add("Contacts")
            }
            if (remaining.isNotEmpty()) {
                Toast.makeText(this, "Still needed: ${remaining.joinToString(", ")}", Toast.LENGTH_LONG).show()
            }
        }
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndPrompt()
    }

    override fun onDestroy() {
        try { unregisterReceiver(queueReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(captureReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val RECEIVER_NOT_EXPORTED = Context.RECEIVER_NOT_EXPORTED
    }
}