package com.wristbridge.ble

import android.os.Handler
import com.wristbridge.protocol.gatt.Cancellable
import com.wristbridge.protocol.gatt.Scheduler

/** [Scheduler] backed by an Android [Handler]. */
class HandlerScheduler(private val handler: Handler) : Scheduler {
    override fun schedule(delayMs: Long, block: () -> Unit): Cancellable {
        val runnable = Runnable(block)
        handler.postDelayed(runnable, delayMs)
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}
