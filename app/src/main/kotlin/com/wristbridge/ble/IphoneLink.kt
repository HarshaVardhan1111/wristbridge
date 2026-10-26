package com.wristbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wristbridge.TAG
import com.wristbridge.protocol.ancs.AncsIds
import com.wristbridge.protocol.ancs.AncsSession
import com.wristbridge.protocol.ancs.AncsUuids
import com.wristbridge.protocol.ancs.NotificationAttributes
import com.wristbridge.protocol.ancs.NotificationSourceEvent
import com.wristbridge.protocol.gatt.GattQueue
import java.util.UUID

/**
 * The link to the iPhone: advertise, accept the connection, bond, subscribe
 * to ANCS, and feed ANCS bytes to [AncsSession].
 *
 * Everything runs on the main thread. Bluetooth callbacks arrive on binder
 * threads, so each one posts to [handler] first. Permissions are checked by
 * MainActivity before the service starts.
 */
@SuppressLint("MissingPermission")
class IphoneLink(private val context: Context) {
    enum class State { IDLE, ADVERTISING, CONNECTED, BONDING, DISCOVERING, SUBSCRIBED, RECONNECTING }

    var state = State.IDLE
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter = manager.adapter
    private val advertiser = Advertiser(adapter)
    private val server = GattServer(context, manager) { device, connected ->
        handler.post { onServerConnectionChange(device, connected) }
    }
    private val queue: GattQueue = GattQueue(HandlerScheduler(handler), onError = { operation, reason ->
        Log.w(TAG, "GATT error $operation: $reason")
        if (operation == OP_REQUEST_MTU && state == State.DISCOVERING) {
            // iOS often sets the MTU itself when it connects, and Android may then
            // never answer our request. A small MTU only means more Data Source fragments.
            Log.i(TAG, "Continuing with the current MTU")
            gatt?.let { g -> queue.enqueue(OP_DISCOVER) { g.discoverServices() } }
        } else {
            tearDown(restart = true)
        }
    })
    private val session = AncsSession(SessionIo())

    private var running = false
    private var device: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var controlPoint: BluetoothGattCharacteristic? = null
    private var backoffMs = INITIAL_BACKOFF_MS
    private var pendingRestart: Runnable? = null
    private var responseTimer: Runnable? = null
    private var discoveryWatchdog: Runnable? = null

