package com.whatsautobot.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WaAutoSendService : AccessibilityService() {

    companion object {
        private const val TAG = "WhatsAutoBot"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        ensureRunning()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString()?.contains("whatsapp") != true) return

        when (AutoMode.current) {
            AutoMode.SEND -> handleSend(event)
            AutoMode.SCAN -> handleScan(event)
            AutoMode.CAPTURE -> handleCapture(event)
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
        handler.post { when (AutoMode.current) {
            AutoMode.SEND -> advanceAndOpen()
            AutoMode.SCAN -> { if (ScanState.active) openScanChat() }
            else -> {}
        } }
    }

    private fun advanceAndOpen() {
        if (!isServiceEnabled()) return
        if (!WaQueue.isRunning) return
        val msg = WaQueue.advance() ?: run {
            WaQueue.setRunning(false)
            WaQueue.broadcast(applicationContext)
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        WaQueue.broadcast(applicationContext)
        openChat(msg)
    }

    private fun openChat(msg: PendingMessage) {
        try {
            val uri = Uri.parse(
                "https://wa.me/${msg.phone.replace("+", "")}?text=${Uri.encode(msg.text)}"
            )
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            WaQueue.stop()
            WaQueue.broadcast(applicationContext)
        }
    }

    private fun isChatReady(msg: PendingMessage): Boolean {
        val root = rootInActiveWindow ?: return false
        val send = findSendButton(root) ?: return false
        val editText = findEditText(root)
        return when {
            editText != null && !editText.text.isNullOrEmpty() -> true
            else -> findText(root, msg.text.trim().take(30).toRegex(RegexOption.IGNORE_CASE)) || isSendEnabled(send)
        }
    }

    private val sending = mutableSetOf<String>()

    private fun scheduleSend(msg: PendingMessage) {
        if (!sending.add(msg.phone + "|" + msg.text.take(20))) return
        handler.postDelayed({
            sending.remove(msg.phone + "|" + msg.text.take(20))
            tapSend(msg)
        }, Prefs.delayMs(applicationContext))
    }

    private fun tapSend(msg: PendingMessage) {
        if (!WaQueue.isRunning) return
        val root = rootInActiveWindow ?: return
        val openAndSend = { send: AccessibilityNodeInfo ->
            tapNode(send)
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
                handler.postDelayed(
                    { if (WaQueue.isRunning) advanceAndOpen() },
                    Prefs.delayMs(applicationContext)
                )
            }, Prefs.delayMs(applicationContext))
        }
        val send = findSendButton(root)
        if (send != null) {
            openAndSend(send)
        } else {
            var attempts = 0
            fun retry() {
                attempts++
                if (attempts > 8) {
                    handler.postDelayed({ advanceAndOpen() }, Prefs.delayMs(applicationContext))
                    return
                }
                val s = findSendButton(rootInActiveWindow ?: return)
                if (s != null) openAndSend(s) else handler.postDelayed({ retry() }, 700)
            }
            retry()
        }
        WaQueue.broadcast(applicationContext)
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
        handler.postDelayed({
            ScanState.probeScheduled = false
            probeScan()
        }, 2500)
    }

    private fun probeScan() {
        if (!ScanState.active) return
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
                    handler.postDelayed({ probeScan() }, 1500)
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
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            node.viewIdResourceName?.let { vid ->
                if (vid in idNames) {
                    val t = node.text?.toString()?.trim().orEmpty()
                    if (t.isNotEmpty() && t.length in 2..60) return t
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.addLast(it) }
        }
        // Fallback: largest upper-half text node.
        val screen = Rect()
        root.getBoundsInScreen(screen)
        var best: String? = null
        var bestArea = 0
        val stack2 = ArrayDeque<AccessibilityNodeInfo>()
        stack2.addLast(root)
        while (stack2.isNotEmpty()) {
            val node = stack2.removeLast()
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
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack2.addLast(it) }
        }
        return best?.takeIf { it.length in 2..60 }
    }

    private fun classify(registered: Boolean) {
        val current = ScanState.current ?: return
        ScanState.setResult(current.second, registered)
        saveScanProgress()
        ScanState.broadcast(applicationContext, if (registered) "scan_found" else "scan_skip")
        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.postDelayed({ openScanChat() }, Prefs.delayMs(applicationContext))
    }

    /** Persist progress so an interrupted scan is never lost. */
    private fun saveScanProgress() {
        val results = ScanState.allResults()
        val entries = results.map { ContactEntry(it.first, it.second, it.third) }
        ContactStore.upsert(
            applicationContext,
            ContactList("phone_scan", "Phone contacts (WhatsApp)", "phone_scan", entries.toMutableList())
        )
    }

    private fun openScanChat() {
        if (!ScanState.active) return
        val item = ScanState.pop() ?: run {
            finishScan()
            return
        }
        ScanState.probeCount = 0
        ScanState.broadcast(applicationContext, "scan_next")
        try {
            val uri = Uri.parse("https://wa.me/${item.second.replace("+", "")}")
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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

    // ---------------------------------------------------------------- CAPTURE
    private fun handleCapture(event: AccessibilityEvent) {
        if (!CaptureState.armed) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val root = rootInActiveWindow ?: return
                Log.d(TAG, "capture window state; armed=${CaptureState.armed} active=${CaptureState.active} title=${event.className}")
                if (CaptureState.active) {
                    consumeCaptureWindow(root)
                } else if (looksLikeMemberScreen(root)) {
                    Log.d(TAG, "capture: members screen detected")
                    startCapture(root)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (CaptureState.active) consumeCaptureWindow(rootInActiveWindow ?: return)
            }
        }
    }

    private fun looksLikeMemberScreen(root: AccessibilityNodeInfo): Boolean {
        // A member list typically shows a search field + many name/number rows.
        val hasSearch = findNodeByHint(root) { it.equals("Search", true) || it.contains("搜索") }
        val phoneCount = countPhoneNumbers(root)
        val nameCount = countNameRows(root)
        Log.d(TAG, "looksLikeMemberScreen: search=$hasSearch phone=$phoneCount names=$nameCount")
        return hasSearch != null || phoneCount >= 2 || nameCount >= 5
    }

    private fun startCapture(root: AccessibilityNodeInfo) {
        CaptureState.active = true
        CaptureState.label = readGroupName(root) ?: CaptureState.label
        CaptureState.broadcast(applicationContext, "capture_start")
        collectCaptureRows(root)
        // begin scrolling loop
        handler.removeCallbacks(captureTick)
        handler.postDelayed(captureTick, 1800)
    }

    private fun consumeCaptureWindow(root: AccessibilityNodeInfo) {
        collectCaptureRows(root)
        CaptureState.broadcast(applicationContext, "capture_scan")
    }

    private val captureTick = Runnable { tickCapture() }

    private fun tickCapture() {
        if (!CaptureState.active) return
        captureRowsNow()
        if (CaptureState.members.size > CaptureState.lastDumpCount) {
            CaptureState.lastDumpCount = CaptureState.members.size
            CaptureState.stableCount = 0
            scrollDown()
            handler.postDelayed(captureTick, 1400)
        } else {
            CaptureState.stableCount++
            if (CaptureState.stableCount >= 4) {
                finishCapture()
            } else {
                scrollDown()
                handler.postDelayed(captureTick, 1400)
            }
        }
    }

    private fun captureRowsNow() {
        val root = rootInActiveWindow ?: return
        collectCaptureRows(root)
    }

    private fun collectCaptureRows(root: AccessibilityNodeInfo) {
        val rows = extractRows(root)
        for (r in rows) {
            CaptureState.addMember(r.first, r.second)
        }
    }

    /** Group visible text tokens by screen rows, then pull name + phone from each. */
    private fun extractRows(root: AccessibilityNodeInfo): List<Pair<String, String>> {
        data class Token(val text: String, val y: Int, val isPhone: Boolean)

        val tokens = mutableListOf<Token>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val t = node.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() && node.isVisibleToUser && !node.isEditable) {
                // Skip obvious chrome / counts.
                if (t.matches(Regex("[\\d,]+ members?")) || t.matches(Regex("[\\d,]+ 位成员"))) continue
                if (t.equals("Search", true) || t.contains("搜索")) continue
                val r = Rect()
                node.getBoundsInScreen(r)
                val isPhone = extractPhone(t) != null
                tokens.add(Token(t, (r.top + r.bottom) / 2, isPhone))
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.addLast(it) }
        }

        // Order by vertical position.
        tokens.sortWith(compareBy({ it.y }, { it.isPhone }))

        // Group into rows: tokens whose y are within a tolerance belong to the same row.
        val rows = mutableListOf<MutableList<Token>>()
        for (tk in tokens) {
            val last = rows.lastOrNull()
            if (last != null && kotlin.math.abs(tk.y - (last.last().y)) <= 55) {
                last.add(tk)
            } else {
                rows.add(mutableListOf(tk))
            }
        }

        val out = mutableListOf<Pair<String, String>>()
        for (row in rows) {
            if (row.size == 1 && row[0].isPhone) {
                // Only a bare number with no name text.
                out.add(("" to row[0].text))
                continue
            }
            val phone = row.filter { it.isPhone }.map { it.text }.firstOrNull()
            val name = row
                .filter { !it.isPhone && it.text.isNotEmpty() }
                .map { it.text }
                .firstOrNull()
            out.add((name ?: phone ?: "") to (phone ?: ""))
        }
        return out
    }

    private fun readGroupName(root: AccessibilityNodeInfo): String? {
        val screen = Rect()
        root.getBoundsInScreen(screen)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var best: String? = null
        var bestScore = -1
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.isEditable) continue // skip the search field itself
            val t = node.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() && extractPhone(t) == null) {
                // Skip member counts like "506 members".
                if (t.matches(Regex("[\\d,\\s]+(members?|位成员|参与者?)"))) continue
                // Skip the search placeholder/hint.
                if (t.equals("Search", true) || t.contains("搜索") || t.contains("查找")) continue
                val r = Rect()
                node.getBoundsInScreen(r)
                // Group name sits in the top bar (upper half of screen), ideally with some width.
                val topRatio = (r.top.toFloat() - screen.top) / screen.height().toFloat()
                if (topRatio in 0f..0.35f) {
                    // Reward a wider, title-like node (e.g. the app bar title).
                    val score = (r.width() - r.height()) + (r.width())
                    if (score > bestScore) {
                        bestScore = score
                        best = t
                    }
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.addLast(it) }
        }
        return best?.takeIf { it.length in 1..80 }
    }

    private fun finishCapture() {
        val entries = CaptureState.members.values.map { m ->
            ContactEntry(m.name.ifBlank { m.phone }, m.phone, true)
        }
        val id = "group_" + System.currentTimeMillis()
        ContactStore.upsert(
            applicationContext,
            ContactList(id, CaptureState.label, "group_import", entries.toMutableList())
        )
        CapturedIdHolder.last = id
        CaptureState.broadcast(applicationContext, "capture_done")
        CaptureState.disarm()
        handler.removeCallbacks(captureTick)
        AutoMode.current = AutoMode.NONE
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun scrollDown() {
        val view = rootInActiveWindow ?: return
        val r = Rect()
        view.getBoundsInScreen(r)
        val fromY = (r.top + r.bottom * 4 / 5).toFloat()
        val toY = (r.top + r.bottom / 5).toFloat()
        val x = r.centerX().toFloat()
        val path = Path()
        path.moveTo(x, fromY)
        path.lineTo(x, toY)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun findInvalidNumber(root: AccessibilityNodeInfo): Boolean {
        val patterns = listOf(
            "isn't on WhatsApp", "is not on WhatsApp", "invalid phone number",
            "号码无效", "未注册 WhatsApp", "not using WhatsApp"
        )
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val text = (node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty())
            if (patterns.any { text.contains(it, true) }) return true
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.addLast(it) }
        }
        return false
    }

    private fun findNodeByHint(root: AccessibilityNodeInfo, match: (String) -> Boolean): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val hint = node.hintText?.toString().orEmpty()
            val cd = node.contentDescription?.toString().orEmpty()
            val txt = node.text?.toString().orEmpty()
            if (match(hint) || match(cd) || match(txt)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.addLast(it) }
        }
        return null
    }

    private fun countPhoneNumbers(root: AccessibilityNodeInfo): Int {
        var count = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            node.text?.toString()?.let { if (extractPhone(it) != null) count++ }
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.addLast(it) }
        }
        return count
    }

    /** Rough count of plausible member-row name rows (short-ish text spans without numbers). */
    private fun countNameRows(root: AccessibilityNodeInfo): Int {
        var count = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            val t = node.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() && t.length in 4..60 && extractPhone(t) == null && !t.contains("\n") && node.isVisibleToUser) {
                count++
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.addLast(it) }
        }
        return count
    }

    private fun extractPhone(text: String): String? {
        val t = text.replace(" ", "").replace("-", "")
        val m = Regex("\\+?\\d{9,13}").find(t) ?: return null
        var p = m.value
        if (!p.startsWith("+")) p = "+$p"
        val digits = p.filter { it.isDigit() }
        if (digits.length in 9..12) return "+$digits"
        return null
    }

    // ---------------------------------------------------------------- shared helpers
    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val ids = setOf("com.whatsapp:id/send", "com.whatsapp:id/entry_send", "com.whatsapp:id/fab_send")
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            node.viewIdResourceName?.let { if (it in ids) return node }
            node.contentDescription?.toString()?.let { cd ->
                if (cd.equals("Send", true) || cd.equals("发送", true)) return node
            }
            node.text?.toString()?.let { t ->
                if (t.equals("Send", true)) return node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.addLast(it) }
        }
        return null
    }

    private fun isSendEnabled(send: AccessibilityNodeInfo): Boolean = send.isEnabled

    private fun findEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.className?.toString()?.contains("EditText") == true || node.isEditable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.addLast(it) }
        }
        return null
    }

    private fun findText(root: AccessibilityNodeInfo, regex: Regex): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            node.text?.toString()?.let { if (regex.containsMatchIn(it)) return true }
            node.contentDescription?.toString()?.let { if (regex.containsMatchIn(it)) return true }
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.addLast(it) }
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
        super.onDestroy()
    }
}

object CapturedIdHolder {
    var last: String? = null
}