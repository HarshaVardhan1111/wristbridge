package com.wristbridge.protocol.gatt

fun interface Cancellable { fun cancel() }

fun interface Scheduler { fun schedule(delayMs: Long, block: () -> Unit): Cancellable }

/**
 * Runs GATT operations one at a time, because Android drops a second
 * operation that starts before the first one's callback. Not thread-safe:
 * call it from one thread only.
 */
class GattQueue(
    private val scheduler: Scheduler,
    private val timeoutMs: Long = 10_000,
    private val onError: (operation: String, reason: String) -> Unit,
) {
    private class Operation(val name: String, val timeoutMs: Long, val start: () -> Boolean)

    private val pending = ArrayDeque<Operation>()
    private var running: Operation? = null
    private var timer: Cancellable? = null

    fun enqueue(name: String, timeoutMs: Long = this.timeoutMs, start: () -> Boolean) {
        pending.addLast(Operation(name, timeoutMs, start))
        startNext()
    }

    /**
     * Call from the GATT callback of operation [name]. Ignored unless [name]
     * is the running operation, so a late callback (for example after a
     * timeout) cannot complete a different operation.
     */
    fun complete(name: String) {
        if (running?.name != name) return
        timer?.cancel()
        timer = null
        running = null
        startNext()
    }

    fun clear() {
        timer?.cancel()
        timer = null
        running = null
        pending.clear()
    }

    private fun startNext() {
        if (running != null) return
        val op = pending.removeFirstOrNull() ?: return
        running = op
        timer = scheduler.schedule(op.timeoutMs) { fail(op, "timeout") }
        if (!op.start()) fail(op, "start failed")
    }

    private fun fail(op: Operation, reason: String) {
        if (running !== op) return
        clear()
        onError(op.name, reason)
    }
}