    fun start() {
        if (running) return
        running = true
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        })
        startLink()
    }

    fun stop() {
        if (!running) return
        running = false
        context.unregisterReceiver(receiver)
        cancelRestart()
        closeLink()
        server.close()
        state = State.IDLE
    }

    // --- Link lifecycle ---

    private fun startLink() {
        pendingRestart = null
        if (!adapter.isEnabled) {
            Log.i(TAG, "Bluetooth is off; waiting")
            state = State.IDLE
            return
        }
        state = State.ADVERTISING
        if (!server.open()) {
            Log.w(TAG, "GATT server failed to open")
            tearDown(restart = true)
            return
        }
        // After a reinstall or a process restart the iPhone may still be connected.
        // It won't connect again, so adopt the existing link.
        val connected = manager.getConnectedDevices(BluetoothProfile.GATT_SERVER)
            .firstOrNull { it.bondState == BluetoothDevice.BOND_BONDED }
        if (connected != null) {
            Log.i(TAG, "iPhone already connected")
            onServerConnectionChange(connected, connected = true)
            return
        }
        advertiser.start(Advertiser.Mode.PAIRING) { ok ->
            handler.post { if (!ok && state == State.ADVERTISING) tearDown(restart = true) }
        }
    }

    private fun onServerConnectionChange(device: BluetoothDevice, connected: Boolean) {
        if (connected) {
            if (state != State.ADVERTISING && state != State.RECONNECTING) return
            cancelRestart()
            advertiser.stop()
            this.device = device
            Log.i(TAG, "iPhone connected: ${device.address}")
            state = State.CONNECTED
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else if (device.address == this.device?.address) {
            Log.i(TAG, "Disconnected (server)")
            tearDown(restart = true)
        }
    }

    private fun onClientConnected() {
        val device = device ?: return
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                Log.i(TAG, "Bonded (already)")
                discover()
            }
            BluetoothDevice.BOND_BONDING -> state = State.BONDING
            else -> {
                state = State.BONDING
                if (!device.createBond()) {
                    Log.w(TAG, "createBond failed")
                    tearDown(restart = true)
                }
            }
        }
    }

    private fun onBondStateChanged(device: BluetoothDevice, bondState: Int) {
        if (device.address != this.device?.address || state != State.BONDING) return
        when (bondState) {
            BluetoothDevice.BOND_BONDED -> {
                Log.i(TAG, "Bonded")
                discover()
            }
            BluetoothDevice.BOND_NONE -> {
                Log.w(TAG, "Bonding failed")
                tearDown(restart = true)
            }
        }
    }

    private fun discover() {
        val gatt = gatt ?: return
        state = State.DISCOVERING
        session.end()
        cancelResponseTimer()
        queue.clear()
        startDiscoveryWatchdog()
        queue.enqueue(OP_REQUEST_MTU) { gatt.requestMtu(MTU) }
        queue.enqueue(OP_DISCOVER) { gatt.discoverServices() }
    }

    /** If ANCS isn't subscribed in time (for example iOS hasn't published it), start over. */
    private fun startDiscoveryWatchdog() {
        cancelDiscoveryWatchdog()
        val watchdog = Runnable {
            discoveryWatchdog = null
            if (state == State.DISCOVERING) {
                Log.w(TAG, "ANCS not ready after ${DISCOVERY_WATCHDOG_MS / 1000} s; reconnecting")
                tearDown(restart = true)
            }
        }
        discoveryWatchdog = watchdog
        handler.postDelayed(watchdog, DISCOVERY_WATCHDOG_MS)
    }

    private fun cancelDiscoveryWatchdog() {
        discoveryWatchdog?.let { handler.removeCallbacks(it) }
        discoveryWatchdog = null
    }

    private fun subscribe(gatt: BluetoothGatt) {
        // Service Changed is handled by Android itself and reported through onServiceChanged().
        val ancs = gatt.getService(AncsUuids.SERVICE)
        if (ancs == null) {
            Log.w(TAG, "ANCS not found; waiting for Service Changed")
            return
        }
        val dataSource = ancs.getCharacteristic(AncsUuids.DATA_SOURCE)
        val notificationSource = ancs.getCharacteristic(AncsUuids.NOTIFICATION_SOURCE)
        controlPoint = ancs.getCharacteristic(AncsUuids.CONTROL_POINT)
        if (dataSource == null || notificationSource == null || controlPoint == null) {
            Log.w(TAG, "ANCS characteristics missing; waiting for Service Changed")
            return
        }
        Log.i(TAG, "ANCS found")
        // Data Source first: Apple says events can arrive as soon as Notification Source is subscribed.
        enableUpdates(gatt, dataSource, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        enableUpdates(gatt, notificationSource, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    }

    private fun enableUpdates(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        // Long timeout: iOS may wait for the user to tap "Allow notifications".
        queue.enqueue(subscribeOp(characteristic.uuid), SUBSCRIBE_TIMEOUT_MS) {
            val cccd = characteristic.getDescriptor(AncsUuids.CCCD)
            cccd != null &&
                gatt.setCharacteristicNotification(characteristic, true) &&
                gatt.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        }
    }

    private fun onSubscribeResult(uuid: UUID, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "Subscribe failed $uuid status $status")
            if (status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION ||
                status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION
            ) {
                Log.w(TAG, "The watch may hold an old bond with this iPhone. See docs/PHASE1-TEST.md, step 1.")
            }
            tearDown(restart = true)
            return
        }
        if (uuid == AncsUuids.NOTIFICATION_SOURCE) {
            cancelDiscoveryWatchdog()
            state = State.SUBSCRIBED
            backoffMs = INITIAL_BACKOFF_MS
            Log.i(TAG, "ANCS subscribed")
        }
    }

    /** Ends the link. With [restart], advertises again after a growing wait (2 s, 4 s … 300 s). */
    private fun tearDown(restart: Boolean) {
        if (state == State.IDLE || state == State.RECONNECTING) return
        closeLink()
        if (!restart) {
            state = State.IDLE
            return
        }
        state = State.RECONNECTING
        Log.i(TAG, "Reconnecting in ${backoffMs / 1000} s")
        val restartLink = Runnable { startLink() }
        pendingRestart = restartLink
        handler.postDelayed(restartLink, backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun closeLink() {
        cancelResponseTimer()
        cancelDiscoveryWatchdog()
        session.end()
        queue.clear()
        advertiser.stop()
        device?.let { server.disconnect(it) }
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        controlPoint = null
        device = null
    }

    private fun cancelRestart() {
        pendingRestart?.let { handler.removeCallbacks(it) }
        pendingRestart = null
    }

    private fun cancelResponseTimer() {
        responseTimer?.let { handler.removeCallbacks(it) }
        responseTimer = null
    }

    // --- Callbacks (binder threads → main thread) ---

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        ?: return
                    onBondStateChanged(device, intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR))
                }
                BluetoothAdapter.ACTION_STATE_CHANGED ->
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_OFF -> {
                            Log.i(TAG, "Bluetooth turned off")
                            cancelRestart()
                            closeLink()
                            server.close()
                            state = State.IDLE
                        }
                        BluetoothAdapter.STATE_ON -> if (running && state == State.IDLE) {
                            Log.i(TAG, "Bluetooth turned on")
                            startLink()
                        }
                    }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = onMain(g) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                onClientConnected()
            } else {
                Log.i(TAG, "Disconnected (status $status)")
                tearDown(restart = true)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = onMain(g) {
            Log.i(TAG, "MTU $mtu")
            queue.complete(OP_REQUEST_MTU)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = onMain(g) {
            queue.complete(OP_DISCOVER)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                subscribe(g)
            } else {
                Log.w(TAG, "Service discovery failed: $status")
                tearDown(restart = true)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = onMain(g) {
            queue.complete(subscribeOp(descriptor.characteristic.uuid))
            onSubscribeResult(descriptor.characteristic.uuid, status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            onMain(g) {
                queue.complete(OP_WRITE_CONTROL_POINT)
                if (characteristic.uuid == AncsUuids.CONTROL_POINT && status != BluetoothGatt.GATT_SUCCESS) {
                    session.onControlPointWriteFailed(status)
                }
            }

        // API 33 overload. Its default implementation calls the old one, so we override only this one.
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) =
            onMain(g) {
                when (characteristic.uuid) {
                    AncsUuids.NOTIFICATION_SOURCE -> session.onNotificationSource(value)
                    AncsUuids.DATA_SOURCE -> session.onDataSource(value)
                }
            }

        // Android consumes Service Changed indications and reports them here (API 31+).
        override fun onServiceChanged(g: BluetoothGatt) = onMain(g) {
            Log.i(TAG, "Service Changed; discovering again")
            discover()
        }

        /** Runs [block] on the main thread, unless [g] is an old, closed connection. */
        private fun onMain(g: BluetoothGatt, block: () -> Unit) {
            handler.post { if (g === gatt) block() }
        }
    }

    private inner class SessionIo : AncsSession.Io {
        override fun writeControlPoint(command: ByteArray) {
            val gatt = gatt ?: return
            val characteristic = controlPoint ?: return
            queue.enqueue(OP_WRITE_CONTROL_POINT) {
                gatt.writeCharacteristic(characteristic, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS
            }
        }

        override fun startResponseTimer() {
            cancelResponseTimer()
            val timer = Runnable {
                responseTimer = null
                session.onResponseTimeout()
            }
            responseTimer = timer
            handler.postDelayed(timer, AncsSession.RESPONSE_TIMEOUT_MS)
        }

        override fun cancelResponseTimer() = this@IphoneLink.cancelResponseTimer()

        override fun onEvent(event: NotificationSourceEvent) {
            Log.i(TAG, "Event ${event.eventId} uid=${event.uid} category=${event.categoryId} flags=0x%02X".format(event.flags))
        }

        override fun onNotification(event: NotificationSourceEvent, attributes: NotificationAttributes) {
            val values = attributes.values
            Log.i(
                TAG,
                "Notification: app=${values[AncsIds.ATTR_APP_IDENTIFIER]} " +
                    "title=${values[AncsIds.ATTR_TITLE]} message=${values[AncsIds.ATTR_MESSAGE]}",
            )
        }

        override fun onRequestDropped(uid: Long, reason: String) {
            Log.w(TAG, "Dropped uid=$uid: $reason")
        }
    }

    private companion object {
        const val MTU = 185
        const val OP_REQUEST_MTU = "requestMtu"
        const val OP_DISCOVER = "discoverServices"
        const val OP_WRITE_CONTROL_POINT = "writeControlPoint"
        const val SUBSCRIBE_TIMEOUT_MS = 30_000L
        const val DISCOVERY_WATCHDOG_MS = 60_000L

        fun subscribeOp(uuid: UUID) = "subscribe $uuid"
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 300_000L
    }
}
