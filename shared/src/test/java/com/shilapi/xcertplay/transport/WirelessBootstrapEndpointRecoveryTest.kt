package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class WirelessBootstrapEndpointRecoveryTest {
    private val v4 = InetAddress.getByName("192.168.43.1")
    private val unscopedV6 = InetAddress.getByName("fe80::1234") as Inet6Address
    private val v6 = Inet6Address.getByAddress(null, unscopedV6.address, 7)
    private val timeout = Iap2WirelessControlResult(
        Iap2WirelessControlTerminal.TIMED_OUT, Iap2WirelessControlStage.CARPLAY_START_SENT,
        0, 0, 1, false, 0, false,
    )

    @Test fun timedOutDualStackAttemptsOfferIpv6ThenIpv4ThenIpv6OnTheWire() {
        val first = endpoint(listOf(v6.hostAddress!!.substringBefore('%')))
        val second = recover(first, listOf(v4, v6))
        val third = recover(second, listOf(v4, v6))
        assertEquals(listOf(v6.hostAddress!!.substringBefore('%')), wireAddresses(first))
        assertEquals(listOf("192.168.43.1"), wireAddresses(second))
        assertEquals(wireAddresses(first), wireAddresses(third))
        for (retry in listOf(second, third)) {
            // Only IPAddress may change: cached Wi-Fi credentials, receiver identity, port and
            // optional AP BSSID still describe the same hotspot and pairing on every attempt.
            assertEquals(withoutIp(first), withoutIp(retry))
            assertArrayEquals(
                Iap2WirelessControlClient.accessoryWiFiConfiguration(first).encodedFrame(),
                Iap2WirelessControlClient.accessoryWiFiConfiguration(retry).encodedFrame(),
            )
        }
    }

    @Test fun eofAfterStartSessionDoesNotMasqueradeAsAnAddressTimeout() {
        val current = endpoint(listOf("fe80::1234"))
        assertSame(current, WirelessBootstrapEndpointRecovery.afterTimeout(
            current, timeout.copy(terminal = Iap2WirelessControlTerminal.CHANNEL_CLOSED), listOf(v4, v6),
        ))
    }

    @Test fun identificationOrAuthenticationTimeoutKeepsTheEndpoint() {
        val current = endpoint(listOf("fe80::1234"))
        for (stage in listOf(Iap2WirelessControlStage.IDENTIFIED, Iap2WirelessControlStage.AUTHENTICATED)) {
            assertSame(current, WirelessBootstrapEndpointRecovery.afterTimeout(
                current, timeout.copy(stage = stage, carPlayStartSessionsSent = 0), listOf(v4, v6),
            ))
        }
    }

    @Test fun missingOrUnusableAlternateDoesNotReplaceTheCurrentFamily() {
        val ipv6 = endpoint(listOf("fe80::1234"))
        assertSame(ipv6, recover(ipv6, emptyList()))
        assertSame(ipv6, recover(ipv6, listOf(v6)))
        for (address in listOf("0.0.0.0", "127.0.0.1", "169.254.1.1", "224.0.0.251")) {
            assertSame(ipv6, recover(ipv6, listOf(InetAddress.getByName(address))))
        }
        val ipv4 = endpoint(listOf("192.168.43.1"))
        assertSame(ipv4, recover(ipv4, listOf(v4, unscopedV6, InetAddress.getByName("2001:db8::1"))))
    }

    @Test fun scopedAlternateIsSentWithoutTheAndroidZoneSuffixAndWithoutDuplicates() {
        val next = recover(endpoint(listOf("192.168.43.1")), listOf(v6, v6))
        assertEquals(listOf(v6.hostAddress!!.substringBefore('%')), wireAddresses(next))
        assertFalse(wireAddresses(next).single().contains('%'))
    }

    @Test fun anAlreadyMixedEndpointIsNotRewrittenToAnArbitraryFamily() {
        val current = endpoint(listOf("192.168.43.1", "fe80::1234"))
        assertSame(current, recover(current, listOf(v4, v6)))
    }

    private fun recover(current: Iap2WirelessCarPlayEndpoint, listening: List<InetAddress>) =
        WirelessBootstrapEndpointRecovery.afterTimeout(current, timeout, listening)

    private fun wireAddresses(endpoint: Iap2WirelessCarPlayEndpoint): List<String> {
        val frame = Iap2WirelessControlClient.carPlayStartSession(endpoint)
        val wireless = Iap2ParameterList.parse(frame.payload).require(1)
        return Iap2ParameterList.parse(wireless.payload).all(3).map {
            it.payload.decodeToString().removeSuffix("\u0000")
        }
    }

    private fun withoutIp(endpoint: Iap2WirelessCarPlayEndpoint): List<Iap2ParameterList> {
        val body = Iap2ParameterList.parse(Iap2WirelessControlClient.carPlayStartSession(endpoint).payload)
        return listOf(
            Iap2ParameterList.of(body.asList().filterNot { it.id == 1 }),
            Iap2ParameterList.of(Iap2ParameterList.parse(body.require(1).payload).asList().filterNot { it.id == 3 }),
        )
    }

    private fun endpoint(addresses: List<String>) = Iap2WirelessCarPlayEndpoint(
        ssid = "test-car", passphrase = "test-password", channel = 6,
        security = Iap2WirelessSecurity.WPA_WPA2, ipAddresses = addresses,
        airPlayPort = 7100, deviceIdentifier = "test-device", publicKey = "test-pairing",
        sourceVersion = "1.0", accessPointBssid = byteArrayOf(2, 1, 2, 3, 4, 5),
    )
}
