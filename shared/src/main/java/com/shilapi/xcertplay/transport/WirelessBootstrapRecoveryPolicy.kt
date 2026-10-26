package com.shilapi.xcertplay.transport

/** Recovery of an ended Bluetooth bring-up loop never tears down the Wi-Fi stack. */
object WirelessBootstrapRecoveryPolicy {
    const val MAX_ATTEMPTS = 3

    enum class Action { CLOSED, HANDOFF, RETRY, EXHAUSTED }

    data class Decision(val action: Action, val retryDelayMillis: Long = 0L)

    /**
     * A TCP AirPlay connection or an accepted Wi-Fi iAP2 tunnel already owns the handoff,
     * including while it authenticates. Do not start another Bluetooth handshake over it.
     */
    fun afterControlEnded(
        completedAttempts: Int,
        closed: Boolean,
        airPlayConnected: Boolean,
        wifiTunnelConnected: Boolean,
    ): Decision {
        require(completedAttempts >= 1) { "completedAttempts must be positive" }
        if (closed) return Decision(Action.CLOSED)
        if (airPlayConnected || wifiTunnelConnected) return Decision(Action.HANDOFF)
        return when (completedAttempts) {
            1 -> Decision(Action.RETRY, 2_000L)
            2 -> Decision(Action.RETRY, 5_000L)
            else -> Decision(Action.EXHAUSTED)
        }
    }
}
