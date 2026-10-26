package com.wristbridge.protocol.ancs

import java.io.ByteArrayOutputStream

/** One attribute to request. [maxLength] is required for Title, Subtitle and Message, and forbidden otherwise. */
data class AttributeRequest(val id: Int, val maxLength: Int? = null)

/** Builds the byte commands written to the ANCS Control Point characteristic. */
object ControlPointCommands {
    private val NEEDS_MAX_LENGTH = setOf(AncsIds.ATTR_TITLE, AncsIds.ATTR_SUBTITLE, AncsIds.ATTR_MESSAGE)

    fun getNotificationAttributes(uid: Long, attributes: List<AttributeRequest>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(AncsIds.COMMAND_GET_NOTIFICATION_ATTRIBUTES)
        out.writeU32le(uid)
        for (attr in attributes) {
            val needsMax = attr.id in NEEDS_MAX_LENGTH
            require(needsMax == (attr.maxLength != null)) {
                if (needsMax) "Attribute ${attr.id} needs a max length" else "Attribute ${attr.id} takes no max length"
            }
            out.write(attr.id)
            attr.maxLength?.let { out.writeU16le(it) }
        }
        return out.toByteArray()
    }
}

private fun ByteArrayOutputStream.writeU16le(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
}

private fun ByteArrayOutputStream.writeU32le(value: Long) {
    for (shift in 0 until 32 step 8) write(((value shr shift) and 0xFF).toInt())
}
