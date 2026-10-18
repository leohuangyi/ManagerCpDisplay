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
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.shilapi.xcertplay.adb.LocalAdb

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
        raiseRmemMax()
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
        raiseRmemMax()
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
        val (prepRc, prepOut) = runSu(SU_HOTSPOT_PREP, timeoutSeconds = 8)
        if (prepRc != 0 || !prepOut.contains("uid=0")) {
            throw IllegalStateException("Hotspot automation needs root")
        }
        val (ssid, password, channel) = parseAndCacheHotspotProfile(prepOut)

        val (upRc, upOut) = runSu(hotspotUpScript(ssid, password, channel))
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
     * Builds the su script that brings the hotspot up with an 802.11n/WMM profile taken from
     * the values read out of the firmware's hostapd.conf (defaults when unreadable). The profile
     * goes to a private file: see SU_HOTSPOT_UP_PREFIX for why /data/misc/wifi cannot be used.
     */
    private fun hotspotUpScript(ssid: String, password: String, channel: Int): String {
        val s = ssid.replace("'", "'\\''")
        val p = password.replace("'", "'\\''")
        return SU_HOTSPOT_UP_PREFIX +
            "echo interface=wlan0 > /data/local/tmp/diplay_hostapd.conf; " +
            "echo driver=nl80211 >> /data/local/tmp/diplay_hostapd.conf; " +
            "echo ssid='$s' >> /data/local/tmp/diplay_hostapd.conf; " +
            "echo hw_mode=g >> /data/local/tmp/diplay_hostapd.conf; " +
            "echo channel=$channel >> /data/local/tmp/diplay_hostapd.conf; " +
            "echo ieee80211n=1 >> /data/local/tmp/diplay_hostapd.conf; " +
            "echo ht_capab=[SHORT-GI-20] >> /data/local/tmp/diplay_hostapd.conf; " +
            "echo wmm_enabled=1 >> /data/local/tmp/diplay_hostapd.conf; " +
            "echo wpa=2 >> /data/local/tmp/diplay_hostapd.conf; " +
            "echo wpa_passphrase='$p' >> /data/local/tmp/diplay_hostapd.conf; " +
            "echo wpa_key_mgmt=WPA-PSK >> /data/local/tmp/diplay_hostapd.conf; " +
            "echo wpa_pairwise=CCMP >> /data/local/tmp/diplay_hostapd.conf; " +
            SU_HOTSPOT_UP_TAIL
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
        val (rc, out) = runSu(SU_HOTSPOT_PREP, timeoutSeconds = 8)
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
        val (rc, out) = runSu(SU_RMEM_MAX, timeoutSeconds = 5)
        if (rc != 0) log("rmem_max raise failed rc=$rc out=${out.trim().take(120)}")
    }

    /** Runs a rooted script in the assistant app, reached over broadcast (no local su anymore). */
    private fun runSu(script: String, timeoutSeconds: Long = 20): Pair<Int, String> {
        val result = LocalAdb(context.applicationContext).use {
            it.exec(script, (timeoutSeconds * 1000).toInt().coerceIn(1, 30_000))
        }
        return result?.let { it.exit to it.output.orEmpty() } ?: (-1 to "cp unavailable")
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

        // One su round-trip for the three pre-flight steps the heavy path used to run
        // separately: the root check, the firmware hotspot profile read and the rmem_max
        // raise. Each su spawn costs about a second on the MY16ADA; rmem_max goes last so
        // this script's exit code reflects it.
        const val SU_HOTSPOT_PREP =
            "id; " +
                "cat /data/misc/wifi/hostapd.conf 2>/dev/null; " +
                "echo 2097152 > /proc/sys/net/core/rmem_max"

        // The miwifi kernel boots with net.core.rmem_max=131071, capping SO_RCVBUF at
        // ~256 KB and shrinking the SYN-time window scale. Raise it before the video
        // listener binds so the 2 MB screen-stream receive window is actually advertised.
        const val SU_RMEM_MAX = "echo 2097152 > /proc/sys/net/core/rmem_max"

        // Verified on the device's toolbox-only shell: no awk/xargs/killall, so leftovers
        // are stopped through a validated dnsmasq pid file and a /proc scan for hostapd.
        // The dnsmasq kill checks /proc/<pid>/cmdline first: the pid file may be stale (PID
        // recycled to an unrelated process) or polluted by a shell user, and a blind kill
        // could SIGTERM an innocent process. It must never touch its own shell either.
        const val SU_KILL_DNSMASQ = "p=\$(cat /data/local/tmp/dnsmasq.pid 2>/dev/null); " +
            "if [ -n \"\$p\" ] && [ \"\$p\" != \"\$\$\" ]; then c=\$(cat /proc/\$p/cmdline 2>/dev/null); " +
            "case \$c in *dnsmasq*) kill \$p 2>/dev/null; sleep 1 ;; esac; fi; "

        // The /proc scan must skip its own shell: with "su -c <inline script>" the sh process
        // cmdline contains the script text (including "hostapd"), so the scan would SIGTERM
        // itself and fail with exit 143.
        // The native hotspot profile is plain 11g: no A-MPDU frame aggregation and no WMM, so
        // every frame pays full 11g airtime and video never gets EDCA priority. On this 2.4 GHz
        // link that showed as 500 ms+ read stalls; with the HT profile, readMaxMs dropped to
        // 145-200 ms and readsOver250Ms went to zero over dynamic scenes.
        // hostapd reads /data/local/tmp/diplay_hostapd.conf, which DiPlay regenerates on every
        // cold start: the miwifi daemon re-chowns /data/misc/wifi while a hotspot runs (even
        // root gets EACCES there) and rewrites its own hostapd.conf back to the 11g template,
        // so injecting into that file is unreliable. A private conf sidesteps both.
        const val SU_HOTSPOT_UP_PREFIX = SU_KILL_DNSMASQ +
            "k=0; for p in /proc/[0-9]*; do [ \${p#/proc/} -eq \$\$ ] && continue; " +
            "c=\$(cat \$p/cmdline 2>/dev/null); case \$c in *hostapd*) kill \${p#/proc/} 2>/dev/null; k=1 ;; esac; done; " +
            "[ \$k -gt 0 ] && sleep 1; setprop ctl.stop mdnsd; ifconfig wlan0 down; "

        // hostapd drops privileges to AID_WIFI after starting, so a root-owned 600 conf
        // is unreadable ("Could not open configuration file" in logcat) and the AP never
        // comes up. The conf holds only the WPA2 passphrase, which the head unit needs
        // anyway to run the AP; 644 keeps it world-readable like /data/misc/wifi files.
        const val SU_HOTSPOT_UP_TAIL =
            "chmod 644 /data/local/tmp/diplay_hostapd.conf; " +
            "/system/bin/hostapd -B /data/local/tmp/diplay_hostapd.conf; sleep 1; " +
            "ifconfig wlan0 192.168.43.1 netmask 255.255.255.0 up; " +
            "dnsmasq --interface=wlan0 --bind-interfaces --dhcp-range=192.168.43.10,192.168.43.100,12h " +
            "--dhcp-option=3,192.168.43.1 --dhcp-option=6,8.8.8.8 --no-resolv --pid-file=/data/local/tmp/dnsmasq.pid"

        // Also drop the static address: `ifconfig down` alone keeps it in the kernel, which
        // would make the next start's fast path see a "hotspot" without any services.
        const val SU_HOTSPOT_DOWN = SU_KILL_DNSMASQ +
            "for p in /proc/[0-9]*; do [ \${p#/proc/} -eq \$\$ ] && continue; c=\$(cat \$p/cmdline 2>/dev/null); case \$c in *hostapd*) kill \${p#/proc/} 2>/dev/null ;; esac; done; " +
            "ifconfig wlan0 0.0.0.0"
    }
}