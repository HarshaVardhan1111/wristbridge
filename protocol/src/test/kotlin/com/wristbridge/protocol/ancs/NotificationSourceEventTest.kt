package com.wristbridge.protocol.ancs

import com.wristbridge.protocol.bytes
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSourceEventTest {
    @Test fun parsesIncomingCallWithActions() {
        val e = NotificationSourceEvent.parse(bytes(0x00, 0x18, 0x01, 0x01, 0x78, 0x56, 0x34, 0x12))!!
        assertEquals(EventId.ADDED, e.eventId)
        assertEquals(AncsIds.CATEGORY_INCOMING_CALL, e.categoryId)
        assertEquals(1, e.categoryCount)
        assertEquals(0x12345678L, e.uid)
        assertTrue(e.hasPositiveAction); assertTrue(e.hasNegativeAction)
        assertFalse(e.isPreExisting); assertFalse(e.isSilent); assertFalse(e.isImportant)
    }

    @Test fun uidIsUnsigned() {
        assertEquals(4294967295L, NotificationSourceEvent.parse(bytes(0x02, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF))!!.uid)
    }

    @Test fun preExistingFlag() {
        assertTrue(NotificationSourceEvent.parse(bytes(0x00, 0x04, 0x04, 0x02, 1, 0, 0, 0))!!.isPreExisting)
    }

    @Test fun tooShortReturnsNull() {
        assertNull(NotificationSourceEvent.parse(bytes(0x00, 0x00, 0x00, 0x00, 1, 0, 0)))
    }

    @Test fun reservedEventIdReturnsNull() {
        assertNull(NotificationSourceEvent.parse(bytes(0x03, 0, 0, 0, 1, 0, 0, 0)))
    }

    @Test fun uuidsMatchAppleSpec() {
        assertEquals(UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0"), AncsUuids.SERVICE)
        assertEquals(UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD"), AncsUuids.NOTIFICATION_SOURCE)
        assertEquals(UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9"), AncsUuids.CONTROL_POINT)
        assertEquals(UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB"), AncsUuids.DATA_SOURCE)
    }
}
