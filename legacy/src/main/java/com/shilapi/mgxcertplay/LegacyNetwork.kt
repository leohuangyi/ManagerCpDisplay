package com.shilapi.mgxcertplay

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import java.io.Closeable
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.shilapi.xcertplay.adb.LocalAdb

/**
 * Brings up the wireless link for CarPlay:
 *
 *  - `auto`: start the hotspot natively over root (hostapd -> static IP -> dnsmasq), since the
 *    miwifi firmware's `setWifiApEnabled` never launches hostapd; rebuilds it on every start.
 *  - `direct`: Wi-Fi Direct Group Owner via [WifiP2pManager.createGroup] (needs P2P-capable firmware).
 */
class LegacyNetwork(
    private val context: Context,
    private val networkMode: String,
    private val stopped: () -> Boolean,
    private val log: (String) -> Unit,
) : Closeable {

    class Endpoint(
        val ssid: String,
        val password: String,
        val channel: Int,
        val iface: NetworkInterface,
        val address: Inet4Address,
        val addresses: List<InetAddress> = listOf(address),
    )

    private val wifi: WifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var p2p: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var stationWasEnabled = false
    private var wifiWasEnabledByUs = false
    private var oldApConfig: WifiConfiguration? = null
    private var apWasEnabled = false
    private var changedAp = false
    private var hotspotStartedByUs = false

    fun prepare(): Endpoint = when (networkMode) {
        "direct" -> prepareDirect()
        else -> prepareAuto()
    }

    // --- Wi-Fi Direct (P2P) ---------------------------------------------------------------

    private fun prepareDirect(): Endpoint {
        val p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: throw IllegalStateException("Wi-Fi Direct service unavailable")
        p2p = p2pManager
        stationWasEnabled = wifi.isWifiEnabled

        oldApConfig = currentApConfig()
        apWasEnabled = runCatching { state() == AP_STATE_ENABLED }.getOrDefault(false)
        if (apWasEnabled) {
            changedAp = true
            check(setAp(oldApConfig, enabled = false)) { "could not stop the existing hotspot" }
        }
        if (!wifi.isWifiEnabled) {
            wifiWasEnabledByUs = true
            check(wifi.setWifiEnabled(true)) { "could not enable Wi-Fi" }
        }

        val channel = p2pManager.initialize(context, Looper.getMainLooper(), null)
        p2pChannel = channel

        val groupLatch = CountDownLatch(1)
        var groupReason: Int? = null
        p2pManager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { groupLatch.countDown() }
            override fun onFailure(reason: Int) { groupReason = reason; groupLatch.countDown() }
        })
        check(groupLatch.await(15, TimeUnit.SECONDS) && groupReason == null && !stopped()) {
            "Wi-Fi Direct group failed reason=$groupReason"
        }

        val credentials = pollGroupCredentials(channel)
        check(credentials != null) { "Wi-Fi Direct group credentials unavailable" }
        val (ssid, password) = credentials!!

        val (iface, address) = findEndpoint(listOf("p2p"))
            ?: throw IllegalStateException("Network IPv4 address unavailable")
        log("Network mode=$networkMode interface=${iface.name} ready=true")
        return Endpoint(ssid, password, channel = 0, iface, address)
    }

    private fun pollGroupCredentials(channel: WifiP2pManager.Channel): Pair<String, String>? {
        val deadline = System.nanoTime() + 10_000_000_000L
        var credentials: Pair<String, String>? = null
        val p2pManager = p2p
        while (!stopped() && credentials == null && System.nanoTime() < deadline) {
            if (p2pManager == null) return null
            val latch = CountDownLatch(1)
            p2pManager.requestGroupInfo(channel, object : WifiP2pManager.GroupInfoListener {
                override fun onGroupInfoAvailable(group: WifiP2pGroup?) {
                    if (group != null && group.isGroupOwner) {
                        val passphrase = group.passphrase
                        if (!passphrase.isNullOrEmpty()) {
                            credentials = group.networkName to passphrase
                        }
                    }
                    latch.countDown()
                }
            })
            latch.await(1, TimeUnit.SECONDS)
        }
        return credentials
    }

    // --- Hotspot automation (hostapd + static IP + dnsmasq through su) ---------------------
    // The miwifi firmware ships a client-only Wi-Fi stack: services.jar has no hostapd /
    // startSoftAp code, the OEM WifiApService only joins external hotspots and WifiApp has
    // no tethering screen. The only working soft-AP is the native three-step sequence
    // (hostapd -> static IP -> dnsmasq), so this mode drives it directly as root.
    private fun prepareAuto(): Endpoint {
        // The helper owns discovery and process identities. Do not infer ownership from
        // another hotspot's hostapd/dnsmasq, a stale address, or an interface name prefix.
        val (prepRc, prepOut) = runOp(OP_HOTSPOT_PREP, timeoutSeconds = 12)
        check(prepRc == 0) { "Hotspot preparation failed rc=$prepRc: ${prepOut.take(200)}" }
        val prepared = parseHotspotMetadata(prepOut)
        // Preserve this app's saved credentials when upgrading the helper. The runtime
        // validates them and keeps its own copy after a successful start.
        val requested = cachedHotspotProfile(prepared)
        if (stopped()) throw InterruptedException("Hotspot preparation stopped")
        // Remove only the helper's owned resources before OEM recovery and a fresh start.
        // Cleanup failure must retain the journal and stop this attempt, not reuse the AP.
        val (downRc, downOut) = runOp(OP_HOTSPOT_DOWN, timeoutSeconds = 20)
        log("Hotspot cleanup rc=$downRc")
        check(downRc == 0) { "Hotspot cleanup failed rc=$downRc: ${downOut.take(200)}" }
        if (stopped()) throw InterruptedException("Hotspot preparation stopped")
        // This is optional OEM recovery: an absent mcreset must not prevent an otherwise
        // capable head unit from starting its AP. WIAP policy is managed by the host.
        val (mcRc, mcOut) = runOp(OP_MC_RESET, timeoutSeconds = 15)
        log("McReset before hotspot rc=$mcRc out=${mcOut.trim().take(200)}")
        if (stopped()) throw InterruptedException("Hotspot preparation stopped")

        val (upRc, upOut) = runOp(
            OP_HOTSPOT_UP, "${requested.ssid}\n${requested.password}\n${requested.channel}", timeoutSeconds = 30,
        )
        // Successful metadata includes credentials, so keep it out of diagnostics.
        log("Hotspot up rc=$upRc")
        check(upRc == 0) { "Hotspot startup failed rc=$upRc: ${upOut.take(200)}" }
        hotspotStartedByUs = true
        if (stopped()) throw InterruptedException("Hotspot preparation stopped")
        val active = parseHotspotMetadata(upOut)
        check(active.ownedActive) { "Helper did not confirm its owned hotspot is ready" }

        val (iface, address) = findEndpoint(emptyList(), active.iface, active.address)
            ?: throw IllegalStateException("Network IPv4 address unavailable")
        cacheHotspotProfile(active)
        log("Network mode=$networkMode interface=${iface.name} ready=true")
        return Endpoint(active.ssid, active.password, active.channel, iface, address, hotspotAddresses(iface, address))
    }

    /** IPv6 DAD can finish after ifconfig returns; keep IPv4 working on IPv4-only firmware. */
    private fun hotspotAddresses(iface: NetworkInterface, ipv4: Inet4Address): List<InetAddress> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!stopped()) {
            // NetworkInterface is a snapshot. Re-read the same interface while DAD settles,
            // preserving its scope for the IPv6 listener (0x4301 strips the zone separately).
            val ipv6 = runCatching {
                val current = NetworkInterface.getByName(iface.name) ?: return@runCatching emptyList()
                if (!current.isUp) return@runCatching emptyList()
                val ready = readyIpv6Addresses(iface.name) ?: return@runCatching emptyList()
                val addresses = current.inetAddresses ?: return@runCatching emptyList()
                val usable = ArrayList<Inet6Address>()
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address !is Inet6Address || !address.isLinkLocalAddress ||
                        address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress
                    ) continue
                    val hex = address.address.joinToString("") {
                        (it.toInt() and 0xff).toString(16).padStart(2, '0')
                    }
                    if (hex !in ready) continue
                    val scoped = Inet6Address.getByAddress(null, address.address, current)
                    if (scoped.scopeId > 0) usable += scoped
                }
                usable
            }.getOrDefault(emptyList())
            if (stopped()) throw InterruptedException("Hotspot address preparation stopped")
            if (ipv6.isNotEmpty()) {
                return (ipv6 + ipv4).also { addresses ->
                    log("Hotspot interface=${iface.name} addresses=${addresses.joinToString { it.hostAddress }}")
                }
            }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) break
            try {
                Thread.sleep(minOf(100L, maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining))))
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            }
        }
        if (stopped()) throw InterruptedException("Hotspot address preparation stopped")
        log("Hotspot interface=${iface.name} IPv6 link-local unavailable; addresses=${ipv4.hostAddress}")
        return listOf(ipv4)
    }

    /** Android's NetworkInterface API omits Linux DAD flags; never advertise a tentative address. */
    private fun readyIpv6Addresses(ifaceName: String): Set<String>? = runCatching {
        File("/proc/net/if_inet6").readLines().mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 6 || fields[5] != ifaceName) return@mapNotNull null
            val flags = fields[4].toIntOrNull(16) ?: return@mapNotNull null
            // IFA_F_DADFAILED / TENTATIVE: the address has not completed duplicate detection.
            if (flags and (0x08 or 0x40) != 0) return@mapNotNull null
            fields[0].lowercase()
        }.toSet()
    }.getOrNull()

    private data class HotspotMetadata(
        val ssid: String,
        val password: String,
        val channel: Int,
        val iface: String,
        val address: String,
        val ownedActive: Boolean,
    )

    /** A new helper must provide a concrete interface; old global-process guesses are unsafe. */
    private fun parseHotspotMetadata(out: String): HotspotMetadata {
        val values = out.lineSequence().mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) null else line.substring(0, eq).trim() to line.substring(eq + 1).removeSuffix("\r")
        }.toMap()
        val iface = values["diplay_iface"]
        check(iface != null && iface.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,31}"))) {
            "Assistant hotspot metadata unavailable; update the assistant"
        }
        val address = values["diplay_address"]
        check(address != null && address.split('.').let { octets ->
            octets.size == 4 && octets.all { octet -> octet.toIntOrNull()?.let { it in 0..255 } == true }
        }) { "Assistant hotspot IPv4 address invalid" }
        val ssid = values["ssid"]
        val password = values["wpa_passphrase"]
        val channel = values["channel"]?.toIntOrNull()
        check(!ssid.isNullOrEmpty() && !password.isNullOrEmpty() && channel != null && channel in 1..13) {
            "Assistant hotspot profile unavailable"
        }
        return HotspotMetadata(ssid, password, channel, iface, address, values["diplay_owned_active"] == "1")
    }

    private fun cachedHotspotProfile(fallback: HotspotMetadata): HotspotMetadata {
        val prefs = context.getSharedPreferences("legacy-network", Context.MODE_PRIVATE)
        val cachedSsid = prefs.getString("hotspotSsid", null)
        val cachedPassword = prefs.getString("hotspotPassword", null)
        val cachedChannel = prefs.getInt("hotspotChannel", -1)
        if (!cachedSsid.isNullOrEmpty() && !cachedPassword.isNullOrEmpty() && cachedChannel in 1..13) {
            return fallback.copy(ssid = cachedSsid, password = cachedPassword, channel = cachedChannel)
        }
        return fallback
    }

    private fun cacheHotspotProfile(profile: HotspotMetadata) {
        context.getSharedPreferences("legacy-network", Context.MODE_PRIVATE).edit()
            .putString("hotspotSsid", profile.ssid)
            .putString("hotspotPassword", profile.password)
            .putInt("hotspotChannel", profile.channel)
            .commit()
    }

    /**
     * Runs a privileged command through the assistant app's opcode channel. This app only
     * sends an opaque [op] token plus optional newline-joined [args]; the real shell script
     * lives in the assistant's CpManager, so decompiling this APK does not reveal the hotspot
     * wiring. Returns (exit, output): -1 means the assistant did not answer.
     */
    private fun runOp(op: String, args: String? = null, timeoutSeconds: Long = 20): Pair<Int, String> {
        val result = LocalAdb(context.applicationContext).use {
            it.execOp(op, args, (timeoutSeconds * 1000).toInt().coerceIn(1, 30_000))
        }
        return if (result == null) -1 to "assistant unreachable"
        else result.exit to result.output.orEmpty()
    }

    // --- Shared helpers --------------------------------------------------------------------

    private fun findEndpoint(
        prefixes: List<String>,
        exactInterface: String? = null,
        exactAddress: String? = null,
    ): Pair<NetworkInterface, Inet4Address>? {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!stopped() && System.nanoTime() < deadline) {
            scanEndpoint(prefixes, exactInterface, exactAddress)?.let { return it }
            Thread.sleep(250)
        }
        return null
    }

    private fun scanEndpoint(
        prefixes: List<String>,
        exactInterface: String? = null,
        exactAddress: String? = null,
    ): Pair<NetworkInterface, Inet4Address>? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        val candidates = ArrayList<NetworkInterface>()
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (exactInterface != null) {
                if (iface.name == exactInterface) candidates.add(iface)
            } else if (prefixes.any { iface.name.startsWith(it) }) candidates.add(iface)
        }
        for (iface in candidates) {
            if (!iface.isUp) continue
            val addresses = iface.inetAddresses ?: continue
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress && !address.isAnyLocalAddress &&
                    (exactAddress == null || address.hostAddress == exactAddress)
                ) {
                    return iface to address
                }
            }
        }
        return null
    }

    private fun currentApConfig(): WifiConfiguration? = runCatching {
        wifi.javaClass.getMethod("getWifiApConfiguration").invoke(wifi) as? WifiConfiguration
    }.getOrNull()

    private fun state(): Int =
        wifi.javaClass.getMethod("getWifiApState").invoke(wifi) as Int

    private fun setAp(config: WifiConfiguration?, enabled: Boolean): Boolean =
        wifi.javaClass
            .getMethod("setWifiApEnabled", WifiConfiguration::class.java, java.lang.Boolean.TYPE)
            .invoke(wifi, config, enabled) == true

    override fun close() {
        // Every successful auto preparation owns the freshly rebuilt hotspot.
        if (hotspotStartedByUs) {
            runOp(OP_HOTSPOT_DOWN)
        }
        val channel = p2pChannel
        val p2pManager = p2p
        if (channel != null && p2pManager != null && Looper.myLooper() != Looper.getMainLooper()) {
            val latch = CountDownLatch(1)
            p2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { latch.countDown() }
                override fun onFailure(reason: Int) {
                    log("Wi-Fi Direct cleanup reason=$reason")
                    latch.countDown()
                }
            })
            try {
                latch.await(2, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        p2pChannel = null
        if (wifiWasEnabledByUs && !stationWasEnabled) {
            runCatching { wifi.setWifiEnabled(false) }
        }
        if (changedAp) {
            runCatching { setAp(null, enabled = false) }
            runCatching { wifi.setWifiEnabled(true) }
        }
    }

    /**
     * Restores the head unit's client Wi-Fi after CarPlay ended and the app exits for good
     * (final app exit, not an in-process wireless restart). The helper restores only the
     * changes in its durable journal, including an earlier interrupted preparation. OEM
     * hotspots and a Wi-Fi station that was originally disabled are left alone.
     */
    fun restoreAfterCarPlay(): Boolean {
        val (rc, out) = runOp(OP_WIFI_RESTORE, timeoutSeconds = 25)
        log("Wifi restart rc=$rc out=${out.trim().take(300)}")
        // Optional firmware recovery: the helper calls only an existing OEM mcreset tool.
        val (mrc, mout) = runOp(OP_MC_RESET, timeoutSeconds = 15)
        log("Media core reset rc=$mrc out=${mout.trim().take(200)}")
        return rc == 0
    }

    private companion object {
        const val AP_STATE_ENABLED = 13

        // Opaque opcodes the assistant app's CpManager maps back to the real hotspot scripts.
        // This APK keeps only these tokens; the actual hostapd/dnsmasq/wifi-restore shell
        // text lives in the assistant process to keep decompilation from revealing the wiring.
        const val OP_HOTSPOT_PREP = "n0"
        const val OP_HOTSPOT_UP = "n1"
        const val OP_HOTSPOT_DOWN = "n2"
        const val OP_WIFI_RESTORE = "n4"
        const val OP_MC_RESET = "n5"
    }
}
