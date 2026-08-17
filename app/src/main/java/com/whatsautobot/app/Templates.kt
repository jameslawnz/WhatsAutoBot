package com.whatsautobot.app

/** Message personalisation placeholders, shared across sender, preview and auto-reply. */
object Templates {
    const val NAME = "{name}"
    const val PHONE = "{phone}"
    const val SENDER = "{sender}"
    const val MESSAGE = "{message}"
    const val BODY = "{body}"

    /** Replace known placeholders. Unused tokens keep their literal text. */
    fun personalise(
        template: String,
        name: String = "",
        phone: String = "",
        sender: String = "",
        message: String = "",
    ): String = template
        .replace(NAME, name)
        .replace(PHONE, phone)
        .replace(SENDER, sender)
        .replace(MESSAGE, message)
        .replace(BODY, message)

    /** Placeholder hints shown in the UI. */
    fun outgoingHint(): String =
        "{name} and {phone} are replaced with each recipient's details."

    fun replyHint(): String =
        "{sender} and {message} are available in the reply."
}