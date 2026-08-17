package com.whatsautobot.app

import android.view.accessibility.AccessibilityNodeInfo

/** Depth-first walk of an accessibility node tree (pre-order). Inline so the
 *  lambda can use non-local returns (e.g. `return node` inside find helpers). */
inline fun forEachNode(root: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Unit) {
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        action(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { stack.addLast(it) }
    }
}
