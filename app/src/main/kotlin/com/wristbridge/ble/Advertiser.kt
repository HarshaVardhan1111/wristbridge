package com.wristbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.os.ParcelUuid
import android.util.Log
import com.wristbridge.TAG
import com.wristbridge.protocol.ancs.AncsUuids

/**
 * Advertises "I want ANCS" (service solicitation), so the iPhone lists the
 * watch in Settings > Bluetooth. The name goes in the scan response because
 * it does not fit next to the 128-bit UUID in the 31-byte main packet.
 */
@SuppressLint("MissingPermission")
class Advertiser(private val adapter: BluetoothAdapter) {
    enum class Mode { PAIRING, BACKGROUND }

    private var callback: AdvertiseCallback? = null

    fun start(mode: Mode, onResult: (Boolean) -> Unit) {
        stop()
        start(mode, includeName = true, onResult)
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        adapter.bluetoothLeAdvertiser?.stopAdvertising(cb)
    }

    private fun start(mode: Mode, includeName: Boolean, onResult: (Boolean) -> Unit) {
        val leAdvertiser = adapter.bluetoothLeAdvertiser
        if (leAdvertiser == null) {
            Log.w(TAG, "Advertising failed: BLE advertising not available")
            onResult(false)
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setConnectable(true)
            .setTimeout(0)
            .setAdvertiseMode(
                if (mode == Mode.PAIRING) AdvertiseSettings.ADVERTISE_MODE_BALANCED
                else AdvertiseSettings.ADVERTISE_MODE_LOW_POWER,
            )
            .setTxPowerLevel(
                if (mode == Mode.PAIRING) AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
                else AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
            )
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceSolicitationUuid(ParcelUuid(AncsUuids.SERVICE))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(includeName)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i(TAG, "Advertising started ($mode)")
                onResult(true)
            }

            override fun onStartFailure(errorCode: Int) {
                if (callback === this) callback = null
                if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && includeName) {
                    Log.w(TAG, "Watch name too long for the advertisement; advertising without it")
                    start(mode, includeName = false, onResult)
                } else {
                    Log.w(TAG, "Advertising failed: $errorCode")
                    onResult(false)
                }
            }
        }
        callback = cb
        leAdvertiser.startAdvertising(settings, data, scanResponse, cb)
    }
}
