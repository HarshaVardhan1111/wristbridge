package com.wristbridge.protocol.ancs

/**
 * ANCS logic for one session (from subscribing to Notification Source until
 * disconnect). Fetches attributes for new notifications, one request at a
 * time. Has no Android code: the caller does the Bluetooth I/O through [Io].
 */
class AncsSession(private val io: Io) {
    interface Io {
        fun writeControlPoint(command: ByteArray)

        /** The caller must call [onResponseTimeout] after [RESPONSE_TIMEOUT_MS] unless cancelled. */
        fun startResponseTimer()
        fun cancelResponseTimer()

        /** Every parsed event, for logging. */
        fun onEvent(event: NotificationSourceEvent)
        fun onNotification(event: NotificationSourceEvent, attributes: NotificationAttributes)

        /** A request was given up, for example "error 0xA2" or "timeout". */
        fun onRequestDropped(uid: Long, reason: String)
    }

    private val assembler = DataSourceAssembler()
    private val queue = ArrayDeque<NotificationSourceEvent>()
    private var inFlight: NotificationSourceEvent? = null

    fun onNotificationSource(bytes: ByteArray) {
        val event = NotificationSourceEvent.parse(bytes) ?: return
        io.onEvent(event)
        when (event.eventId) {
            EventId.ADDED -> if (!event.isPreExisting) {
                queue.addLast(event)
                sendNext()
            }
            EventId.REMOVED -> queue.removeAll { it.uid == event.uid }
            EventId.MODIFIED -> Unit
        }
    }

    fun onDataSource(bytes: ByteArray) {
        val event = inFlight ?: return
        val attributes = assembler.append(bytes) ?: return
        io.cancelResponseTimer()
        inFlight = null
        io.onNotification(event, attributes)
        sendNext()
    }

    fun onControlPointWriteFailed(status: Int) = drop(String.format("error 0x%02X", status))

    fun onResponseTimeout() = drop("timeout")

    fun end() {
        if (inFlight != null) io.cancelResponseTimer()
        inFlight = null
        queue.clear()
        assembler.reset()
    }

    private fun drop(reason: String) {
        val event = inFlight ?: return
        io.cancelResponseTimer()
        inFlight = null
        assembler.reset()
        io.onRequestDropped(event.uid, reason)
        sendNext()
    }

    private fun sendNext() {
        if (inFlight != null) return
        val event = queue.removeFirstOrNull() ?: return
        inFlight = event
        assembler.begin(event.uid, PHASE1_ATTRIBUTES.map { it.id })
        io.startResponseTimer()
        io.writeControlPoint(ControlPointCommands.getNotificationAttributes(event.uid, PHASE1_ATTRIBUTES))
    }

    companion object {
        const val RESPONSE_TIMEOUT_MS = 5_000L

        val PHASE1_ATTRIBUTES = listOf(
            AttributeRequest(AncsIds.ATTR_APP_IDENTIFIER),
            AttributeRequest(AncsIds.ATTR_TITLE, 64),
            AttributeRequest(AncsIds.ATTR_MESSAGE, 256),
        )
    }
}
