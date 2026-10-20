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
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.shilapi.xcertplay.adb.LocalAdb

/**
 * Brings up the wireless link for CarPlay:
 *
 *  - `direct`: Wi-Fi Direct Group Owner via [WifiP2pManager.createGroup] (needs P2P-capable firmware).
 *  - `auto`: start the hotspot natively over root (hostapd -> static IP -> dnsmasq), since the
 *    miwifi firmware's `setWifiApEnabled` never launches hostapd; reuses it when already up.
 *  - `app` / `saved`: the head unit becomes a WPA2 hotspot through the hidden
 *    `WifiManager.setWifiApEnabled` reflection API (broken on this firmware).
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
        "auto" -> prepareAuto()
        else -> prepareSoftAp()
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
        // Fast path: this mode's hotspot is already up, keep the connection intact.
        // InetAddress.toString() prefixes a slash ("/192.168.43.1"), compare hostAddress.
        // The address alone is not enough: the kernel keeps the static address after
        // `ifconfig down`, so a stopped hotspot would still match. Neither is dnsmasq
        // alone: the firmware runs its own dnsmasq for the car's hotspot, and a leftover
        // pid file can pass its check while our hostapd is long dead (the phone then
        // joins the car hotspot and video never arrives). Require both to be alive.
        val existing = scanEndpoint(listOf("wlan", "ap"))
        if (existing != null && existing.second.hostAddress.startsWith("192.168.43.") &&
            isDnsmasqAlive() && isHostapdAlive()
        ) {
            hotspotStartedByUs = false
            raiseRmemMax()
            val (ssid, password, channel) = hotspotProfile()
            log("Network mode=$networkMode interface=${existing.first.name} already up")
            return Endpoint(ssid, password, channel, existing.first, existing.second)
        }

        // Heavy path: one su call covers the root check, the firmware's hotspot profile and
        // the rmem_max raise. Three separate su spawns cost about a second each on this unit.
        val (prepRc, prepOut) = runOp(OP_HOTSPOT_PREP, timeoutSeconds = 8)
        if (prepRc != 0 || !prepOut.contains("uid=0")) {
            throw IllegalStateException("H a n r请检查权限是否开启")
        }
        val (ssid, password, channel) = parseAndCacheHotspotProfile(prepOut)

        val (upRc, upOut) = runOp(OP_HOTSPOT_UP, "$ssid\n$password\n$channel")
        log("Hotspot up rc=$upRc out=${upOut.trim().take(300)}")
        check(upRc == 0) { "Hotspot startup failed rc=$upRc" }
        // The script's exit code only reflects dnsmasq, its last command: hostapd can
        // silently fail to start (unreadable conf after its privilege drop) while the
        // rest succeeds, which ends as "Connected" without any video. Fail loudly.
        check(isHostapdAlive()) { "hostapd did not start, see logcat tag hostapd" }
        hotspotStartedByUs = true

        val (iface, address) = findEndpoint(listOf("wlan", "ap"))
            ?: throw IllegalStateException("Network IPv4 address unavailable")
        log("Network mode=$networkMode interface=${iface.name} ready=true")
        return Endpoint(ssid, password, channel, iface, address)
    }

    /**
     * The (ssid, passphrase, channel) the broadcast AP uses. The heavy path reads them from
     * the firmware's hostapd.conf over su and caches the triple, so a later fast path can
     * rebuild the endpoint without paying another su spawn. A cold cache falls back to one
     * su read (or the known defaults when root is unavailable).
     */
    private fun hotspotProfile(): Triple<String, String, Int> {
        val prefs = context.getSharedPreferences("legacy-network", Context.MODE_PRIVATE)
        val cachedSsid = prefs.getString("hotspotSsid", null)
        val cachedPassword = prefs.getString("hotspotPassword", null)
        val cachedChannel = prefs.getInt("hotspotChannel", -1)
        if (cachedSsid != null && cachedPassword != null && cachedChannel > 0) {
            return Triple(cachedSsid, cachedPassword, cachedChannel)
        }
        val (rc, out) = runOp(OP_HOTSPOT_PREP, timeoutSeconds = 8)
        if (rc != 0) return Triple("DiPlay-Legacy", "diplay1234", 6)
        return parseAndCacheHotspotProfile(out)
    }

    /** Parses (ssid, passphrase, channel) from the combined su prep output, falling back to the known defaults. */
    private fun parseAndCacheHotspotProfile(out: String): Triple<String, String, Int> {
        var ssid = "DiPlay-Legacy"
        var password = "diplay1234"
        var channel = 6
        for (line in out.lines()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            when (key) {
                "ssid" -> ssid = value
                "wpa_passphrase" -> password = value
                "channel" -> channel = value.toIntOrNull() ?: channel
            }
        }
        context.getSharedPreferences("legacy-network", Context.MODE_PRIVATE).edit()
            .putString("hotspotSsid", ssid)
            .putString("hotspotPassword", password)
            .putInt("hotspotChannel", channel)
            .commit()
        return Triple(ssid, password, channel)
    }

    /**
     * Raises the kernel receive-buffer ceiling to 2 MB. The miwifi kernel boots with
     * net.core.rmem_max=131071, which silently caps SO_RCVBUF at ~256 KB no matter what the
     * app requests, and the TCP window scale negotiated at SYN time is derived from that
     * capped buffer. Without this, the screen stream's 2 MB receive window never takes
     * effect and Wi-Fi bursts still stall reads. tcp_rmem's ceiling is already 2097152.
     * Best effort: any su failure is logged and the connection still works with the cap.
     */
    private fun raiseRmemMax() {
        val (rc, out) = runOp(OP_RMEM, timeoutSeconds = 5)
        if (rc != 0) log("rmem_max raise failed rc=$rc out=${out.trim().take(120)}")
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

    // --- Hotspot via setWifiApEnabled reflection (app / saved) -----------------------------

    private fun prepareSoftAp(): Endpoint {
        oldApConfig = currentApConfig()
        apWasEnabled = runCatching { state() == AP_STATE_ENABLED }.getOrDefault(false)
        stationWasEnabled = wifi.isWifiEnabled

        var config = oldApConfig ?: WifiConfiguration()
        if (networkMode == "app") {
            val prefs = context.getSharedPreferences("receiver-network", Context.MODE_PRIVATE)
            var password = prefs.getString("password", null)
            if (password == null) {
                password = UUID.randomUUID().toString().replace("-", "").take(16)
                prefs.edit().putString("password", password).commit()
            }
            config = WifiConfiguration().apply {
                SSID = "DiPlay-Legacy"
                preSharedKey = password
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            }
        }

        val ssid = config.SSID
        val key = config.preSharedKey
        check(!ssid.isNullOrBlank() && !key.isNullOrEmpty() &&
            config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) {
            "Configure a WPA2 hotspot first"
        }

        if (!apWasEnabled || networkMode == "app") {
            changedAp = true
            try {
                if (apWasEnabled) {
                    check(setAp(oldApConfig, enabled = false)) { "could not stop the existing hotspot" }
                }
                if (stationWasEnabled) {
                    check(wifi.setWifiEnabled(false)) { "could not disable Wi-Fi station" }
                }
                check(!stopped() && setAp(config, enabled = true)) {
                    "Hotspot rejected by firmware"
                }
                waitForApState()
            } catch (e: Throwable) {
                // A rejected hotspot must not leave the head unit with Wi-Fi off. Restore the station
                // so the device stays reachable on the local network.
                runCatching { setAp(null, enabled = false) }
                if (stationWasEnabled) runCatching { wifi.setWifiEnabled(true) }
                throw e
            }
        }

        val channel = runCatching {
            config.javaClass.getField("apChannel").getInt(config).coerceIn(0, 255)
        }.getOrDefault(0)
        val endpointSsid = unquote(ssid)
        val endpointPassword = unquote(key)
        val (iface, address) = findEndpoint(listOf("wlan", "ap"))
            ?: throw IllegalStateException("Network IPv4 address unavailable")
        log("Network mode=$networkMode interface=${iface.name} ready=true")
        return Endpoint(endpointSsid, endpointPassword, channel, iface, address)
    }

    private fun waitForApState() {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!stopped() && System.nanoTime() < deadline) {
            if (runCatching { state() == AP_STATE_ENABLED }.getOrDefault(false)) return
            Thread.sleep(250)
        }
        check(!stopped() && runCatching { state() == AP_STATE_ENABLED }.getOrDefault(false)) {
            "Hotspot startup timed out"
        }
    }

    // --- Shared helpers --------------------------------------------------------------------

    private fun findEndpoint(prefixes: List<String>): Pair<NetworkInterface, Inet4Address>? {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!stopped() && System.nanoTime() < deadline) {
            scanEndpoint(prefixes)?.let { return it }
            Thread.sleep(250)
        }
        return null
    }

    private fun scanEndpoint(prefixes: List<String>): Pair<NetworkInterface, Inet4Address>? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        val candidates = ArrayList<NetworkInterface>()
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (prefixes.any { iface.name.startsWith(it) }) candidates.add(iface)
        }
        for (iface in candidates) {
            val addresses = iface.inetAddresses ?: continue
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress && !address.isAnyLocalAddress) {
                    return iface to address
                }
            }
        }
        return null
    }

    /**
     * True when the hotspot's dnsmasq is still alive: reads its pid file and verifies
     * `/proc/<pid>/cmdline`. Only the fast path uses this, so a false negative just means
     * the heavy path restarts the hotspot from scratch.
     */
    private fun isDnsmasqAlive(): Boolean = runCatching {
        val pid = File("/data/local/tmp/dnsmasq.pid").readText().trim().toIntOrNull()
            ?: return@runCatching false
        File("/proc/$pid/cmdline").readText().contains("dnsmasq")
    }.getOrDefault(false)

    /**
     * True when a hostapd process is running: scans /proc for a cmdline containing
     * "hostapd". Used by the fast path and after a cold start, so a false negative just
     * restarts the hotspot from scratch. Unlike the su scripts this scan runs in the app
     * process, whose cmdline never contains "hostapd", so it cannot match itself.
     */
    private fun isHostapdAlive(): Boolean = runCatching {
        val procs = File("/proc").listFiles() ?: return@runCatching false
        procs.any { proc ->
            proc.name.toIntOrNull() != null &&
                runCatching { File(proc, "cmdline").readText() }
                    .getOrDefault("").contains("hostapd")
        }
    }.getOrDefault(false)

    private fun currentApConfig(): WifiConfiguration? = runCatching {
        wifi.javaClass.getMethod("getWifiApConfiguration").invoke(wifi) as? WifiConfiguration
    }.getOrNull()

    private fun state(): Int =
        wifi.javaClass.getMethod("getWifiApState").invoke(wifi) as Int

    private fun setAp(config: WifiConfiguration?, enabled: Boolean): Boolean =
        wifi.javaClass
            .getMethod("setWifiApEnabled", WifiConfiguration::class.java, java.lang.Boolean.TYPE)
            .invoke(wifi, config, enabled) == true

    private fun unquote(value: String): String = value.removeSurrounding("\"")

    override fun close() {
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
     * (final app exit, not an in-process wireless restart). Tears any leftover hostapd/dnsmasq
     * hotspot down regardless of who started it — on the fast path the hotspot was reused, not
     * started by us — and bounces the stack: the station side does not rescan wlan0 by itself
     * after hostapd released it, so only a disable -> enable cycle makes the head unit rejoin
     * its saved Wi-Fi networks. Runs over the same su broadcast channel as the boot path
     * (LocalAdb -> assistant CpManager). The teardown is idempotent, so repeating it after
     * [close]'s own teardown is harmless; the final `svc wifi enable` decides the exit code.
     */
    fun restoreAfterCarPlay(): Boolean {
        val (rc, out) = runOp(OP_WIFI_RESTORE, timeoutSeconds = 25)
        log("Wifi restart rc=$rc out=${out.trim().take(300)}")
        // 无论 Wi-Fi 是否还原成功，都再走原车 mcreset 重置媒体核心（stop jmcs -> stop mdnsd ->
        // kill mcservice -> start mdnsd -> start jmcs），把 DiPlay 退出后残留的 iAP2/USB host
        // 状态机拉回干净状态，使原车有线 CarPlay 重新可 claim。
        val (mrc, mout) = runOp(OP_MC_RESET, timeoutSeconds = 15)
        log("Media core reset rc=$mrc out=${mout.trim().take(200)}")
        return rc == 0
    }

    private companion object {
        const val AP_STATE_ENABLED = 13

        // Opaque opcodes the assistant app's CpManager maps back to the real hotspot scripts.
        // This APK keeps only these tokens; the actual hostapd/dnsmasq/rmem/wifi-restore shell
        // text lives in the assistant process to keep decompilation from revealing the wiring.
        const val OP_HOTSPOT_PREP = "n0"
        const val OP_HOTSPOT_UP = "n1"
        const val OP_HOTSPOT_DOWN = "n2"
        const val OP_RMEM = "n3"
        const val OP_WIFI_RESTORE = "n4"
        const val OP_MC_RESET = "n5"
    }
}