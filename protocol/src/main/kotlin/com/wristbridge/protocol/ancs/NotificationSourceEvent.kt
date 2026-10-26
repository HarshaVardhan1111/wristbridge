package com.wristbridge.protocol.ancs

enum class EventId(val value: Int) { ADDED(0), MODIFIED(1), REMOVED(2) }

/** One 8-byte GATT notification from the ANCS Notification Source characteristic. */
data class NotificationSourceEvent(
    val eventId: EventId,
    val flags: Int,
    val categoryId: Int,
    val categoryCount: Int,
    /** Unsigned 32-bit NotificationUID (0..4294967295). */
    val uid: Long,
) {
    val isSilent get() = flags and AncsIds.FLAG_SILENT != 0
    val isImportant get() = flags and AncsIds.FLAG_IMPORTANT != 0
    val isPreExisting get() = flags and AncsIds.FLAG_PRE_EXISTING != 0
    val hasPositiveAction get() = flags and AncsIds.FLAG_POSITIVE_ACTION != 0
    val hasNegativeAction get() = flags and AncsIds.FLAG_NEGATIVE_ACTION != 0

    companion object {
        /** Returns null for packets shorter than 8 bytes or with a reserved EventID. */
        fun parse(bytes: ByteArray): NotificationSourceEvent? {
            if (bytes.size < 8) return null
            val eventId = EventId.entries.firstOrNull { it.value == bytes.u8(0) } ?: return null
            return NotificationSourceEvent(
                eventId = eventId,
                flags = bytes.u8(1),
                categoryId = bytes.u8(2),
                categoryCount = bytes.u8(3),
                uid = bytes.u32le(4),
            )
        }
    }
}

internal fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

internal fun ByteArray.u16le(index: Int): Int = u8(index) or (u8(index + 1) shl 8)

internal fun ByteArray.u32le(index: Int): Long =
    u8(index).toLong() or (u8(index + 1).toLong() shl 8) or
        (u8(index + 2).toLong() shl 16) or (u8(index + 3).toLong() shl 24)
