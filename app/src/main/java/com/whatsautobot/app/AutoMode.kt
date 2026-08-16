package com.whatsautobot.app

/** Global mode dispatch shared between Activity and services. */
object AutoMode {
    const val NONE = 0
    const val SEND = 1
    const val SCAN = 2
    const val CAPTURE = 3
    var current: Int = NONE
}