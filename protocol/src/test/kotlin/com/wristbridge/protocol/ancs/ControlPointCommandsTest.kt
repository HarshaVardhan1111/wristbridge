package com.wristbridge.protocol.ancs

import com.wristbridge.protocol.bytes
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ControlPointCommandsTest {
    @Test fun buildsPhase1Request() {
        val cmd = ControlPointCommands.getNotificationAttributes(0x12345678L, listOf(
            AttributeRequest(AncsIds.ATTR_APP_IDENTIFIER),
            AttributeRequest(AncsIds.ATTR_TITLE, 64),
            AttributeRequest(AncsIds.ATTR_MESSAGE, 256)))
        assertArrayEquals(bytes(0x00, 0x78, 0x56, 0x34, 0x12, 0x00, 0x01, 0x40, 0x00, 0x03, 0x00, 0x01), cmd)
    }

    @Test(expected = IllegalArgumentException::class) fun titleWithoutMaxLengthIsRejected() {
        ControlPointCommands.getNotificationAttributes(1, listOf(AttributeRequest(AncsIds.ATTR_TITLE)))
    }

    @Test(expected = IllegalArgumentException::class) fun maxLengthOnAppIdentifierIsRejected() {
        ControlPointCommands.getNotificationAttributes(1, listOf(AttributeRequest(AncsIds.ATTR_APP_IDENTIFIER, 10)))
    }
}
