package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.transport.WirelessBootstrapRecoveryPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WirelessBootstrapRecoveryPolicyTest {
    @Test fun anUnjoinedPhoneGetsTwoBoundedRetriesWithIncreasingBackoff() {
        val first = decision(1)
        val second = decision(2)
        assertEquals(Action.RETRY, first.action)
        assertEquals(2_000L, first.retryDelayMillis)
        assertEquals(Action.RETRY, second.action)
        assertEquals(5_000L, second.retryDelayMillis)
        assertEquals(Action.EXHAUSTED, decision(WirelessBootstrapRecoveryPolicy.MAX_ATTEMPTS).action)
        assertEquals(Action.EXHAUSTED, decision(4).action)
    }

    @Test fun anAirPlayConnectionDuringPairingStopsRetriesEvenBeforeItIsActive() {
        assertEquals(Action.HANDOFF, decision(1, airPlayConnected = true).action)
        assertEquals(Action.HANDOFF, decision(3, airPlayConnected = true).action)
    }

    @Test fun anAcceptedWifiTunnelStopsRetriesWhileItAuthenticates() {
        assertEquals(Action.HANDOFF, decision(1, wifiTunnelConnected = true).action)
        assertEquals(Action.HANDOFF, decision(3, wifiTunnelConnected = true).action)
    }

    @Test fun closeCancelsRecoveryEvenWhenAHandoffWasInProgress() {
        assertEquals(Action.CLOSED, decision(1, closed = true).action)
        assertEquals(Action.CLOSED, decision(2, closed = true, airPlayConnected = true).action)
    }

    @Test fun aPhoneJoiningDuringBackoffPreventsTheNextHandshake() {
        assertEquals(Action.RETRY, decision(1).action)
        assertEquals(Action.HANDOFF, decision(1, airPlayConnected = true).action)
    }

    @Test fun anAttemptMustHaveCompletedBeforeRecoveryIsConsidered() {
        assertThrows(IllegalArgumentException::class.java) { decision(0) }
    }

    private fun decision(
        completedAttempts: Int,
        closed: Boolean = false,
        airPlayConnected: Boolean = false,
        wifiTunnelConnected: Boolean = false,
    ) = WirelessBootstrapRecoveryPolicy.afterControlEnded(
        completedAttempts,
        closed,
        airPlayConnected,
        wifiTunnelConnected,
    )
}
