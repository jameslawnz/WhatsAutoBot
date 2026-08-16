package com.whatsautobot.app

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.widget.Toast

class WaAutoRespondService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName != "com.whatsapp") return
        if (!Prefs.autoReply(applicationContext)) return
        val n = sbn.notification ?: return
        if (n.category == Notification.CATEGORY_STATUS || n.category == Notification.CATEGORY_SYSTEM) return

        val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        val sender = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""

        val replyAction = n.actions?.firstOrNull { a ->
            a.title?.toString()?.contains("Reply", true) == true
        } ?: return

        val replyText = buildReply(sender, text)
        sendReply(replyAction, replyText, sender)
    }

    private fun buildReply(sender: String, body: String): String {
        val t = Prefs.replyTemplate(applicationContext).trim().ifEmpty { "Hi {sender}, thanks for your message." }
        return t
            .replace("{sender}", sender)
            .replace("{name}", sender)
            .replace("{message}", body)
            .replace("{body}", body)
            .trim()
    }

    private fun sendReply(action: Notification.Action, text: String, sender: String) {
        try {
            // Rebuild the action with a remote input so the reply text is delivered.
            val remoteInput = RemoteInput.Builder("remote_input").build()
            val rebuilt = Notification.Action.Builder(action)
                .addRemoteInput(remoteInput)
                .build()
            val intent = Intent()
            val results = Bundle()
            results.putCharSequence("remote_input", text)
            RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, results)
            rebuilt.actionIntent.send(this, 0, intent)
            toast("Replied to $sender")
        } catch (e: Exception) {
            toast("Auto-reply failed: ${e.message}")
        }
    }

    private fun toast(msg: String) {
        try {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }
}