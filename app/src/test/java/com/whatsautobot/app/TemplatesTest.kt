package com.whatsautobot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplatesTest {

    @Test
    fun personalise_nameAndPhone() {
        val result = Templates.personalise("Hello {name}, your number is {phone}.", name = "John", phone = "+64211234567")
        assertEquals("Hello John, your number is +64211234567.", result)
    }

    @Test
    fun personalise_senderAndMessage() {
        val result = Templates.personalise("{sender} said: {message}", sender = "Alice", message = "Hi!")
        assertEquals("Alice said: Hi!", result)
    }

    @Test
    fun personalise_unknownTokenPreserved() {
        val result = Templates.personalise("Hello {name}, use code {code}.", name = "John")
        assertEquals("Hello John, use code {code}.", result)
    }

    @Test
    fun personalise_bodyAlias() {
        val result = Templates.personalise("You said: {body}", message = "Test")
        assertEquals("You said: Test", result)
    }

    @Test
    fun outgoingHint() {
        assertTrue(Templates.outgoingHint().contains("{name}"))
        assertTrue(Templates.outgoingHint().contains("{phone}"))
    }

    @Test
    fun replyHint() {
        assertTrue(Templates.replyHint().contains("{sender}"))
        assertTrue(Templates.replyHint().contains("{message}"))
    }
}
