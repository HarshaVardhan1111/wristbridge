package com.wristbridge.protocol.ancs

import com.wristbridge.protocol.bytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataSourceAssemblerTest {
    // 00 | 78 56 34 12 | 00 05 00 "com.a" | 01 02 00 "Hi" | 03 00 00
    private val full = bytes(0x00, 0x78, 0x56, 0x34, 0x12) + bytes(0x00, 0x05, 0x00) + "com.a".toByteArray() +
        bytes(0x01, 0x02, 0x00) + "Hi".toByteArray() + bytes(0x03, 0x00, 0x00)
    private val ids = listOf(AncsIds.ATTR_APP_IDENTIFIER, AncsIds.ATTR_TITLE, AncsIds.ATTR_MESSAGE)
    private val expected = NotificationAttributes(0x12345678L, mapOf(0 to "com.a", 1 to "Hi", 3 to ""))

    @Test fun singleFragment() {
        val a = DataSourceAssembler(); a.begin(0x12345678L, ids)
        assertEquals(expected, a.append(full))
    }

    @Test fun splitAtEveryIndexGivesSameResult() {
        for (i in 1 until full.size) {
            val a = DataSourceAssembler(); a.begin(0x12345678L, ids)
            assertNull("split at $i", a.append(full.copyOfRange(0, i)))
            assertEquals("split at $i", expected, a.append(full.copyOfRange(i, full.size)))
        }
    }

    @Test fun multiByteCharacterSplitAcrossFragments() {
        val title = "Hé😀".toByteArray() // 1 + 2 + 4 bytes
        val msg = bytes(0x00, 1, 0, 0, 0, 0x01, title.size, 0x00) + title
        val a = DataSourceAssembler(); a.begin(1, listOf(AncsIds.ATTR_TITLE))
        assertNull(a.append(msg.copyOfRange(0, 12))) // header is 8 bytes; "H"=8, "é"=9..10, "😀"=11..14 → cuts inside "😀"
        assertEquals(NotificationAttributes(1, mapOf(1 to "Hé😀")), a.append(msg.copyOfRange(12, msg.size)))
    }

    @Test fun nothingExpectedReturnsNull() {
        assertNull(DataSourceAssembler().append(full))
    }

    @Test fun otherUidIsIgnoredAndCorrectResponseStillParses() {
        val a = DataSourceAssembler(); a.begin(0x12345678L, ids)
        assertNull(a.append(bytes(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)))
        assertEquals(expected, a.append(full))
    }

    @Test fun resetDiscardsPartial() {
        val a = DataSourceAssembler(); a.begin(0x12345678L, ids)
        a.append(full.copyOfRange(0, 9)); a.reset()
        assertNull(a.append(full.copyOfRange(9, full.size)))
    }

    @Test fun bytesAfterCompletionAreIgnored() {
        val a = DataSourceAssembler(); a.begin(0x12345678L, ids)
        assertEquals(expected, a.append(full + bytes(0xAA)))
        assertNull(a.append(bytes(0xBB)))
    }
}
