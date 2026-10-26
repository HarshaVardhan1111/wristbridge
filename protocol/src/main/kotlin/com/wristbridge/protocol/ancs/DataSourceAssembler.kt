package com.wristbridge.protocol.ancs

import java.io.ByteArrayOutputStream

/** A complete Get Notification Attributes response. [values] is keyed by attribute id. */
data class NotificationAttributes(val uid: Long, val values: Map<Int, String>)

/**
 * Joins Data Source fragments into one Get Notification Attributes response.
 * Apple splits responses larger than the MTU, so a fragment can end anywhere,
 * even inside a length field or a multi-byte character.
 */
class DataSourceAssembler {
    private val buffer = ByteArrayOutputStream()
    private var expectedUid: Long? = null
    private var expectedIds: List<Int> = emptyList()

    fun begin(uid: Long, attributeIds: List<Int>) {
        expectedUid = uid
        expectedIds = attributeIds
        buffer.reset()
    }

    /** Returns the response once every requested attribute has fully arrived, otherwise null. */
    fun append(fragment: ByteArray): NotificationAttributes? {
        val uid = expectedUid ?: return null
        buffer.write(fragment)
        val data = buffer.toByteArray()
        if (data.size < HEADER_SIZE) return null
        if (data.u8(0) != AncsIds.COMMAND_GET_NOTIFICATION_ATTRIBUTES || data.u32le(1) != uid) {
            // Late bytes from an older request: drop them but keep waiting for ours.
            buffer.reset()
            return null
        }
        val values = parseTuples(data) ?: return null
        reset()
        return NotificationAttributes(uid, values)
    }

    fun reset() {
        expectedUid = null
        expectedIds = emptyList()
        buffer.reset()
    }

    private fun parseTuples(data: ByteArray): Map<Int, String>? {
        val values = LinkedHashMap<Int, String>()
        var offset = HEADER_SIZE
        repeat(expectedIds.size) {
            if (data.size < offset + TUPLE_HEADER_SIZE) return null
            val id = data.u8(offset)
            val length = data.u16le(offset + 1)
            val start = offset + TUPLE_HEADER_SIZE
            if (data.size < start + length) return null
            values[id] = String(data, start, length, Charsets.UTF_8)
            offset = start + length
        }
        return values
    }

    private companion object {
        const val HEADER_SIZE = 5 // CommandID + NotificationUID
        const val TUPLE_HEADER_SIZE = 3 // AttributeID + 16-bit length
    }
}
