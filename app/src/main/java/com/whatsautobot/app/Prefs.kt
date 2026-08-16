package com.whatsautobot.app

import android.content.Context

object Prefs {
    private const val FILE = "whatsautobot"
    const val KEY_TEMPLATE = "template"
    const val KEY_RECIPIENTS = "recipients_raw"
    const val KEY_AUTO_REPLY = "auto_reply"
    const val KEY_REPLY_TEMPLATE = "reply_template"
    const val KEY_DELAY_MS = "delay_ms"

    fun get(context: Context): android.content.SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun template(context: Context): String =
        get(context).getString(KEY_TEMPLATE, "") ?: ""

    fun recipientsRaw(context: Context): String =
        get(context).getString(KEY_RECIPIENTS, "") ?: ""

    fun autoReply(context: Context): Boolean =
        get(context).getBoolean(KEY_AUTO_REPLY, false)

    fun replyTemplate(context: Context): String =
        get(context).getString(KEY_REPLY_TEMPLATE, "") ?: ""

    fun delayMs(context: Context): Long =
        get(context).getLong(KEY_DELAY_MS, 2500)
}