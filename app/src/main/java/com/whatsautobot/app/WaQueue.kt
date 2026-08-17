package com.whatsautobot.app

import android.content.Context
import android.content.Intent

data class PendingMessage(
    val name: String,
    val phone: String,
    val text: String,
)

object WaQueue {
    const val BROADCAST = "com.whatsautobot.app.QUEUE"
    const val EXTRA_STATE = "state"
    const val EXTRA_COUNT = "count"
    const val EXTRA_CURRENT = "current"

    const val STATE_IDLE = "idle"
    const val STATE_WAITING_CHAT = "waiting_chat"
    const val STATE_OPEN_NEXT = "open_next"

    private val messages = ArrayDeque<PendingMessage>()
    var current: PendingMessage? = null
        private set
    var isRunning: Boolean = false
        private set

    fun enqueue(msg: PendingMessage) {
        messages.addLast(msg)
    }

    fun size() = messages.size

    fun peek(): PendingMessage? = messages.firstOrNull()

    fun pop(): PendingMessage? = if (messages.isNotEmpty()) messages.removeFirst() else null

    fun stop() {
        isRunning = false
        messages.clear()
        current = null
    }

    fun setRunning(v: Boolean) {
        isRunning = v
    }

    fun setCurrent(msg: PendingMessage?) {
        current = msg
    }

    fun advance(): PendingMessage? {
        current = pop()
        return current
    }

    fun broadcast(context: Context) {
        val i = Intent(BROADCAST)
            .setPackage(context.packageName)
            .putExtra(EXTRA_STATE, if (isRunning) STATE_OPEN_NEXT else STATE_IDLE)
            .putExtra(EXTRA_COUNT, size())
        context.sendBroadcast(i)
    }
}