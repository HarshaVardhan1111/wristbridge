package com.wristbridge.protocol.gatt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GattQueueTest {
    private class FakeScheduler : Scheduler {
        private var now = 0L
        private val tasks = mutableListOf<Pair<Long, () -> Unit>>()

        override fun schedule(delayMs: Long, block: () -> Unit): Cancellable {
            val task = (now + delayMs) to block
            tasks += task
            return Cancellable { tasks.remove(task) }
        }

        fun advance(ms: Long) {
            now += ms
            tasks.filter { it.first <= now }.forEach { tasks.remove(it); it.second() }
        }
    }

    private val sched = FakeScheduler()
    private val errors = mutableListOf<Pair<String, String>>()
    private val started = mutableListOf<String>()
    private val q = GattQueue(sched, onError = { op, reason -> errors += op to reason })

    @Test fun runsOneAtATime() {
        q.enqueue("a") { started += "a"; true }; q.enqueue("b") { started += "b"; true }
        assertEquals(listOf("a"), started)
        q.complete("a")
        assertEquals(listOf("a", "b"), started)
    }

    @Test fun startFailureReportsErrorAndClears() {
        q.enqueue("a") { started += "a"; true }; q.enqueue("b") { false }; q.enqueue("c") { started += "c"; true }
        q.complete("a") // "b" starts and fails
        assertEquals("b" to "start failed", errors.single()); assertEquals(listOf("a"), started)
    }

    @Test fun timeoutReportsErrorAndClears() {
        q.enqueue("a") { true }; q.enqueue("b") { started += "b"; true }
        sched.advance(10_000)
        assertEquals("a" to "timeout", errors.single()); assertTrue(started.isEmpty())
    }

    @Test fun completeCancelsTimeout() {
        q.enqueue("a") { true }; q.complete("a")
        sched.advance(20_000)
        assertTrue(errors.isEmpty())
    }

    @Test fun completeWithNothingRunningIsIgnored() {
        q.complete("x"); q.enqueue("a") { started += "a"; true }
        assertEquals(listOf("a"), started)
    }

    @Test fun worksAgainAfterClear() {
        q.enqueue("a") { true }; q.clear(); q.enqueue("b") { started += "b"; true }
        assertEquals(listOf("b"), started)
    }

    @Test fun completeForOtherOperationIsIgnored() {
        q.enqueue("a") { started += "a"; true }; q.enqueue("b") { started += "b"; true }
        q.complete("late") // e.g. a late callback from an operation that already timed out
        assertEquals(listOf("a"), started)
        q.complete("a")
        assertEquals(listOf("a", "b"), started)
    }

    @Test fun perOperationTimeoutOverridesDefault() {
        q.enqueue("slow", timeoutMs = 30_000) { true }
        sched.advance(10_000)
        assertTrue(errors.isEmpty())
        sched.advance(20_000)
        assertEquals("slow" to "timeout", errors.single())
    }
}
