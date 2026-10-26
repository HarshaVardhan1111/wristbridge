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
    private class Operation(val name: String, val start: () -> Boolean)

    private val pending = ArrayDeque<Operation>()
    private var running: Operation? = null
    private var timer: Cancellable? = null

    fun enqueue(name: String, start: () -> Boolean) {
        pending.addLast(Operation(name, start))
        startNext()
    }

    /** Call from the GATT callback of the current operation. */
    fun complete() {
        if (running == null) return
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
        timer = scheduler.schedule(timeoutMs) { fail(op, "timeout") }
        if (!op.start()) fail(op, "start failed")
    }

    private fun fail(op: Operation, reason: String) {
        if (running !== op) return
        clear()
        onError(op.name, reason)
    }
}
