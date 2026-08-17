package com.whatsautobot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhonesTest {

    @Test
    fun normalize_addsNzCountryCode() {
        assertEquals("+6421234567", Phones.normalize("21234567"))
        assertEquals("+6421234567", Phones.normalize(" 21 234 567 "))
    }

    @Test
    fun normalize_stripsLeadingZero() {
        assertEquals("+6421234567", Phones.normalize("021234567"))
        assertEquals("+64211234567", Phones.normalize("0211234567"))
    }

    @Test
    fun normalize_keepsExistingCountryCode() {
        assertEquals("+6421668078", Phones.normalize("+6421668078"))
        assertEquals("+6421668078", Phones.normalize("6421668078"))
    }

    @Test
    fun normalize_rejectsNonsense() {
        assertNull(Phones.normalize(""))
        assertNull(Phones.normalize("abc"))
        assertNull(Phones.normalize("1234")) // too short even with +64
    }

    @Test
    fun extract_findsNumberInText() {
        assertEquals("+6421668078", Phones.extract("call +64 21 668 078 now"))
        assertEquals("+021668078", Phones.extract("John 021 668 078"))
    }

    @Test
    fun extract_ignoresNonNumbers() {
        assertNull(Phones.extract("Hello world"))
    }

    @Test
    fun parseRecipients_parsesNameAndPhone() {
        val out = Phones.parseRecipients("John, 0211234567\nJane, +6421668078")
        assertEquals(2, out.size)
        assertEquals("John" to "+64211234567", out[0])
        assertEquals("Jane" to "+6421668078", out[1])
    }

    @Test
    fun parseRecipients_skipsInvalidLines() {
        val out = Phones.parseRecipients("NoPhone, abc\nEmptyName, +6421668078\n")
        assertEquals(1, out.size)
        assertEquals("EmptyName" to "+6421668078", out[0])
    }
}
