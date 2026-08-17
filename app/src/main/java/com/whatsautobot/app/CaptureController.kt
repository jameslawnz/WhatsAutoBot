package com.whatsautobot.app

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Accessibility-driven capture of a WhatsApp group's member list. The user opens a
 *  group's "View all members" screen while CAPTURE mode is armed; this walks the list,
 *  auto-scrolling until the member set stops growing, then saves it to the store. */
class CaptureController(
    private val context: Context,
    private val getRoot: () -> AccessibilityNodeInfo?,
    private val dispatchGesture: (GestureDescription) -> Boolean,
    private val performGlobalAction: (Int) -> Boolean,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val captureTick = Runnable { tickCapture() }

    private companion object {
        private const val TAG = "WhatsAutoBot"
        private val MEMBER_COUNT_RE = Regex("[\\d,]+ members?")
        private val MEMBER_COUNT_ZH_RE = Regex("[\\d,]+ 位成员")
        private val GROUP_MEMBER_COUNT_RE = Regex("[\\d,\\s]+(members?|位成员|参与者?)")
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!CaptureState.armed) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val root = getRoot() ?: return
                Log.d(TAG, "capture window state; armed=${CaptureState.armed} active=${CaptureState.active} title=${event.className}")
                if (CaptureState.active) {
                    consumeCaptureWindow(root)
                } else if (looksLikeMemberScreen(root)) {
                    Log.d(TAG, "capture: members screen detected")
                    startCapture(root)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (CaptureState.active) consumeCaptureWindow(getRoot() ?: return)
            }
        }
    }

    fun onDestroy() {
        handler.removeCallbacks(captureTick)
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
        CaptureState.broadcast(context, "capture_start")
        collectCaptureRows(root)
        // begin scrolling loop
        handler.removeCallbacks(captureTick)
        handler.postDelayed(captureTick, 1800)
    }

    private fun consumeCaptureWindow(root: AccessibilityNodeInfo) {
        collectCaptureRows(root)
        CaptureState.broadcast(context, "capture_scan")
    }

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
        val root = getRoot() ?: return
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
        forEachNode(root) { node ->
            val t = node.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() && node.isVisibleToUser && !node.isEditable) {
                // Skip obvious chrome / counts.
                if (MEMBER_COUNT_RE.matches(t) || MEMBER_COUNT_ZH_RE.matches(t)) return@forEachNode
                if (t.equals("Search", true) || t.contains("搜索")) return@forEachNode
                val r = Rect()
                node.getBoundsInScreen(r)
                val isPhone = Phones.extract(t) != null
                tokens.add(Token(t, (r.top + r.bottom) / 2, isPhone))
            }
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
        var best: String? = null
        var bestScore = -1
        forEachNode(root) { node ->
            if (node.isEditable) return@forEachNode // skip the search field itself
            val t = node.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() && Phones.extract(t) == null) {
                // Skip member counts like "506 members".
                if (GROUP_MEMBER_COUNT_RE.matches(t)) return@forEachNode
                // Skip the search placeholder/hint.
                if (t.equals("Search", true) || t.contains("搜索") || t.contains("查找")) return@forEachNode
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
        }
        return best?.takeIf { it.length in 1..80 }
    }

    private fun finishCapture() {
        val entries = CaptureState.members.values.map { m ->
            ContactEntry(m.name.ifBlank { m.phone }, m.phone, onWhatsApp = m.phone.isNotBlank())
        }
        val id = "group_" + System.currentTimeMillis()
        ContactStore.upsert(
            context,
            ContactList(id, CaptureState.label, ContactStore.SOURCE_GROUP_IMPORT, entries.toMutableList())
        )
        CapturedIdHolder.last = id
        CaptureState.broadcast(context, "capture_done")
        CaptureState.disarm()
        handler.removeCallbacks(captureTick)
        AutoMode.current = AutoMode.NONE
        performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
    }

    private fun scrollDown() {
        val view = getRoot() ?: return
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
        dispatchGesture(gesture)
    }

    private fun findNodeByHint(root: AccessibilityNodeInfo, match: (String) -> Boolean): AccessibilityNodeInfo? {
        forEachNode(root) { node ->
            val hint = node.hintText?.toString().orEmpty()
            val cd = node.contentDescription?.toString().orEmpty()
            val txt = node.text?.toString().orEmpty()
            if (match(hint) || match(cd) || match(txt)) return node
        }
        return null
    }

    private fun countPhoneNumbers(root: AccessibilityNodeInfo): Int {
        var count = 0
        forEachNode(root) { node ->
            node.text?.toString()?.let { if (Phones.extract(it) != null) count++ }
        }
        return count
    }

    /** Rough count of plausible member-row name rows (short-ish text spans without numbers). */
    private fun countNameRows(root: AccessibilityNodeInfo): Int {
        var count = 0
        forEachNode(root) { node ->
            val t = node.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() && t.length in 4..60 && Phones.extract(t) == null && !t.contains("\n") && node.isVisibleToUser) {
                count++
            }
        }
        return count
    }
}
