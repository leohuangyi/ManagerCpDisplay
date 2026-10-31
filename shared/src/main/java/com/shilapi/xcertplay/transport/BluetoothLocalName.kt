package com.shilapi.xcertplay.transport

import java.io.IOException
import java.util.concurrent.TimeUnit

/** Waits for an asynchronous radio rename before iAP2 advertises the matching accessory name. */
object BluetoothLocalName {
    /** Returns true when a rename was requested; an already matching radio is left alone. */
    fun ensure(
        expected: String,
        currentName: () -> String?,
        requestName: (String) -> Boolean,
        enabled: () -> Boolean,
        cancelled: () -> Boolean = { false },
        timeoutMillis: Long = 5_000L,
        clockNanos: () -> Long = System::nanoTime,
        sleepMillis: (Long) -> Unit = { Thread.sleep(it) },
    ): Boolean {
        require(expected.isNotBlank())
        require(timeoutMillis > 0)

        fun checkReady() {
            if (cancelled() || Thread.currentThread().isInterrupted) {
                throw InterruptedException("Bluetooth local name synchronization cancelled")
            }
            if (!enabled()) throw IOException("Bluetooth is off during local name synchronization")
        }

        checkReady()
        if (currentName() == expected) return false
        checkReady()
        val startedNanos = clockNanos()
        val accepted = requestName(expected)
        while (true) {
            checkReady()
            // A concurrent rename can have succeeded even when this request was rejected.
            if (currentName() == expected) return true
            if (!accepted) throw IOException("Bluetooth local name change rejected; iAP2 name is not synchronized")
            val elapsedNanos = (clockNanos() - startedNanos).coerceAtLeast(0)
            val remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - elapsedNanos
            if (remainingNanos <= 0) {
                throw IOException("Bluetooth local name change timed out; iAP2 name is not synchronized")
            }
            try {
                sleepMillis(minOf(100L, maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))))
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            }
        }
    }
}
