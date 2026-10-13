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

/**
 * Brings up the wireless link for CarPlay. Mirrors the reference implementation's three modes:
 *
 *  - `direct`: Wi-Fi Direct Group Owner via [WifiP2pManager.createGroup] (needs P2P-capable firmware).
 *  - `router`: the head unit and iPhone join the same WPA2 router.
 *  - `app` / `saved`: the head unit becomes a WPA2 hotspot through the hidden
 *    `WifiManager.setWifiApEnabled` reflection API. `app` auto-generates a "DiPlay-Legacy"
 *    hotspot; `saved` reuses the firmware's already-configured hotspot.
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

    fun prepare(): Endpoint = when (networkMode) {
        "direct" -> prepareDirect()
        "router" -> prepareRouter()
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
            Thread.sleep(250)
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
    }
}