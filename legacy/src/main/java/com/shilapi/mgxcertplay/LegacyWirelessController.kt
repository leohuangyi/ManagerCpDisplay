package com.shilapi.mgxcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.network.CarPlayBonjourProtocol
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Orchestrates Wireless CarPlay without the API 21+ wireless stack. It mirrors the reference
 * implementation's bootstrap: car hotspot -> Bluetooth RFCOMM iAP2 identification/MFi -> hand iPhone
 * the Wi-Fi endpoint -> iPhone joins -> Wi-Fi iAP2 tunnel + AirPlay session.
 */
class LegacyWirelessController(
    private val context: Context,
    private val sink: MediaSink,
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    private val onConnectionFailure: (String) -> Unit = {},
    /** An established AirPlay session ended: its control connection is gone for good. */
    private val onSessionEnded: () -> Unit = {},
    private val onHostUi: () -> Unit = {},
    private val networkMode: String = "router",
) : Closeable {

    companion object {
        private const val TAG = "LegacyWireless"
        private const val RFCOMM_UUID = "00000000-deca-fade-deca-deafdecacafe"
        private const val SOURCE_VERSION = "950.7.1"
        // The stale RFCOMM channel of a just-closed bootstrap can take several seconds to
        // clear in this head unit's Bluetooth service, so give the connect a wide window.
        private const val RFCOMM_CONNECT_ATTEMPTS = 5
        private const val RFCOMM_CONNECT_RETRY_DELAY_MS = 2000L
        /** AirPlay resource id and changeModes transfer types for the CarPlay main screen. */
        private const val RESOURCE_ID_MAIN_SCREEN = 1
        private const val TRANSFER_BORROW = 3
        private const val TRANSFER_UNBORROW = 4
        /** AirPlayTransferPriority UserInitiated: the driver's own request outranks the iPhone. */
        private const val TRANSFER_PRIORITY_USER_INITIATED = 500
        /** AirPlayConstraint Never: the iPhone may not end this borrow on its own. */
        private const val CONSTRAINT_NEVER = 1000
        /** Apple's predefined borrowID for a screen the vehicle is not showing. */
        private const val BORROW_ID_DISPLAY_OFF = "displayOff"
    }

    private val closed = AtomicBoolean(false)
    private val resources = CopyOnWriteArrayList<Closeable>()
    private val wakeLocks = CopyOnWriteArrayList<PowerManager.WakeLock>()
    private val wifiLocks = CopyOnWriteArrayList<WifiManager.WifiLock>()
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    private val sessions = CopyOnWriteArrayList<AirPlaySession>()
    @Volatile private var activeSession: AirPlaySession? = null

    private val sessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            activeSession = session
            log("AirPlay session active; waiting for video")
            // CarPlay 控制会话建立后，请求小助手切到蓝牙媒体源(19)，让音频从蓝牙侧出来。每次新会话重新请求一次。
            LocalAdb(context).use { it.requestSourceSwitch() }
        }

        override fun onSessionEnded(session: AirPlaySession) {
            sessions.remove(session)
            if (activeSession === session) activeSession = null
            log("AirPlay session ended")
            onSessionEnded()
        }

        override fun onTransportError(message: String) {
            log("AirPlay transport error: $message")
            onConnectionFailure("AirPlay transport error")
        }

        override fun onDebugLog(message: String) {
            log("AirPlay: $message")
        }

        override fun onHostUiRequested(session: AirPlaySession) {
            onHostUi()
        }

        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            // modesChanged (and the changeModes family) reports which side owns each resource:
            // entity 1 = iPhone, 2 = accessory. Tracked for the logs: a borrow shows up as
            // entity 2 and the unborrow returns it to 1.
            if (type != "modesChanged" && type != "changeModes") return
            val states = (params["resources"] as? List<*>).orEmpty().mapNotNull { entry ->
                val resource = entry as? Map<*, *> ?: return@mapNotNull null
                "res=${resource["resourceID"]}(entity=${resource["entity"]},permanent=${resource["permanentEntity"]})"
            }
            log("iPhone resource modes ($type): ${states.joinToString(" ") { it }}")
            // The appStates payload explains why the iPhone re-announced modes (for example a
            // music play/pause flipping an audio app) and is the evidence that ties a stream
            // stall to the iPhone-side state change; it is logged rather than acted upon.
            params["appStates"]?.let { log("iPhone app states ($type): ${it.toString().take(300)}") }
        }
    }

    fun start(phone: BluetoothDevice) {
        Thread({ bootstrap(phone) }, "legacy-carplay-start").apply {
            isDaemon = true
            start()
        }
    }

    fun sendTouch(contacts: List<AirPlayContact>): Boolean {
        val session = activeSession ?: return false
        return runCatching { session.sendTouch(contacts) }.getOrDefault(false)
    }

    /**
     * The host activity left the foreground. The accessory borrows the CarPlay main screen
     * (changeModes, transferType 3, borrowID "displayOff"), Apple's pattern for a screen the
     * vehicle is about to hide: the iPhone answers by stopping the screen stream itself, and it
     * knows the stream is down. The local suspension stays as a fallback for an iPhone that
     * ignores the borrow. The control session survives; on resume [unborrowMainScreen] returns
     * the resource and the iPhone re-establishes the video stream with its own retries.
     */
    fun pauseVideo(): Boolean {
        val session = activeSession
        if (session == null) {
            // The silent path here once let a dead-session pause hide for hours: the borrow
            // cannot go out without a session, so say so instead of returning quietly.
            log("video pause skipped: no live AirPlay session")
            return false
        }
        val taken = sendCommand(changeModes(transferType = TRANSFER_BORROW))
        val torn = runCatching { session.suspendScreenStreams() }.getOrDefault(false)
        if (torn) log("video suspended for the background")
        if (taken) log("main screen borrow sent: the iPhone stops the stream itself")
        return torn || taken
    }

    /**
     * Returns the borrowed main screen to the iPhone (changeModes, transferType 4): CarPlay UI
     * takes it back and re-establishes the video stream over the live control session without
     * a full reboot. Sending the unborrow again is harmless when the stream is already back.
     */
    fun unborrowMainScreen(): Boolean = sendCommand(changeModes(transferType = TRANSFER_UNBORROW))

    /** The changeModes command that borrows or returns the CarPlay main screen resource. */
    private fun changeModes(transferType: Int): Map<String, Any?> {
        val entry = linkedMapOf<String, Any?>(
            "resourceID" to RESOURCE_ID_MAIN_SCREEN,
            "transferType" to transferType,
        )
        if (transferType == TRANSFER_BORROW) {
            entry["transferPriority"] = TRANSFER_PRIORITY_USER_INITIATED
            // The official Borrow wire format keys this "unborrowConstraint" and rejects a borrow
            // without a borrowID; "displayOff" is Apple's predefined id for a hidden screen.
            entry["unborrowConstraint"] = CONSTRAINT_NEVER
            entry["borrowID"] = BORROW_ID_DISPLAY_OFF
        } else {
            // Unborrow carries only the borrowID; constraint keys are rejected on it.
            entry["borrowID"] = BORROW_ID_DISPLAY_OFF
        }
        return mapOf("type" to "changeModes", "params" to mapOf("resources" to listOf(entry)))
    }

    /**
     * Asks the iPhone for a fresh key frame. Recovers a stream that still runs but renders
     * black after the surface swap; harmless when the screen stream is already down.
     */
    fun requestKeyFrame(): Boolean = sendCommand(mapOf("type" to "forceKeyFrame"))

    /** Sends a CarPlay control command over the live session; false with no live session. */
    private fun sendCommand(command: Map<String, Any?>): Boolean {
        val session = activeSession ?: return false
        return runCatching { session.sendCommand(command) }.getOrDefault(false).also { sent ->
            log(if (sent) "iPhone command sent: ${command["type"]}" else "iPhone command not sent: ${command["type"]}")
        }
    }

    private fun bootstrap(phone: BluetoothDevice) {
        try {
            runBootstrap(phone)
        } catch (e: Throwable) {
            if (!closed.get()) {
                Log.e(TAG, "bootstrap failed", e)
                failure("bootstrap", e)
            }
        }
    }

    private fun runBootstrap(phone: BluetoothDevice) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw IllegalStateException("Android Bluetooth is unavailable")
        check(adapter.isEnabled) { "please enable Bluetooth first" }

        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DiPlay:wireless")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        wakeLocks += wakeLock

        val wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DiPlay:wireless")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        wifiLocks += wifiLock

        val btMac = adapter.address ?: throw IllegalStateException("unable to read Bluetooth address")

        // A cold start used to run its three independent stages back to back: hotspot bring-up
        // (the long pole), MFi/media session setup, and the HFP disconnect that frees the
        // RFCOMM channel. They now run on three parallel tracks and meet at a latch; a failure
        // on any track aborts the bootstrap without waiting for the others. The latch gives
        // the join the visibility a plain volatile read could not guarantee on every JVM.
        val network = LegacyNetwork(context, networkMode, { closed.get() }, log).also { resources += it }
        var endpoint: LegacyNetwork.Endpoint? = null
        var parts: SessionParts? = null
        val failures = CopyOnWriteArrayList<Throwable>()
        val tracks = CountDownLatch(3)

        fun track(name: String, work: () -> Unit) {
            Thread({
                try {
                    work()
                } catch (error: Throwable) {
                    if (!closed.get()) failures += error
                } finally {
                    tracks.countDown()
                }
            }, name).apply {
                isDaemon = true
                start()
            }
        }

        track("legacy-network-prep", { endpoint = network.prepare() })
        track("legacy-session-prep", { parts = prepareSession(btMac) })
        track("legacy-hfp-disconnect", { disconnectHfp(phone) })

        while (true) {
            // Fail fast: keep waiting only while every track is still healthy.
            failures.firstOrNull()?.let { throw it }
            try {
                if (tracks.await(500, TimeUnit.MILLISECONDS)) break
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedException()
            }
        }
        failures.firstOrNull()?.let { throw it }
        val readyEndpoint = endpoint ?: throw IllegalStateException("Network endpoint unavailable")
        val setup = parts ?: throw IllegalStateException("Session setup unavailable")

        val identification = Iap2IdentificationConfig(
            name = "CarPlay",
            modelIdentifier = "CarPlay",
            manufacturer = "CarPlay",
            serialNumber = setup.identity.pairingId,
            firmwareVersion = "0.21",
            hardwareVersion = "iMX6",
            wireless = Iap2WirelessIdentification(btMac, readyEndpoint.ssid),
        )
        val wirelessEndpoint = Iap2WirelessCarPlayEndpoint(
            ssid = readyEndpoint.ssid,
            passphrase = readyEndpoint.password,
            channel = readyEndpoint.channel,
            security = Iap2WirelessSecurity.WPA_WPA2,
            ipAddresses = listOf(readyEndpoint.address.hostAddress),
            airPlayPort = setup.config.port,
            deviceIdentifier = setup.deviceId,
            publicKey = setup.identity.publicKeyHex,
            sourceVersion = setup.config.sourceVersion,
        )

        setup.media.setIapTunnelHandler { stream: BlockingDuplexByteStream ->
            if (closed.get()) {
                false
            } else {
                log("Wi-Fi iAP2 tunnel request accepted")
                val tunnelThread = Thread({
                    try {
                        val session = Iap2Session.openTunnel(stream)
                        log("Wi-Fi iAP2 tunnel established")
                        Iap2WirelessControlClient(session, setup.mfiClient).run(
                            identification = identification,
                            endpoint = wirelessEndpoint,
                            onReady = { log("Wi-Fi iAP2 authenticated; control tunnel ready") },
                            onProgress = { log("Wi-Fi: $it") },
                        )
                    } catch (e: Throwable) {
                        if (!closed.get()) failure("Wi-Fi iAP2", e)
                    } finally {
                        runCatching { stream.close() }
                    }
                }, "legacy-iap-tunnel")
                tunnelThread.isDaemon = true
                tunnelThread.start()
                true
            }
        }

        val server = LegacyListenerBinder.bind(
            InetSocketAddress(readyEndpoint.address, setup.config.port),
            { closed.get() },
            { attempt -> if (attempt == 1) log("AirPlay port busy; waiting up to 5s") },
        )
        // The listener is deliberately NOT registered in "resources": the shutdown path must
        // never close it from another thread. On this head unit a ServerSocket closed by a
        // different thread can leave the LISTEN socket pinned in the kernel, and the next
        // bootstrap then dies with EADDRINUSE on bind. The accept thread owns the socket and
        // closes it in runAirPlayServer's finally block.
        Thread({ runAirPlayServer(server, setup.config, setup.identity, setup.pairings, setup.mfi, setup.media) }, "legacy-airplay-server").apply {
            isDaemon = true
            start()
        }

        val mcLock = wifi.createMulticastLock("legacy-carplay-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
        multicastLock = mcLock

        val dns = JmDNS.create(readyEndpoint.address, "DiPlay-Legacy")
        resources += dns
        dns.registerService(
            ServiceInfo.create(
                "_airplay._tcp.local.",
                setup.config.deviceName,
                setup.config.port,
                0,
                0,
                CarPlayBonjourProtocol.airPlayTxtRecords(setup.config, setup.identity),
            ),
        )
        dns.addServiceListener(
            "_carplay-ctrl._tcp.local.",
            controlServiceListener(readyEndpoint.address, setup.config, setup.deviceId),
        )

        log("listening on ${readyEndpoint.iface.name}; starting Bluetooth handshake")
        runBluetoothBootstrap(phone, setup.mfiClient, identification, wirelessEndpoint)
        log("Bluetooth bootstrap ended; waiting for Wi-Fi session")
    }

    /**
     * Everything the bootstrap builds that is independent of the live network endpoint: identity,
     * pairings, MFi client, AirPlay config and the media engine. Prepared on a parallel track
     * while the hotspot comes up.
     */
    private class SessionParts(
        val config: AirPlayConfig,
        val identity: AirPlayIdentity,
        val pairings: PairingStore,
        val mfi: LocalMfiAuthenticationClient,
        val mfiClient: Iap2MfiAuthenticationClient,
        val media: CarPlayMediaEngine,
        val deviceId: String,
    )

    /**
     * Builds the session parts on their own track. The wlan0 MAC is static in the firmware, so
     * the device id can be resolved before the hotspot is up; the nil-interface fallback in
     * [deviceId] keeps the stored id. MFi asset installation moved here from the host so it
     * overlaps the hotspot bring-up instead of preceding it.
     */
    private fun prepareSession(btMac: String): SessionParts {
        val identity = loadIdentity(context)
        val pairings = loadPairings(context)

        LegacyCarPlayHost.installMfi(context)
        val mfi = LocalMfiAuthenticationClient.load(LocalMfiAuthenticationClient.offlineDirectory(context))
        val mfiClient = Iap2MfiAuthenticationClient(mfi)

        val deviceId = deviceId(NetworkInterface.getByName("wlan0"))

        val config = AirPlayConfig(
            deviceName = "CarPlay",
            deviceId = deviceId,
            btMac = btMac,
            sourceVersion = SOURCE_VERSION,
            main = AirPlayDisplayConfig(
                widthPixels = displayWidth,
                heightPixels = displayHeight,
                // 30 fps over a weak 2.4 GHz link leaves twice the bitrate budget per frame,
                // so fast-changing scenes stay sharp instead of being crushed by iPhone rate
                // control. The settings screen can raise it to 60.
                fps = LegacySettings.fps(context),
                primaryInputDevice = 1,
            ),
            cluster = null,
            rightHandDrive = LegacySettings.rightHandDrive(context),
            port = 7100,
            hevc = LegacySettings.hevc(context),
            // This head unit routes CarPlay audio over its native Bluetooth A2DP service
            // (system_server BlueZ), not through a CarPlay audio stream. Advertising CarPlay
            // audio output here made the iPhone negotiate mainBuffered but still send music
            // over A2DP, and the mismatch reset the AirPlay session ~8 s after a play/pause
            // tap. Declaring no audio output lets the iPhone use A2DP cleanly, so no CarPlay
            // audio renderer is created to conflict with it.
            mainBufferedAudio = false,
            disableAudioOutput = true,
            microphone = false,
            microphoneOpus = false,
            manufacturer = "CarPlay",
            model = "CarPlay",
            oemLabel = "CarPlay",
            icons = emptyList(),
        )
        val media = CarPlayMediaEngine(sink = sink, microphoneEnabled = false, audioCaptureDirectory = null)
        return SessionParts(config, identity, pairings, mfi, mfiClient, media, deviceId)
    }

    /**
     * Disconnects the OEM Hands-Free Profile from the iPhone before opening the CarPlay RFCOMM
     * channel. The head unit's HFP profile holds its own RFCOMM connection, so BlueZ rejects a
     * second RFCOMM connect with EBUSY. Uses the public BluetoothHeadset API, no root required.
     */
    private fun disconnectHfp(phone: BluetoothDevice) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val latch = CountDownLatch(1)
        var headset: BluetoothHeadset? = null
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) headset = proxy as BluetoothHeadset
                latch.countDown()
            }

            override fun onServiceDisconnected(profile: Int) {
                latch.countDown()
            }
        }
        try {
            if (!adapter.getProfileProxy(context, listener, BluetoothProfile.HEADSET)) {
                log("HFP proxy request rejected; skipping HFP disconnect")
                return
            }
            if (!latch.await(3, TimeUnit.SECONDS) || headset == null) {
                log("HFP proxy unavailable; skipping HFP disconnect")
                return
            }
            val connected = headset!!.connectedDevices
            if (connected.contains(phone)) {
                log("Disconnecting HFP from iPhone")
                // BluetoothHeadset.disconnect(BluetoothDevice) is @hide, so invoke it reflectively.
                BluetoothHeadset::class.java.getMethod("disconnect", BluetoothDevice::class.java)
                    .invoke(headset, phone)
                Thread.sleep(800)
                log("HFP disconnect requested")
            } else {
                log("HFP not connected to iPhone; nothing to disconnect")
            }
        } catch (error: Throwable) {
            log("HFP disconnect failed: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            headset?.let { runCatching { adapter.closeProfileProxy(BluetoothProfile.HEADSET, it) } }
        }
    }

    private fun runAirPlayServer(
        server: ServerSocket,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: LocalMfiAuthenticationClient,
        media: CarPlayMediaEngine,
    ) {
        // A bounded accept is what lets close() release the port. A plain accept() on API 17
        // stays parked in the kernel even after the ServerSocket fd is closed, and the parked
        // thread's pending accept keeps the LISTEN socket alive. The next start would then hit
        // EADDRINUSE on the AirPlay port. The timeout re-enters accept every 500 ms, so the
        // loop sees the closed flag and exits.
        server.soTimeout = 500
        try {
            while (!closed.get()) {
                val socket: Socket = try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    if (closed.get()) return
                    failure("AirPlay listen", e)
                    return
                }
                try {
                    log("received AirPlay TCP connection")
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    val session = AirPlaySession(socket, config, identity, pairings, mfi, sessionListener, media)
                    sessions += session
                    session.start()
                } catch (e: Throwable) {
                    runCatching { socket.close() }
                    if (closed.get()) return
                    failure("AirPlay session", e)
                }
            }
        } finally {
            // Close the listener on the accept thread itself. See the note at the bind site:
            // a close() from the shutdown thread leaves the LISTEN socket pinned in the kernel
            // on this head unit, so this thread owns the server socket end to end.
            runCatching { server.close() }
            log("AirPlay server listener closed")
        }
    }

    private fun runBluetoothBootstrap(
        phone: BluetoothDevice,
        mfiClient: Iap2MfiAuthenticationClient,
        identification: Iap2IdentificationConfig,
        endpoint: Iap2WirelessCarPlayEndpoint,
    ) {
        // This head unit kills a backgrounded process within seconds; when CarPlay was running
        // the Bluetooth service still holds the phone's RFCOMM channel and a fresh connect
        // fails with "Device or resource busy". One socket per attempt plus a short wait lets
        // that stale state clear (or an in-flight HFP disconnect finish) instead of failing
        // the whole start. Only the connect stage retries: a dropped established session is a
        // real failure, and the Wi-Fi tunnel keeps the session alive anyway.
        var attempt = 0
        var socket: BluetoothSocket? = null
        while (socket == null && !closed.get()) {
            attempt += 1
            val candidate = phone.createRfcommSocketToServiceRecord(UUID.fromString(RFCOMM_UUID))
            try {
                candidate.connect()
                socket = candidate
                log(if (attempt > 1) "Bluetooth RFCOMM connected on attempt $attempt" else "Bluetooth RFCOMM connected")
            } catch (e: IOException) {
                runCatching { candidate.close() }
                if (!closed.get() && attempt < RFCOMM_CONNECT_ATTEMPTS) {
                    log("Bluetooth RFCOMM attempt $attempt failed (${e.message}); retrying")
                    try {
                        Thread.sleep(RFCOMM_CONNECT_RETRY_DELAY_MS)
                    } catch (_: InterruptedException) {
                        return
                    }
                } else {
                    if (!closed.get()) failure("Bluetooth bootstrap", e)
                    return
                }
            }
        }
        val connected = socket ?: return
        try {
            val stream = BluetoothRfcommDuplexStream(connected) { log("rfcomm: $it") }
            val session = Iap2Session.openWireless(stream)
            Iap2WirelessControlClient(session, mfiClient).run(
                identification = identification,
                endpoint = endpoint,
                onReady = { log("Bluetooth bootstrap ready; handing off to Wi-Fi") },
                onProgress = { log("Bluetooth: $it") },
            )
        } catch (e: Throwable) {
            if (!closed.get()) failure("Bluetooth bootstrap", e)
        } finally {
            runCatching { connected.close() }
        }
    }

    private fun controlServiceListener(host: Inet4Address, config: AirPlayConfig, deviceId: String): ServiceListener =
        object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                if (closed.get()) return
                log("discovered iPhone control service; resolving address")
                event.dns.requestServiceInfo(event.type, event.name, true)
            }

            override fun serviceRemoved(event: ServiceEvent) {}

            override fun serviceResolved(event: ServiceEvent) {
                val peer = event.info.inet4Addresses?.firstOrNull() ?: return
                val port = event.info.port
                if (port !in 1..65535 || closed.get()) return
                log("iPhone control service IPv4 resolved")
                Thread({ probeControl(host, peer, port, config, deviceId) }, "legacy-control-discovery").apply {
                    isDaemon = true
                    start()
                }
            }
        }

    private fun probeControl(host: Inet4Address, peer: Inet4Address, port: Int, config: AirPlayConfig, deviceId: String) {
        try {
            val socket = Socket()
            try {
                socket.bind(InetSocketAddress(host, 0))
                socket.connect(InetSocketAddress(peer, port), 2000)
                socket.soTimeout = 2000
                val request = CarPlayBonjourProtocol.connectProbeRequest(peer.hostAddress, port, config.sourceVersion, deviceId)
                socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                socket.getOutputStream().flush()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                val firstLine = reader.readLine() ?: ""
                val status = Regex("^HTTP/[^ ]+ ([0-9]{3})").find(firstLine)?.groupValues?.get(1) ?: "unknown"
                log("discovered iPhone control service; HTTP status=$status")
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            if (!closed.get()) failure("iPhone control service probe", e)
        }
    }

    private fun deviceId(iface: NetworkInterface?): String {
        val hw = iface?.hardwareAddress
        if (hw != null && hw.size == 6) {
            return hw.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
        }
        val prefs = context.getSharedPreferences("receiver-identity", Context.MODE_PRIVATE)
        prefs.getString("deviceId", null)?.let { return it }
        val sb = StringBuilder("02:")
        val uuid = UUID.randomUUID().toString().replace("-", "").take(10)
        sb.append(uuid.chunked(2).joinToString(":"))
        val value = sb.toString()
        prefs.edit().putString("deviceId", value).commit()
        return value
    }

    private fun loadIdentity(context: Context): AirPlayIdentity {
        val prefs = context.getSharedPreferences("legacy_diplay", Context.MODE_PRIVATE)
        val pub = prefs.getString("identity_pub", null)
        val priv = prefs.getString("identity_priv", null)
        val id = prefs.getString("identity_id", null)
        if (pub != null && priv != null && id != null) {
            return AirPlayIdentity(base64(priv), base64(pub), id)
        }
        val generated = AirPlayIdentity.generate()
        prefs.edit()
            .putString("identity_pub", encode64(generated.publicKey))
            .putString("identity_priv", encode64(generated.privateKey))
            .putString("identity_id", generated.pairingId)
            .commit()
        return generated
    }

    private fun loadPairings(context: Context): PairingStore {
        val prefs = context.getSharedPreferences("receiver-pairings", Context.MODE_PRIVATE)
        val store = PairingStore { identifier, bytes ->
            prefs.edit().putString(identifier, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
        }
        for ((key, value) in prefs.all) {
            if (value is String) {
                runCatching { store.save(key, Base64.decode(value, Base64.NO_WRAP)) }
            }
        }
        return store
    }

    private fun failure(stage: String, error: Throwable) {
        val detail = error.javaClass.simpleName + (error.message?.let { ": $it" } ?: "")
        log("$stage failed: $detail")
        onConnectionFailure("$stage: $detail")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeSession = null
        sessions.forEach { runCatching { it.close() } }
        sessions.clear()
        multicastLock?.let { runCatching { if (it.isHeld) it.release() } }
        multicastLock = null
        wakeLocks.forEach { runCatching { if (it.isHeld) it.release() } }
        wakeLocks.clear()
        wifiLocks.forEach { runCatching { if (it.isHeld) it.release() } }
        wifiLocks.clear()
        resources.forEach { runCatching { it.close() } }
        resources.clear()
    }

    private fun encode64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun base64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}