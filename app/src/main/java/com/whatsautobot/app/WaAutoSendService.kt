package com.whatsautobot.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WaAutoSendService : AccessibilityService() {

    companion object {
        private const val TAG = "WhatsAutoBot"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var captureController: CaptureController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        captureController = CaptureController(
            context = applicationContext,
            getRoot = { rootInActiveWindow },
            dispatchGesture = { gesture -> dispatchGesture(gesture, null, null) },
            performGlobalAction = { performGlobalAction(it) },
        )
        ensureRunning()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString()?.contains("whatsapp") != true) return

        when (AutoMode.current) {
            AutoMode.SEND -> handleSend(event)
            AutoMode.SCAN -> handleScan(event)
            AutoMode.CAPTURE -> captureController?.onAccessibilityEvent(event)
            else -> {}
        }
    }

    // ---------------------------------------------------------------- SEND
    private fun handleSend(event: AccessibilityEvent) {
        if (!WaQueue.isRunning) return
        val msg = WaQueue.current ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (isChatReady(msg)) scheduleSend(msg)
            }
        }
    }

    private fun ensureRunning() {
        if (started) return
        started = true
        handler.post {
            if (AutoMode.current == AutoMode.NONE && WaQueue.restore(applicationContext)) {
                // Process was killed mid-campaign; resume sending.
                AutoMode.current = AutoMode.SEND
                advanceAndOpen()
            } else {
                when (AutoMode.current) {
                    AutoMode.SEND -> advanceAndOpen()
                    AutoMode.SCAN -> { if (ScanState.active) openScanChat() }
                    else -> {}
                }
            }
        }
    }

    private fun advanceAndOpen() {
        if (!isServiceEnabled()) return
        if (!WaQueue.isRunning) return
        if (!Throttler.canSendNow(applicationContext)) {
            // Daily quota reached or outside the send window: stop cleanly.
            WaQueue.setRunning(false)
            WaQueue.save(applicationContext)
            WaQueue.broadcast(applicationContext)
            CampaignStore.currentId(applicationContext)?.let { CampaignStore.finish(applicationContext, it, Campaign.STATUS_QUOTA) }
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        // Skip numbers already messaged within the dedup window.
        var msg = WaQueue.advance()
        while (msg != null && Throttler.wasSentRecently(applicationContext, msg.phone)) {
            CampaignStore.currentId(applicationContext)?.let { CampaignStore.increment(applicationContext, it, skipped = 1) }
            msg = WaQueue.advance()
        }
        if (msg == null) {
            WaQueue.setRunning(false)
            WaQueue.save(applicationContext)
            WaQueue.broadcast(applicationContext)
            CampaignStore.currentId(applicationContext)?.let { CampaignStore.finish(applicationContext, it) }
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        WaQueue.save(applicationContext)
        WaQueue.broadcast(applicationContext)
        openChat(msg)
    }

    private fun openChat(msg: PendingMessage) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Phones.waMe(msg.phone, msg.text)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            ErrorLog.log(applicationContext, "Failed to open chat", "${msg.phone}: ${e.message}")
            CampaignStore.currentId(applicationContext)?.let { CampaignStore.increment(applicationContext, it, failed = 1) }
            WaQueue.stop()
            WaQueue.save(applicationContext)
            WaQueue.broadcast(applicationContext)
        }
    }

    private fun isChatReady(msg: PendingMessage): Boolean {
        val root = rootInActiveWindow ?: return false
        val send = findSendButton(root) ?: return false
        val editText = findEditText(root)
        return when {
            editText != null && !editText.text.isNullOrEmpty() -> true
            else -> findText(root, Regex.escape(msg.text.trim().take(30)), ignoreCase = true) || isSendEnabled(send)
        }
    }

    private val sending = mutableSetOf<String>()

    private fun scheduleSend(msg: PendingMessage) {
        if (!sending.add(msg.phone + "|" + msg.text.take(20))) return
        handler.postDelayed({
            sending.remove(msg.phone + "|" + msg.text.take(20))
            tapSend(msg)
        }, nextDelay())
    }

    private fun tapSend(msg: PendingMessage) {
        if (!WaQueue.isRunning) return
        val root = rootInActiveWindow ?: return
        val openAndSend = { send: AccessibilityNodeInfo ->
            tapNode(send)
            recordSent(msg)
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
                handler.postDelayed(
                    { if (WaQueue.isRunning) advanceAndOpen() },
                    nextDelay()
                )
            }, nextDelay())
        }
        val send = findSendButton(root)
        if (send != null) {
            openAndSend(send)
        } else {
            var attempts = 0
            fun retry() {
                attempts++
                if (attempts > 8) {
                    handler.postDelayed({ advanceAndOpen() }, nextDelay())
                    return
                }
                val rootNow = rootInActiveWindow ?: run {
                    handler.postDelayed({ retry() }, 700)
                    return
                }
                val s = findSendButton(rootNow)
                if (s != null) openAndSend(s) else handler.postDelayed({ retry() }, 700)
            }
            retry()
        }
        WaQueue.broadcast(applicationContext)
    }

    private fun nextDelay(): Long = Throttler.policy(applicationContext).nextDelayMs()

    private fun recordSent(msg: PendingMessage) {
        Throttler.recordSend(applicationContext)
        Throttler.recordSentTo(applicationContext, msg.phone)
        Throttler.prune(applicationContext)
        CampaignStore.currentId(applicationContext)?.let {
            CampaignStore.increment(applicationContext, it, sent = 1)
            WaQueue.broadcast(applicationContext)
        }
    }

    // ---------------------------------------------------------------- SCAN
    private fun handleScan(event: AccessibilityEvent) {
        if (!ScanState.active) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> scheduleScanProbe()
        }
    }

    private fun scheduleScanProbe() {
        if (ScanState.probeScheduled) return
        ScanState.probeScheduled = true
        val gen = ScanState.generation
        handler.postDelayed({
            ScanState.probeScheduled = false
            probeScan(gen)
        }, 2500)
    }

    private fun probeScan(gen: Int) {
        if (!ScanState.active) return
        if (gen != ScanState.generation) return // a newer chat is already open
        val current = ScanState.current ?: return
        val root = rootInActiveWindow
        val hasInput = root?.let { findEditText(it) != null || findSendButton(it) != null } ?: false
        val hasInvalid = root?.let { findInvalidNumber(it) } ?: false

        if (hasInput) {
            // Chat is ready: grab the display name that WhatsApp shows in the header.
            val waName = root?.let { readChatHeaderName(it) }
            if (!waName.isNullOrBlank()) ScanState.setName(current.second, waName)
            classify(true)
            return
        }
        when {
            hasInvalid -> classify(false)
            else -> {
                // wait a couple more rounds for the chat to load
                if (ScanState.probeCount++ < 4) {
                    handler.postDelayed({ probeScan(gen) }, 1500)
                } else {
                    classify(false) // no input shown → not a usable WhatsApp chat
                }
            }
        }
    }

    private fun readChatHeaderName(root: AccessibilityNodeInfo): String? {
        // Prefer WhatsApp's known conversation-title resource ids.
        val idNames = setOf(
            "com.whatsapp:id/conversation_contact_name",
            "com.whatsapp:id/conversation_subject",
            "com.whatsapp:id/chat_name",
            "com.whatsapp:id/contact_name"
        )
        forEachNode(root) { node ->
            node.viewIdResourceName?.let { vid ->
                if (vid in idNames) {
                    val t = node.text?.toString()?.trim().orEmpty()
                    if (t.isNotEmpty() && t.length in 2..60) return t
                }
            }
        }
        // Fallback: largest upper-half text node.
        val screen = Rect()
        root.getBoundsInScreen(screen)
        var best: String? = null
        var bestArea = 0
        forEachNode(root) { node ->
            val t = node.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() && extractPhone(t) == null && node.isVisibleToUser) {
                val r = Rect()
                node.getBoundsInScreen(r)
                val area = r.width() * r.height()
                if (r.top >= screen.top && r.top < (screen.top + screen.height() * 0.5) && area > bestArea) {
                    bestArea = area
                    best = t
                }
            }
        }
        return best?.takeIf { it.length in 2..60 }
    }

    private fun classify(registered: Boolean) {
        val current = ScanState.current ?: return
        ScanState.setResult(current.second, registered)
        saveScanProgress(throttled = true)
        ScanState.broadcast(applicationContext, if (registered) "scan_found" else "scan_skip")
        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.postDelayed({ openScanChat() }, nextDelay())
    }

    /** Persist progress so an interrupted scan is never lost. Writes are throttled
     *  to every 10 contacts to keep the repeated full-store save off the main thread. */
    private fun saveScanProgress(throttled: Boolean = false) {
        if (throttled && ++scanSaveTicks % 10 != 0) return
        val results = ScanState.allResults()
        val entries = results.map { ContactEntry(it.first, it.second, it.third) }
        ContactStore.upsert(
            applicationContext,
            ContactList(ContactStore.SOURCE_PHONE_SCAN, "Phone contacts (WhatsApp)", ContactStore.SOURCE_PHONE_SCAN, entries.toMutableList())
        )
    }

    private var scanSaveTicks = 0

    private fun openScanChat() {
        if (!ScanState.active) return
        val item = ScanState.pop() ?: run {
            finishScan()
            return
        }
        ScanState.probeCount = 0
        ScanState.broadcast(applicationContext, "scan_next")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Phones.waMe(item.second)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            ScanState.setResult(item.second, false)
            finishScan()
        }
    }

    private fun finishScan() {
        saveScanProgress()
        ScanState.broadcast(applicationContext, "scan_done")
        ScanState.stop()
        AutoMode.current = AutoMode.NONE
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // ---------------------------------------------------------------- shared helpers
    private fun findInvalidNumber(root: AccessibilityNodeInfo): Boolean {
        val patterns = listOf(
            "isn't on WhatsApp", "is not on WhatsApp", "invalid phone number",
            "号码无效", "未注册 WhatsApp", "not using WhatsApp"
        )
        forEachNode(root) { node ->
            val text = (node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty())
            if (patterns.any { text.contains(it, true) }) return true
        }
        return false
    }

    private fun extractPhone(text: String): String? = Phones.extract(text)

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val ids = setOf("com.whatsapp:id/send", "com.whatsapp:id/entry_send", "com.whatsapp:id/fab_send")
        forEachNode(root) { node ->
            node.viewIdResourceName?.let { if (it in ids) return node }
            node.contentDescription?.toString()?.let { cd ->
                if (cd.equals("Send", true) || cd.equals("发送", true)) return node
            }
            node.text?.toString()?.let { t ->
                if (t.equals("Send", true)) return node
            }
        }
        return null
    }

    private fun isSendEnabled(send: AccessibilityNodeInfo): Boolean = send.isEnabled

    private fun findEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        forEachNode(root) { node ->
            if (node.className?.toString()?.contains("EditText") == true || node.isEditable) return node
        }
        return null
    }

    private fun findText(root: AccessibilityNodeInfo, pattern: String, ignoreCase: Boolean = false): Boolean {
        val regex = Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        forEachNode(root) { node ->
            node.text?.toString()?.let { if (regex.containsMatchIn(it)) return true }
            node.contentDescription?.toString()?.let { if (regex.containsMatchIn(it)) return true }
        }
        return false
    }

    private fun tapNode(node: AccessibilityNodeInfo) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val midX = (bounds.left + bounds.right) / 2f
        val midY = (bounds.top + bounds.bottom) / 2f
        val path = Path()
        path.moveTo(midX, midY)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, WaAutoSendService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.lastIndexOf('/') != -1 && expected.flattenToString().equals(it, true) }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        captureController?.onDestroy()
        super.onDestroy()
    }
}
