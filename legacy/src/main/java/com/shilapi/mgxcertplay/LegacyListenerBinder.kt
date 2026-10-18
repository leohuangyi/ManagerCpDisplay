package com.shilapi.mgxcertplay

import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.math.min

/**
 * Binds a bare [ServerSocket] on an [InetSocketAddress], retrying briefly when the port is already
 * in use. API 17 friendly: no VpnService, no NsdManager; mirrors the reference implementation.
 */
object LegacyListenerBinder {
    fun bind(
        address: InetSocketAddress,
        cancelled: () -> Boolean,
        onBusy: (Int) -> Unit,
        timeoutMillis: Long = 5_000L,
    ): ServerSocket {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        var attempt = 0
        while (!cancelled() && !Thread.currentThread().isInterrupted) {
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(address)
                if (cancelled()) throw InterruptedException()
                return socket
            } catch (e: Exception) {
                socket.close()
                if (e !is BindException || System.nanoTime() >= deadline) throw e
                attempt++
                onBusy(attempt)
                val remaining = min(250L, ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L))
                Thread.sleep(remaining)
            }
        }
        throw InterruptedException()
    }
}