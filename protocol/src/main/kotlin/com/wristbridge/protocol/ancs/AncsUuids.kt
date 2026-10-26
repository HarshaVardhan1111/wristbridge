package com.wristbridge.protocol.ancs

import java.util.UUID

/** UUIDs from Apple's ANCS specification, plus the standard GATT ones we need. */
object AncsUuids {
    val SERVICE: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
    val NOTIFICATION_SOURCE: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
    val CONTROL_POINT: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
    val DATA_SOURCE: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")

    val GATT_SERVICE: UUID = bluetoothBase(0x1801)
    val SERVICE_CHANGED: UUID = bluetoothBase(0x2A05)
    val CCCD: UUID = bluetoothBase(0x2902)

    private fun bluetoothBase(short: Int): UUID =
        UUID.fromString(String.format("0000%04X-0000-1000-8000-00805F9B34FB", short))
}
