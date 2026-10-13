package local.airuize.diplaylegacy

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import java.io.Closeable
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Brings up the wireless link for CarPlay. Mirrors the reference implementation's three modes:
 *
 *  - `direct`: Wi-Fi Direct Group Owner via [WifiP2pManager.createGroup] (needs P2P-capable firmware).
 *  - `router`: the head unit and iPhone join the same WPA2 router.
 *  - `reuse`: discover an already-running hotspot without touching Wi-Fi.
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
        "router" -> prepareRouter()
        "reuse" -> prepareReuse()
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

    // --- Same-WPA2-router ------------------------------------------------------------------

    private fun prepareRouter(): Endpoint {
        check(wifi.isWifiEnabled) {
            "Connect head unit and iPhone to the same WPA2 router first"
        }
        val ssid = unquote(wifi.connectionInfo.ssid ?: "")
        val password = context.getSharedPreferences("receiver-ui", Context.MODE_PRIVATE)
            .getString("routerPassword", "") ?: ""
        val passwordValid = password.length in 8 until 64
        check(ssid.isNotBlank() && ssid != "<unknown ssid>" && passwordValid) {
            "Router WPA2 password must be configured"
        }
        val (iface, address) = findEndpoint(listOf("wlan", "ap"))
            ?: throw IllegalStateException("Network IPv4 address unavailable")
        log("Network mode=$networkMode interface=${iface.name} ready=true")
        return Endpoint(ssid, password, channel = 0, iface, address)
    }

    // --- Reuse an already-running hotspot (manual hostapd/dnsmasq) -------------------------
    // The miwifi firmware's setWifiApEnabled does not start hostapd, so the hotspot is brought
    // up manually (root) and this mode only discovers the endpoint without touching Wi-Fi.
    private fun prepareReuse(): Endpoint {
        val (iface, address) = findEndpoint(listOf("wlan", "ap"))
            ?: throw IllegalStateException("Network IPv4 address unavailable")
        log("Network mode=$networkMode interface=${iface.name} ready=true")
        return Endpoint("DiPlay-Legacy", "diplay1234", channel = 6, iface, address)
    }

    // --- Hotspot automation (hostapd + static IP + dnsmasq through su) ---------------------
    // The miwifi firmware ships a client-only Wi-Fi stack: services.jar has no hostapd /
    // startSoftAp code, the OEM WifiApService only joins external hotspots and WifiApp has
    // no tethering screen. The only working soft-AP is the native three-step sequence
    // (hostapd -> static IP -> dnsmasq), so this mode drives it directly as root.
    private fun prepareAuto(): Endpoint {
        val (ssid, password, channel) = readHotspotConfig()

        // Fast path: this mode's hotspot is already up, keep the connection intact.
        // InetAddress.toString() prefixes a slash ("/192.168.43.1"), compare hostAddress.
        val existing = scanEndpoint(listOf("wlan", "ap"))
        if (existing != null && existing.second.hostAddress.startsWith("192.168.43.")) {
            hotspotStartedByUs = false
            log("Network mode=$networkMode interface=${existing.first.name} already up")
            return Endpoint(ssid, password, channel, existing.first, existing.second)
        }

        val (idRc, idOut) = runSu(SU_ID, timeoutSeconds = 5)
        if (idRc != 0 || !idOut.contains("uid=0")) {
            throw IllegalStateException("Hotspot automation needs root: grant su once over USB adb")
        }

        val (upRc, upOut) = runSu(SU_HOTSPOT_UP)
        log("Hotspot up rc=$upRc out=${upOut.trim().take(300)}")
        check(upRc == 0) { "Hotspot startup failed rc=$upRc" }
        hotspotStartedByUs = true

        val (iface, address) = findEndpoint(listOf("wlan", "ap"))
            ?: throw IllegalStateException("Network IPv4 address unavailable")
        log("Network mode=$networkMode interface=${iface.name} ready=true")
        return Endpoint(ssid, password, channel, iface, address)
    }

    /** Reads (ssid, passphrase, channel) from /data/misc/wifi/hostapd.conf, falling back to the known defaults. */
    private fun readHotspotConfig(): Triple<String, String, Int> {
        var ssid = "DiPlay-Legacy"
        var password = "diplay1234"
        var channel = 6
        val (rc, out) = runSu("cat /data/misc/wifi/hostapd.conf", timeoutSeconds = 5)
        if (rc == 0) {
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
        }
        return Triple(ssid, password, channel)
    }

    private fun runSu(script: String, timeoutSeconds: Long = 20): Pair<Int, String> {
        val process = try {
            ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return -1 to (e.message ?: "su unavailable")
        }
        val output = StringBuilder()
        val reader = thread(start = true, isDaemon = true) {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { output.append(it).append('\n') }
            }
        }
        // Process.waitFor(long, TimeUnit) needs API 26; poll exitValue() instead for API 17.
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        var exitValue = -1
        var finished = false
        while (!finished) {
            try {
                exitValue = process.exitValue()
                finished = true
            } catch (_: IllegalThreadStateException) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    runCatching { process.destroy() }
                    return -1 to "timed out"
                }
                try {
                    Thread.sleep(if (remaining < 100) remaining else 100)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        try {
            reader.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return process.exitValue() to output.toString()
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
            runSu(SU_HOTSPOT_DOWN)
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

    private companion object {
        const val AP_STATE_ENABLED = 13

        // Verified on the device's toolbox-only shell: no awk/xargs/killall, so leftovers
        // are stopped through the dnsmasq pid file and a /proc scan.
        const val SU_ID = "id"

        // The /proc scan must skip its own shell: with "su -c <inline script>" the sh process
        // cmdline contains the script text (including "hostapd"), so the scan would SIGTERM
        // itself and fail with exit 143.
        const val SU_HOTSPOT_UP = "if [ -f /data/local/tmp/dnsmasq.pid ]; then kill \$(cat /data/local/tmp/dnsmasq.pid) 2>/dev/null; sleep 1; fi; " +
            "for p in /proc/[0-9]*; do [ \${p#/proc/} -eq \$\$ ] && continue; c=\$(cat \$p/cmdline 2>/dev/null); case \$c in *hostapd*) kill \${p#/proc/} 2>/dev/null ;; esac; done; " +
            "sleep 1; setprop ctl.stop mdnsd; ifconfig wlan0 down; " +
            "/system/bin/hostapd -B /data/misc/wifi/hostapd.conf; sleep 1; " +
            "ifconfig wlan0 192.168.43.1 netmask 255.255.255.0 up; " +
            "dnsmasq --interface=wlan0 --bind-interfaces --dhcp-range=192.168.43.10,192.168.43.100,12h " +
            "--dhcp-option=3,192.168.43.1 --dhcp-option=6,8.8.8.8 --no-resolv --pid-file=/data/local/tmp/dnsmasq.pid"

        const val SU_HOTSPOT_DOWN = "if [ -f /data/local/tmp/dnsmasq.pid ]; then kill \$(cat /data/local/tmp/dnsmasq.pid) 2>/dev/null; fi; " +
            "for p in /proc/[0-9]*; do [ \${p#/proc/} -eq \$\$ ] && continue; c=\$(cat \$p/cmdline 2>/dev/null); case \$c in *hostapd*) kill \${p#/proc/} 2>/dev/null ;; esac; done"
    }
}