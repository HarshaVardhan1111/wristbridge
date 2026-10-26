package com.wristbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context

/**
 * An empty GATT server. It offers no services; its only job is to tell us
 * when the iPhone connects to the watch's advertisement.
 * Permissions are checked by MainActivity before the service starts.
 */
@SuppressLint("MissingPermission")
class GattServer(
    private val context: Context,
    private val manager: BluetoothManager,
    private val onConnectionChange: (BluetoothDevice, Boolean) -> Unit,
) {
    private var server: BluetoothGattServer? = null

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            onConnectionChange(device, newState == BluetoothProfile.STATE_CONNECTED)
        }
    }

    fun open(): Boolean {
        if (server == null) server = manager.openGattServer(context, callback)
        return server != null
    }

    /** Drops the link to [device], so the iPhone reconnects cleanly later. */
    fun disconnect(device: BluetoothDevice) {
        server?.cancelConnection(device)
    }

    fun close() {
        server?.close()
        server = null
    }
}
