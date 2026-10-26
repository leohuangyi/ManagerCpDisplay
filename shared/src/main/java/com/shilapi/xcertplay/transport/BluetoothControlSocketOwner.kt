package com.shilapi.xcertplay.transport

import java.io.Closeable

/**
 * Gives one Bluetooth iAP2 control worker exclusive ownership of a socket. An authenticated
 * incoming connection may replace a pending outbound connect, but never a claimed control link.
 * A single early incoming connection can wait for phone selection and network preparation.
 */
class BluetoothControlSocketOwner<T : Any>(private val closeSocket: (T) -> Unit) : Closeable {
    private data class Incoming<T>(val peer: String, val socket: T)

    private val lock = java.lang.Object()
    private var selectedPeer: String? = null
    private var incoming: Incoming<T>? = null
    private var connecting: T? = null
    private var claimed: T? = null
    private var closed = false

    fun selectPeer(peer: String) {
        val rejected = synchronized(lock) {
            check(selectedPeer == null || selectedPeer.equals(peer, ignoreCase = true))
            selectedPeer = peer
            incoming?.takeUnless { it.peer.equals(peer, ignoreCase = true) }?.also {
                incoming = null
            }?.socket
        }
        closeQuietly(rejected)
    }

    /** The caller must already have checked Bluetooth bonding/authentication. */
    fun offerIncoming(peer: String, socket: T): Boolean {
        var displaced: T? = null
        val accepted = synchronized(lock) {
            if (closed || claimed != null || incoming != null ||
                (selectedPeer != null && !selectedPeer.equals(peer, ignoreCase = true))) {
                false
            } else {
                incoming = Incoming(peer, socket)
                displaced = connecting
                connecting = null
                lock.notifyAll()
                true
            }
        }
        closeQuietly(if (accepted) displaced else socket)
        return accepted
    }

    fun beginOutgoing(peer: String, socket: T): Boolean {
        val accepted = synchronized(lock) {
            if (closed || !selectedPeer.equals(peer, ignoreCase = true) || incoming != null ||
                connecting != null || claimed != null) {
                false
            } else {
                connecting = socket
                true
            }
        }
        if (!accepted) closeQuietly(socket)
        return accepted
    }

    /** Claims a completed outbound connect only if an incoming socket did not win first. */
    fun outgoingConnected(socket: T): Boolean = synchronized(lock) {
        if (closed || connecting !== socket) false else {
            connecting = null
            claimed = socket
            true
        }
    }

    fun outgoingFailed(socket: T) {
        synchronized(lock) {
            if (connecting === socket) connecting = null
        }
    }

    fun takeIncoming(): T? = synchronized(lock) {
        if (closed || claimed != null || selectedPeer == null) null else {
            incoming?.socket?.also {
                incoming = null
                claimed = it
            }
        }
    }

    fun hasIncoming(): Boolean = synchronized(lock) { incoming != null && !closed }

    /** Event-driven wait after bounded outbound attempts have ended; [close] wakes it. */
    @Throws(InterruptedException::class)
    fun awaitIncoming(): Boolean = synchronized(lock) {
        while (!closed && incoming == null) lock.wait()
        !closed && incoming != null
    }

    fun release(socket: T) {
        synchronized(lock) {
            if (claimed === socket) claimed = null
            if (connecting === socket) connecting = null
        }
    }

    override fun close() {
        val sockets = synchronized(lock) {
            if (closed) return
            closed = true
            listOfNotNull(incoming?.socket, connecting, claimed).also {
                incoming = null
                connecting = null
                claimed = null
                lock.notifyAll()
            }
        }
        sockets.forEach(::closeQuietly)
    }

    private fun closeQuietly(socket: T?) {
        if (socket != null) runCatching { closeSocket(socket) }
    }
}
