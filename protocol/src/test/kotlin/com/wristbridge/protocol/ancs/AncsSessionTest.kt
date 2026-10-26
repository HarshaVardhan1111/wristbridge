package com.wristbridge.protocol.ancs

import com.wristbridge.protocol.ancs.EventId.ADDED
import com.wristbridge.protocol.ancs.EventId.REMOVED
import com.wristbridge.protocol.bytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AncsSessionTest {
    private class FakeIo : AncsSession.Io {
        val writes = mutableListOf<ByteArray>()
        var timerStarts = 0
        var timerCancels = 0
        val events = mutableListOf<NotificationSourceEvent>()
        val notifications = mutableListOf<Pair<NotificationSourceEvent, NotificationAttributes>>()
        val dropped = mutableListOf<Pair<Long, String>>()

        override fun writeControlPoint(command: ByteArray) { writes += command }
        override fun startResponseTimer() { timerStarts++ }
        override fun cancelResponseTimer() { timerCancels++ }
        override fun onEvent(event: NotificationSourceEvent) { events += event }
        override fun onNotification(event: NotificationSourceEvent, attributes: NotificationAttributes) {
            notifications += event to attributes
        }
        override fun onRequestDropped(uid: Long, reason: String) { dropped += uid to reason }
    }

    private val io = FakeIo()
    private val s = AncsSession(io)

    private fun event(id: EventId, flags: Int = 0, uid: Long) = bytes(
        id.value, flags, AncsIds.CATEGORY_SOCIAL, 1,
        (uid and 0xFF).toInt(), ((uid shr 8) and 0xFF).toInt(), ((uid shr 16) and 0xFF).toInt(), ((uid shr 24) and 0xFF).toInt(),
    )

    private fun phase1RequestFor(uid: Long) =
        ControlPointCommands.getNotificationAttributes(uid, AncsSession.PHASE1_ATTRIBUTES)

    /** Same layout as DataSourceAssemblerTest.full: app "com.a", title "Hi", empty message. */
    private fun responseFor(uid: Long) = phase1RequestFor(uid).copyOfRange(0, 5) +
        bytes(0x00, 0x05, 0x00) + "com.a".toByteArray() +
        bytes(0x01, 0x02, 0x00) + "Hi".toByteArray() + bytes(0x03, 0x00, 0x00)

    @Test fun newNotificationIsFetchedAndDelivered() {
        s.onNotificationSource(event(ADDED, flags = 0, uid = 0x12345678))
        assertArrayEquals(phase1RequestFor(0x12345678), io.writes.single())
        assertEquals(1, io.timerStarts)
        s.onDataSource(responseFor(0x12345678))
        assertEquals("Hi", io.notifications.single().second.values[AncsIds.ATTR_TITLE])
        assertEquals(1, io.timerCancels)
    }

    @Test fun preExistingEventsAreNotFetched() {
        repeat(50) { s.onNotificationSource(event(ADDED, flags = AncsIds.FLAG_PRE_EXISTING, uid = it.toLong())) }
        assertTrue(io.writes.isEmpty()); assertEquals(50, io.events.size)
    }

    @Test fun onlyOneRequestInFlight() {
        s.onNotificationSource(event(ADDED, uid = 1)); s.onNotificationSource(event(ADDED, uid = 2))
        assertEquals(1, io.writes.size)
        s.onDataSource(responseFor(1))
        assertEquals(2, io.writes.size); assertArrayEquals(phase1RequestFor(2), io.writes[1])
    }

    @Test fun removedWhileQueuedIsNotFetched() {
        s.onNotificationSource(event(ADDED, uid = 1)); s.onNotificationSource(event(ADDED, uid = 2))
        s.onNotificationSource(event(REMOVED, uid = 2))
        s.onDataSource(responseFor(1))
        assertEquals(1, io.writes.size)
    }

    @Test fun removedWhileInFlightStillCompletesQueue() {
        s.onNotificationSource(event(ADDED, uid = 1)); s.onNotificationSource(event(ADDED, uid = 2))
        s.onNotificationSource(event(REMOVED, uid = 1))
        s.onControlPointWriteFailed(AncsIds.ERROR_INVALID_PARAMETER)
        assertArrayEquals(phase1RequestFor(2), io.writes.last())
    }

    @Test fun controlPointErrorSkipsToNext() {
        s.onNotificationSource(event(ADDED, uid = 1)); s.onNotificationSource(event(ADDED, uid = 2))
        s.onControlPointWriteFailed(0xA2)
        assertEquals(1L to "error 0xA2", io.dropped.single())
        assertArrayEquals(phase1RequestFor(2), io.writes.last())
    }

    @Test fun responseTimeoutSkipsToNext() {
        s.onNotificationSource(event(ADDED, uid = 1)); s.onNotificationSource(event(ADDED, uid = 2))
        s.onDataSource(responseFor(1).copyOfRange(0, 6))
        s.onResponseTimeout()
        assertEquals(1L to "timeout", io.dropped.single())
        s.onDataSource(responseFor(2))
        assertEquals(2L, io.notifications.single().second.uid)
    }

    @Test fun endClearsEverything() {
        s.onNotificationSource(event(ADDED, uid = 1)); s.onNotificationSource(event(ADDED, uid = 2))
        s.end()
        s.onDataSource(responseFor(1))
        assertTrue(io.notifications.isEmpty()); assertEquals(1, io.writes.size); assertEquals(1, io.timerCancels)
    }

    @Test fun garbageNotificationSourceIsIgnored() {
        s.onNotificationSource(bytes(0x01))
        assertTrue(io.events.isEmpty())
    }
}
