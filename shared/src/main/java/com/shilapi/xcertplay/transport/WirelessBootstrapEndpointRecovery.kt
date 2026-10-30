package com.shilapi.xcertplay.transport

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Address-family fallback for a Bluetooth attempt that sent StartSession but got no handoff. */
object WirelessBootstrapEndpointRecovery {
    /**
     * The caller supplies only addresses still present on the selected AP and already bound by
     * its AirPlay listeners. Call only after the recovery policy allows another attempt.
     * Keep one family per StartSession, as the main wireless controller does on TCP timeout.
     */
    fun afterTimeout(
        current: Iap2WirelessCarPlayEndpoint,
        result: Iap2WirelessControlResult,
        listeningAddresses: List<InetAddress>,
    ): Iap2WirelessCarPlayEndpoint {
        if (result.terminal != Iap2WirelessControlTerminal.TIMED_OUT || result.carPlayStartSessionsSent == 0) {
            return current
        }
        val ipv6 = current.ipAddresses.all { ':' in it }
        val ipv4 = current.ipAddresses.all { ':' !in it }
        if (!ipv6 && !ipv4) return current
        val alternate = listeningAddresses.filter { address ->
            if (ipv6) {
                address is Inet4Address && !address.isAnyLocalAddress && !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress && !address.isMulticastAddress
            } else {
                address is Inet6Address && address.isLinkLocalAddress && address.scopeId != 0
            }
        }.map { it.hostAddress!!.substringBefore('%') }.distinct()
        if (alternate.isEmpty()) return current
        return Iap2WirelessCarPlayEndpoint(
            ssid = current.ssid,
            passphrase = current.passphrase,
            channel = current.channel,
            security = current.security,
            ipAddresses = alternate,
            airPlayPort = current.airPlayPort,
            deviceIdentifier = current.deviceIdentifier,
            publicKey = current.publicKey,
            sourceVersion = current.sourceVersion,
            accessPointBssid = current.accessPointBssid,
        )
    }
}
