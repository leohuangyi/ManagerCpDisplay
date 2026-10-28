package com.shilapi.mgxcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
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
import com.shilapi.xcertplay.transport.BluetoothControlSocketOwner
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint
import com.shilapi.xcertplay.transport.Iap2WirelessControlClient
import com.shilapi.xcertplay.transport.Iap2WirelessControlResult
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import com.shilapi.xcertplay.transport.WirelessBootstrapRecoveryPolicy
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.Inet6Address
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
import java.util.concurrent.atomic.AtomicInteger
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
    private val networkMode: String = "auto",
) : Closeable {

    companion object {
        private const val TAG = "LegacyWireless"
        private const val RFCOMM_UUID = "00000000-deca-fade-deca-deafdecacafe"
        private const val ACCESSORY_RFCOMM_UUID = "00000000-deca-fade-deca-deafdecacaff"
        private const val CARPLAY_SERVICE_UUID = "ec884348-cd41-40a2-9727-575d50bf1fd3"
        private const val SOURCE_VERSION = "320.17.3"
        // The stale RFCOMM channel of a just-closed bootstrap can take several seconds to
        // clear in this head unit's Bluetooth service, so give the connect a wide window.
        private const val RFCOMM_CONNECT_ATTEMPTS = 6
        private const val RFCOMM_CONNECT_RETRY_DELAY_MS = 2000L
        // EBUSY ("Device or resource busy") means the OEM wired CarPlay stack still holds a
        // live RFCOMM/L2CAP link; wait for BlueZ to tear it down before reconnecting.
        private const val RFCOMM_BUSY_RETRY_DELAY_MS = 3000L
        // "Service discovery failed" means the iPhone has not yet switched to wireless iAP2
        // mode (it is still leaving its wired session); give the state machine longer to settle.
        private const val RFCOMM_SDP_RETRY_DELAY_MS = 4000L
        // Settle time after disconnecting HFP/A2DP before opening the CarPlay RFCOMM channel.
        private const val BLUETOOTH_PROFILE_SETTLE_MS = 2500L
        // HF/client profiles are discovered from the running framework; HEADSET is the AG
        // role and cannot stand in for a missing hands-free client API.
        private val PROFILE_HFP = LegacyBluetoothProfiles.Role.HANDS_FREE
        private val PROFILE_A2DP = LegacyBluetoothProfiles.Role.A2DP
        // BluetoothA2dp.PRIORITY_AUTO_CONNECT (hidden): set so the native stack also auto-reconnects.
        private const val A2DP_PRIORITY_AUTO_CONNECT = 1000
        // Rapid re-opens can leave the previous profile's RFCOMM/L2CAP channel half-torn-down, so a
        // single connect() can land in STATE_CONNECTING and fall back instead of reaching connected.
        // Retry each profile a few times and poll for the connected state before giving up.
        private const val PROFILE_CONNECT_RETRIES = 3
        private const val PROFILE_CONNECT_ATTEMPT_TIMEOUT_MS = 4_000L
        private const val PROFILE_CONNECT_POLL_MS = 250L
        // CarPlay hands audio off by sending "disableBluetooth" right after the AirPlay session
        // goes active. Only HFP is re-linked here: A2DP is left to the system because its profile 2
        // proxy misreports connectedDevices (it said "not connected" while BluetoothA2dpService was
        // still streaming), so a disconnect would be a no-op and the follow-up connect doomed. The
        // switch has no completion ack, so a fixed settle window is used; if the connect still fails,
        // the system's own background connectSource re-links it later (~30s) as a fallback.
        private const val MEDIA_SOURCE_SWITCH_SETTLE_MS = 10_000L
        private const val MEDIA_SOURCE_DISPATCH_TIMEOUT_MS = 5_000
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
        /** How long [close] waits for the bootstrap thread and its preparation tracks to exit. */
        private const val BOOTSTRAP_EXIT_WAIT_MS = 5_000L
        private const val TRACK_EXIT_WAIT_MS = 2_000L
    }

    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val bluetoothControlLock = Any()
    private val bluetoothServicePreparationLock = Any()
    private var bluetoothServicesPrepared = false
    private val bluetoothServices = CopyOnWriteArrayList<Closeable>()
    private val bluetoothAcceptThreads = CopyOnWriteArrayList<Thread>()
    private val bluetoothSocketOwner = BluetoothControlSocketOwner<ControlSocket> { it.close() }
    /** Published before connect(), so close() also cancels a socket that has no stream yet. */
    @Volatile private var connectingRfcommSocket: ControlSocket? = null
    /** The live RFCOMM control stream/session of the Bluetooth bootstrap, closed by [closeRfcomm]. */
    @Volatile private var rfcommStream: BluetoothRfcommDuplexStream? = null
    @Volatile private var rfcommSession: Iap2Session? = null
    /** The iPhone this controller bootstrapped against, used to tear down Bluetooth profiles on exit. */
    @Volatile private var phone: BluetoothDevice? = null
    /** Guards reconnectBluetoothProfiles so it only re-links the profiles once per session. */
    private val bluetoothProfilesReconnected = AtomicBoolean(false)
    private val resources = CopyOnWriteArrayList<Closeable>()
    @Volatile private var bootstrapThread: Thread? = null
    private val trackThreads = CopyOnWriteArrayList<Thread>()

    /** The network handle of the running bootstrap, kept for the post-CarPlay hotspot restore. */
    @Volatile private var network: LegacyNetwork? = null
    private val wakeLocks = CopyOnWriteArrayList<PowerManager.WakeLock>()
    private val wifiLocks = CopyOnWriteArrayList<WifiManager.WifiLock>()
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    private val sessions = CopyOnWriteArrayList<AirPlaySession>()
    private val acceptingAirPlay = AtomicInteger(0)
    private val tunnelStreams = CopyOnWriteArrayList<BlockingDuplexByteStream>()
    @Volatile private var activeSession: AirPlaySession? = null

    /**
     * The OEM Bluetooth native implementation must see only one close, including races between
     * connect cancellation, stream-construction failure, reader failure, and controller shutdown.
     */
    private class ControlSocket(val socket: BluetoothSocket) : Closeable {
        private val closeStarted = AtomicBoolean(false)
        val remoteDevice: BluetoothDevice get() = socket.remoteDevice
        fun connect() = socket.connect()
        override fun close() {
            if (closeStarted.compareAndSet(false, true)) socket.close()
        }
    }

    private val sessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            activeSession = session
            log("AirPlay session active; waiting for video")
            // Dispatch and await native-source capability on a background thread. AirPlay's
            // control thread must keep processing SETUP/commands while the assistant answers.
            scheduleBluetoothProfileReconnectAfterMediaSourceSwitch(session)
        }

        override fun onSessionEnded(session: AirPlaySession) {
            sessions.remove(session)
            if (activeSession === session) activeSession = null
            log("AirPlay session ended")
            // Startup rollback and an explicit close can end a partially accepted
            // session. They must finish cleanup before the host starts another link.
            if (!closed.get()) onSessionEnded()
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
            // The iPhone sends "disableBluetooth" when it takes over audio from the CarPlay
            // handshake. The HFP re-link now runs after the media source switch instead, so
            // here we only record the handoff for diagnostics.
            if (type.equals("disableBluetooth", ignoreCase = true) || type.equals("disable-bluetooth", ignoreCase = true)) {
                log("iPhone sent $type (audio handoff); HFP re-link runs after media source switch")
            }
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
        synchronized(bluetoothControlLock) {
            if (closed.get() || !started.compareAndSet(false, true)) return
            this.phone = phone
            bluetoothSocketOwner.selectPeer(phone.address)
            Thread({ bootstrap(phone) }, "legacy-carplay-start").apply {
                isDaemon = true
                bootstrapThread = this
                start()
            }
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
                // Failed discovery can already own locks and partially prepared listeners.
                // Roll them back now, even if the driver leaves the failure screen open.
                runCatching { close() }.onFailure { Log.w(TAG, "bootstrap cleanup failed", it) }
                failure("bootstrap", e)
            }
        }
    }

    private fun runBootstrap(phone: BluetoothDevice) {
        this.phone = phone
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
        // iAP2 over Bluetooth must identify with the same name as the radio's EIR Local Name.
        // AirPlay Info still uses its protocol-required "CarPlay" name independently.
        val btName = adapter.name?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("unable to read Bluetooth local name")

        // A cold start used to run its stages back to back; now only the hotspot bring-up (the
        // long pole) and MFi/media session setup run, in parallel and meeting at a latch. HFP is
        // no longer disconnected up front: the RFCOMM bootstrap clears it only if its own connect
        // hits EBUSY. A failure on any track aborts the bootstrap; the latch gives the join the
        // visibility a plain volatile read could not guarantee on every JVM.
        val network = LegacyNetwork(context, networkMode, { closed.get() }, log)
            .also { this.network = it; resources += it }
        var endpoint: LegacyNetwork.Endpoint? = null
        var parts: SessionParts? = null
        val failures = CopyOnWriteArrayList<Throwable>()
        val tracks = CountDownLatch(2)

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
                trackThreads += this
                start()
            }
        }

        track("legacy-network-prep", { endpoint = network.prepare() })
        track("legacy-session-prep", { parts = prepareSession(btMac) })

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
        val prepared = parts ?: throw IllegalStateException("Session setup unavailable")
        // The AP interface can differ between ROMs. Resolve one identity from the actual
        // prepared endpoint, then use it consistently for /info, Bonjour and 0x4301.
        val endpointDeviceId = deviceId(readyEndpoint.iface)
        val setup = SessionParts(
            prepared.config.copy(deviceId = endpointDeviceId), prepared.identity, prepared.pairings,
            prepared.mfi, prepared.mfiClient, prepared.media, endpointDeviceId,
        )

        val identification = Iap2IdentificationConfig(
            name = btName,
            modelIdentifier = "CarPlay",
            manufacturer = "CarPlay",
            serialNumber = setup.identity.pairingId,
            firmwareVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: SOURCE_VERSION,
            hardwareVersion = Build.HARDWARE.takeIf { it.isNotBlank() }
                ?: Build.DEVICE.takeIf { it.isNotBlank() } ?: "Android",
            wireless = Iap2WirelessIdentification(
                btMac,
                readyEndpoint.ssid,
                // Only advertise implemented standard transport capabilities on this receiver.
                includeExtendedTransportParameters = false,
                includeLegacyConnectionMessages = false,
            ),
        )
        val wirelessEndpoint = Iap2WirelessCarPlayEndpoint(
            ssid = readyEndpoint.ssid,
            passphrase = readyEndpoint.password,
            channel = readyEndpoint.channel,
            security = Iap2WirelessSecurity.WPA_WPA2,
            // Wireless CarPlay's IPAddress field names the Wi-Fi IPv6 link-local endpoint.
            // Keep IPv4 listeners for compatibility, and only advertise IPv4 as a fallback.
            ipAddresses = readyEndpoint.addresses.filterIsInstance<Inet6Address>()
                .ifEmpty { readyEndpoint.addresses }
                .map { it.hostAddress!!.substringBefore('%') },
            airPlayPort = setup.config.port,
            deviceIdentifier = setup.deviceId,
            // 0x4301 calls this field PublicKey, but it carries the pairing identity (Bonjour pi).
            publicKey = setup.identity.pairingId,
            sourceVersion = setup.config.sourceVersion,
        )

        setup.media.setIapTunnelHandler { stream: BlockingDuplexByteStream ->
            val accepted = synchronized(bluetoothControlLock) {
                if (closed.get()) false else {
                    tunnelStreams += stream
                    true
                }
            }
            if (!accepted) {
                false
            } else {
                log("Wi-Fi iAP2 tunnel request accepted")
                val tunnelThread = Thread({
                    var tunnelSession: Iap2Session? = null
                    try {
                        val session = Iap2Session.openTunnel(stream).also { tunnelSession = it }
                        log("Wi-Fi iAP2 tunnel established")
                        val result = Iap2WirelessControlClient(session, setup.mfiClient).run(
                            identification = identification,
                            endpoint = wirelessEndpoint,
                            onReady = { log("Wi-Fi iAP2 authenticated; control tunnel ready") },
                            keepAliveAfterDeadline = { !closed.get() && activeSession != null },
                            onProgress = { log("Wi-Fi: $it") },
                        )
                        logControlResult("Wi-Fi", result)
                    } catch (e: Throwable) {
                        if (!closed.get()) {
                            if (hasAirPlayConnection()) {
                                log("Wi-Fi iAP2 control ended during AirPlay; preserving session: ${e.message}")
                            } else {
                                failure("Wi-Fi iAP2", e)
                            }
                        }
                    } finally {
                        tunnelStreams.remove(stream)
                        runCatching { tunnelSession?.close() }
                        runCatching { stream.close() }
                    }
                }, "legacy-iap-tunnel")
                tunnelThread.isDaemon = true
                tunnelThread.start()
                true
            }
        }

        val mcLock = wifi.createMulticastLock("legacy-carplay-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
        multicastLock = mcLock

        // Open discovery before the TCP listeners: a busy UDP 5353 must not leave a
        // half-started AirPlay server. Publish its services only after TCP is listening.
        // JmDNS uses one wildcard UDP socket on this runtime, so keep one IPv4 registry;
        // the AirPlay listeners and 0x4301 still provide IPv6 reachability.
        val dns = JmDNS.create(readyEndpoint.address, "DiPlay-Legacy")
        resources += dns

        // Bind one listener per advertised address family so the iPhone's AirPlay SYN on
        // 7100 is accepted whether it dials the IPv4 address or the scoped link-local IPv6.
        // Same LAN topology binds one listener per family; phone-hotspot mode does the same.
        // Each listener is deliberately NOT registered in "resources": the shutdown path must
        // never close it from another thread. On this head unit a ServerSocket closed by a
        // different thread can leave the LISTEN socket pinned in the kernel, and the next
        // bootstrap then dies with EADDRINUSE on bind. The accept thread owns the socket and
        // closes it in runAirPlayServer's finally block.
        for (bindAddress in readyEndpoint.addresses) {
            val server = LegacyListenerBinder.bind(
                InetSocketAddress(bindAddress, setup.config.port),
                { closed.get() },
                { attempt -> if (attempt == 1) log("AirPlay port busy; waiting up to 5s") },
            )
            Thread({ runAirPlayServer(server, setup.config, setup.identity, setup.pairings, setup.mfi, setup.media) }, "legacy-airplay-server").apply {
                isDaemon = true
                start()
            }
        }

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

        prepareBluetoothServices()
        log("listening on ${readyEndpoint.iface.name}; starting Bluetooth handshake")
        runBluetoothBootstrap(phone, setup.mfiClient, identification, wirelessEndpoint)
        // Outbound connection retries remain bounded. A later authenticated phone-initiated
        // connection is a new event and can still use the prepared Wi-Fi/iAP2 engine.
        while (!closed.get() && !hasWirelessHandoff()) {
            if (!bluetoothSocketOwner.awaitIncoming()) return
            if (closed.get() || hasWirelessHandoff()) return
            val result = runBluetoothControlAttempt(
                phone, setup.mfiClient, identification, wirelessEndpoint,
                recovering = true, incomingOnly = true,
            )
            if (result != null) logControlResult("Bluetooth incoming", result)
        }
    }

    /**
     * Publish the accessory iAP2/CarPlay services before the host waits for its first pairing.
     * Accepted sockets wait for phone selection and network preparation; only the bootstrap
     * worker may consume them and run identification/authentication.
     */
    fun prepareBluetoothServices() = synchronized(bluetoothServicePreparationLock) {
        if (bluetoothServicesPrepared || closed.get()) return@synchronized
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw IllegalStateException("Android Bluetooth is unavailable")
        check(adapter.isEnabled) { "please enable Bluetooth first" }
        val name = adapter.name?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("unable to read Bluetooth local name")
        val added = ArrayList<Closeable>()
        try {
            for ((label, uuid) in listOf(
                "iAP2 accessory (caff)" to ACCESSORY_RFCOMM_UUID,
                "CarPlay (ec88)" to CARPLAY_SERVICE_UUID,
            )) {
                if (closed.get()) return@synchronized
                val server = adapter.listenUsingRfcommWithServiceRecord(name, UUID.fromString(uuid))
                // The OEM API 17 socket does not implement Closeable. Wrap it and guard close
                // because preparation failure and controller shutdown may race each other.
                val serverClosed = AtomicBoolean(false)
                val resource = Closeable {
                    if (serverClosed.compareAndSet(false, true)) server.close()
                }
                val retained = synchronized(bluetoothControlLock) {
                    if (closed.get()) false else {
                        bluetoothServices += resource
                        added += resource
                        true
                    }
                }
                if (!retained) {
                    runCatching { resource.close() }
                    return@synchronized
                }
                Thread({ acceptBluetoothConnections(server, resource, label) }, "legacy-bt-accept-$label").apply {
                    isDaemon = true
                    bluetoothAcceptThreads += this
                    start()
                }
                log("Bluetooth SDP/EIR service $label registered; accepting connections")
            }
            bluetoothServicesPrepared = true
        } catch (error: Throwable) {
            added.forEach {
                bluetoothServices.remove(it)
                runCatching { it.close() }
            }
            throw error
        }
    }

    private fun acceptBluetoothConnections(server: BluetoothServerSocket, resource: Closeable, label: String) {
        try {
            while (!closed.get()) {
                val socket = try {
                    ControlSocket(server.accept())
                } catch (error: IOException) {
                    if (!closed.get()) log("Bluetooth service $label accept ended: ${error.javaClass.simpleName}")
                    return
                }
                val accepted = try {
                    val peer = socket.remoteDevice
                    if (closed.get() || peer.bondState != BluetoothDevice.BOND_BONDED || hasWirelessHandoff()) {
                        runCatching { socket.close() }
                        false
                    } else {
                        bluetoothSocketOwner.offerIncoming(peer.address, socket)
                    }
                } catch (error: Exception) {
                    runCatching { socket.close() }
                    if (!closed.get()) log("Bluetooth incoming peer check failed: ${error.javaClass.simpleName}")
                    false
                }
                if (closed.get()) return
                log(if (accepted) "Bluetooth incoming $label queued for control" else
                    "Bluetooth incoming $label rejected: unselected, unpaired, duplicate, or Wi-Fi active")
            }
        } finally {
            // Never leave an advertised RFCOMM channel without an accept worker.
            runCatching { resource.close() }
            bluetoothServices.remove(resource)
        }
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
     * Builds the session parts on their own track. The network-independent identity below is
     * provisional; bootstrap replaces it with the prepared AP interface identity before any
     * listener advertises it. MFi asset installation moved here from the host so it
     * overlaps the hotspot bring-up instead of preceding it.
     */
    private fun prepareSession(btMac: String): SessionParts {
        val identity = loadIdentity(context)
        val pairings = loadPairings(context)

        LegacyCarPlayHost.installMfi(context)
        val mfi = LocalMfiAuthenticationClient.load(LocalMfiAuthenticationClient.offlineDirectory(context))
        val mfiClient = Iap2MfiAuthenticationClient(mfi)

        val deviceId = deviceId(null)

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

    /** Disconnects [phone] from one Bluetooth profile via the public (@hide) disconnect method. */
    private fun disconnectProfile(
        phone: BluetoothDevice,
        profile: LegacyBluetoothProfiles.Role,
        label: String,
        stillNeeded: () -> Boolean = { !closed.get() },
    ) {
        LegacyBluetoothProfiles.withProfile(context, profile, phone, log) { handle ->
            if (!stillNeeded()) return@withProfile
            val p = handle.proxy
            if (p.connectedDevices.contains(phone)) {
                log("Disconnecting $label from iPhone")
                val accepted = handle.disconnect(phone)
                log("$label disconnect requested accepted=$accepted profile=${handle.id}")
            } else {
                log("$label not connected to iPhone; nothing to disconnect")
            }
        }
    }

    /** Connects [phone] to one Bluetooth profile via the OEM @hide connect path, retrying on failure.
     *  Returns true once the profile reached the connected state, false when it gave up. */
    private fun connectProfile(
        phone: BluetoothDevice,
        profile: LegacyBluetoothProfiles.Role,
        label: String,
        stillNeeded: () -> Boolean = { !closed.get() },
    ): Boolean =
        LegacyBluetoothProfiles.withProfile(context, profile, phone, log) { handle ->
            if (!stillNeeded()) return@withProfile false
            val p = handle.proxy
            if (p.connectedDevices.contains(phone)) {
                log("$label already connected to iPhone; nothing to connect")
                return@withProfile true
            }
            // The hands-free API owns its discovered connect signature; A2DP uses the standard
            // profile and its optional priority method. No numeric HF profile or car-model table.
            repeat(PROFILE_CONNECT_RETRIES) { attempt ->
                if (!stillNeeded()) return@withProfile false
                try {
                    if (profile == PROFILE_A2DP) {
                        // Keep the native stack's normal background auto-connect policy.
                        runCatching {
                            p.javaClass.getMethod(
                                "setPriority", BluetoothDevice::class.java, Int::class.javaPrimitiveType
                            ).invoke(p, phone, A2DP_PRIORITY_AUTO_CONNECT)
                        }.onSuccess {
                            log("$label priority set to AUTO_CONNECT($A2DP_PRIORITY_AUTO_CONNECT)")
                        }.onFailure { error ->
                            log("$label setPriority failed: ${error.javaClass.simpleName}: ${error.message}")
                        }
                    }
                    val accepted = handle.connect(phone)
                    log("$label connect requested accepted=$accepted profile=${handle.id} " +
                        "(attempt ${attempt + 1}/$PROFILE_CONNECT_RETRIES)")

                    // Poll for the connected state instead of trusting the fire-and-forget connect():
                    // the rapid-reopen path can drop a fresh connect from STATE_CONNECTING straight
                    // back to disconnected, and only a re-issue once things settle recovers it.
                    val deadline = System.currentTimeMillis() + PROFILE_CONNECT_ATTEMPT_TIMEOUT_MS
                    while (System.currentTimeMillis() < deadline && stillNeeded()) {
                        if (p.connectedDevices.contains(phone)) {
                            log("$label connected")
                            return@withProfile true
                        }
                        try {
                            Thread.sleep(PROFILE_CONNECT_POLL_MS)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@withProfile false
                        }
                    }
                    if (!closed.get()) log("$label not connected after attempt ${attempt + 1}")
                } catch (error: Throwable) {
                    log("$label connect failed (attempt ${attempt + 1}): ${error.javaClass.simpleName}: ${error.message}")
                }
            }
            log("$label connect gave up after $PROFILE_CONNECT_RETRIES attempts")
            false
        } ?: false

    /**
     * Re-links only HFP after dispatching the native Bluetooth audio source. A2DP is intentionally
     * left untouched: on this head unit the profile 2 proxy misreports connectedDevices (it said
     * "not connected" while BluetoothA2dpService was still streaming), so a disconnect would be a
     * no-op and the follow-up connect doomed. Only a discovered HF/client profile is re-linked.
     */
    private fun reconnectBluetoothProfiles(session: AirPlaySession) {
        val phone = this.phone ?: return
        if (!bluetoothProfilesReconnected.compareAndSet(false, true)) {
            log("Native Bluetooth profiles already queued; skipping re-link")
            return
        }
        log("Queueing native HFP re-link (disconnect then reconnect)")
        Thread({
            if (closed.get() || activeSession !== session) return@Thread
            disconnectProfile(phone, PROFILE_HFP, "HFP") { !closed.get() && activeSession === session }
            try {
                Thread.sleep(BLUETOOTH_PROFILE_SETTLE_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@Thread
            }
            if (closed.get() || activeSession !== session) return@Thread
            Thread({
                if (!closed.get() && activeSession === session) {
                    connectProfile(phone, PROFILE_HFP, "HFP") { !closed.get() && activeSession === session }
                }
            }, "legacy-profile-hfp").apply {
                isDaemon = true
                start()
            }
        }, "legacy-profile-reconnect").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Re-links native HFP only after the assistant confirms it dispatched the discovered native
     * Bluetooth audio source. This is not a source-applied ack, so the existing settle window
     * remains. Missing capabilities, stale sessions and shutdown leave HFP untouched. If
     * [reconnectBluetoothProfiles] already ran this becomes a no-op.
     */
    private fun scheduleBluetoothProfileReconnectAfterMediaSourceSwitch(session: AirPlaySession) {
        Thread({
            if (closed.get() || activeSession !== session) return@Thread
            val result = LocalAdb(context).use {
                it.requestSourceSwitch(MEDIA_SOURCE_DISPATCH_TIMEOUT_MS)
            }
            if (closed.get() || activeSession !== session) return@Thread
            if (result?.exit != 0 || !result?.output.orEmpty().startsWith("dispatched;sourceId=")) {
                log("Native Bluetooth source dispatch unconfirmed; leaving HFP connected")
                return@Thread
            }
            log("Native Bluetooth source dispatched; settling before HFP re-link")
            try {
                Thread.sleep(MEDIA_SOURCE_SWITCH_SETTLE_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@Thread
            }
            if (closed.get() || activeSession !== session) return@Thread
            reconnectBluetoothProfiles(session)
        }, "legacy-profile-reconnect-after-source").apply {
            isDaemon = true
            trackThreads += this
            start()
        }
    }

    /**
     * Connects A2DP if the CarPlay iPhone is not already on it. The host calls this once the
     * CarPlay screen stream carries its first frame (画面出现), by which point the RFCOMM/iAP2
     * bootstrap has fully handed off to Wi-Fi, so touching A2DP can no longer collide with it.
     * [connectProfile] is idempotent: it logs "already connected" and returns without issuing a
     * connect when A2DP is already up, so this is safe to call even if the system already
     * re-linked A2DP in the background.
     */
    fun ensureA2dpConnected() {
        val phone = this.phone ?: return
        if (closed.get()) return
        Thread({
            if (closed.get()) return@Thread
            connectProfile(phone, PROFILE_A2DP, "A2DP")
        }, "legacy-a2dp-ensure").apply {
            isDaemon = true
            start()
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
                acceptingAirPlay.incrementAndGet()
                try {
                    if (closed.get()) {
                        socket.close()
                        return
                    }
                    log("received AirPlay TCP connection")
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    val session = synchronized(bluetoothControlLock) {
                        if (closed.get()) null else {
                            AirPlaySession(socket, config, identity, pairings, mfi, sessionListener, media)
                                .also { sessions += it }
                        }
                    }
                    if (session == null) {
                        socket.close()
                        return
                    }
                    session.start()
                } catch (e: Throwable) {
                    runCatching { socket.close() }
                    if (closed.get()) return
                    failure("AirPlay session", e)
                } finally {
                    acceptingAirPlay.decrementAndGet()
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
        for (controlAttempt in 1..WirelessBootstrapRecoveryPolicy.MAX_ATTEMPTS) {
            if (closed.get()) return
            if (controlAttempt > 1 && hasWirelessHandoff()) {
                log("Bluetooth retry cancelled; Wi-Fi handoff already in progress")
                return
            }
            log("Bluetooth control attempt $controlAttempt/${WirelessBootstrapRecoveryPolicy.MAX_ATTEMPTS}")
            val result = runBluetoothControlAttempt(
                phone, mfiClient, identification, endpoint, recovering = controlAttempt > 1,
            ) ?: return
            logControlResult("Bluetooth attempt $controlAttempt", result)
            val decision = WirelessBootstrapRecoveryPolicy.afterControlEnded(
                completedAttempts = controlAttempt,
                closed = closed.get(),
                airPlayConnected = hasAirPlayConnection(),
                wifiTunnelConnected = tunnelStreams.isNotEmpty(),
            )
            when (decision.action) {
                WirelessBootstrapRecoveryPolicy.Action.CLOSED -> return
                WirelessBootstrapRecoveryPolicy.Action.HANDOFF -> {
                    log("Bluetooth control ended during Wi-Fi handoff; preserving AirPlay and listeners")
                    return
                }
                WirelessBootstrapRecoveryPolicy.Action.EXHAUSTED -> {
                    // Failure reporting updates the UI; the network and listeners stay available
                    // if the iPhone finishes joining after the final bounded attempt.
                    if (!closed.get() && !hasWirelessHandoff()) {
                        failure("Bluetooth bootstrap", IOException(
                            "${result.terminal} at ${result.stage} after $controlAttempt attempts; " +
                                "iPhone has not opened a Wi-Fi CarPlay connection",
                        ))
                    }
                    return
                }
                WirelessBootstrapRecoveryPolicy.Action.RETRY -> {
                    log("Bluetooth ${result.terminal} at ${result.stage}; " +
                        "retrying control in ${decision.retryDelayMillis}ms with hotspot and listeners preserved")
                    if (!waitForBluetoothRetry(decision.retryDelayMillis)) return
                }
            }
        }
    }

    private fun hasAirPlayConnection(): Boolean =
        activeSession != null || acceptingAirPlay.get() > 0 || sessions.isNotEmpty()

    private fun hasWirelessHandoff(): Boolean = hasAirPlayConnection() || tunnelStreams.isNotEmpty()

    /** Interruptible backoff also notices a Wi-Fi handoff without opening another RFCOMM link. */
    private fun waitForBluetoothRetry(delayMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis)
        while (!closed.get()) {
            if (hasWirelessHandoff()) {
                log("Bluetooth retry cancelled; Wi-Fi handoff arrived during backoff")
                return false
            }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return true
            try {
                Thread.sleep(minOf(250L, maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining))))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun logControlResult(transport: String, result: Iap2WirelessControlResult) {
        log("$transport control ended: terminal=${result.terminal} stage=${result.stage} " +
            "wifiConfigs=${result.wifiConfigurationsSent} startSessions=${result.carPlayStartSessionsSent} " +
            "transportNotified=${result.transportNotificationSeen} " +
            "postTransportConfigs=${result.postTransportWiFiConfigurationsSent} " +
            "airPlayConnected=${hasAirPlayConnection()} wifiTunnels=${tunnelStreams.size}")
    }

    private fun runBluetoothControlAttempt(
        phone: BluetoothDevice,
        mfiClient: Iap2MfiAuthenticationClient,
        identification: Iap2IdentificationConfig,
        endpoint: Iap2WirelessCarPlayEndpoint,
        recovering: Boolean,
        incomingOnly: Boolean = false,
    ): Iap2WirelessControlResult? {
        // This head unit kills a backgrounded process within seconds; when CarPlay was running
        // the Bluetooth service still holds the phone's RFCOMM channel and a fresh connect
        // fails with "Device or resource busy". One socket per attempt plus a short wait lets
        // that stale state clear. HFP is not disconnected up front anymore: only if a connect
        // fails with EBUSY do we clear the HFP RFCOMM once, then retry. Only the connect stage
        // retries here. The outer loop separately handles an ended iAP2 control result.
        var attempt = 0
        var hfpClearedOnBusy = false
        var connected: ControlSocket? = null
        var stream: BluetoothRfcommDuplexStream? = null
        var controlSession: Iap2Session? = null
        try {
            while (connected == null && !closed.get()) {
                if (recovering && hasWirelessHandoff()) {
                    log("Bluetooth retry cancelled; Wi-Fi handoff already in progress")
                    return null
                }
                val incoming = bluetoothSocketOwner.takeIncoming()
                if (incoming != null) {
                    connected = incoming
                    val peer = incoming.remoteDevice
                    // A bond can disappear while an early incoming socket waits for the AP.
                    if (peer.bondState != BluetoothDevice.BOND_BONDED ||
                        !peer.address.equals(phone.address, ignoreCase = true)) {
                        runCatching { incoming.close() }
                        bluetoothSocketOwner.release(incoming)
                        connected = null
                        continue
                    }
                    log("Bluetooth RFCOMM connected (phone initiated)")
                    break
                }
                if (incomingOnly) return null
                attempt += 1
                val candidate = ControlSocket(phone.createRfcommSocketToServiceRecord(UUID.fromString(RFCOMM_UUID)))
                // An incoming socket may have arrived between takeIncoming and this creation.
                // It wins before any second control handshake can start.
                if (!bluetoothSocketOwner.beginOutgoing(phone.address, candidate)) {
                    if (closed.get()) return null
                    continue
                }
                synchronized(bluetoothControlLock) {
                    if (closed.get()) {
                        runCatching { candidate.close() }
                        return null
                    }
                    connectingRfcommSocket = candidate
                }
                try {
                    candidate.connect()
                    if (!bluetoothSocketOwner.outgoingConnected(candidate)) {
                        if (closed.get()) return null
                        continue
                    }
                    connected = candidate
                    if (closed.get() || (recovering && hasWirelessHandoff())) {
                        // Exit or Wi-Fi handoff won while the retry was connecting. This is
                        // still this attempt's socket; do not start another iAP2 handshake.
                        runCatching { candidate.close() }
                        return null
                    }
                    log(if (attempt > 1) "Bluetooth RFCOMM connected on attempt $attempt" else "Bluetooth RFCOMM connected")
                } catch (e: IOException) {
                    bluetoothSocketOwner.outgoingFailed(candidate)
                    runCatching { candidate.close() }
                    if (closed.get() || (recovering && hasWirelessHandoff())) return null
                    if (bluetoothSocketOwner.hasIncoming()) {
                        log("Bluetooth incoming connection replaced pending outbound connect")
                        continue
                    }
                    val message = e.message ?: ""
                    // EBUSY and SDP failure need different back-offs: EBUSY means a stale OEM
                    // RFCOMM/L2CAP link is still clearing, SDP failure means the iPhone has not
                    // entered wireless iAP2 mode yet. Both deserve a longer wait than a generic
                    // connect error rather than a blind fixed retry.
                    val busy = message.contains("busy", ignoreCase = true)
                    val delay = when {
                        busy -> RFCOMM_BUSY_RETRY_DELAY_MS
                        message.contains("Service discovery", ignoreCase = true) -> RFCOMM_SDP_RETRY_DELAY_MS
                        else -> RFCOMM_CONNECT_RETRY_DELAY_MS
                    }
                    // Only the initial bootstrap may clear HFP. Recovery already completed a
                    // Bluetooth handshake once; touching profiles now could disturb a Wi-Fi
                    // handoff that arrives while the profile proxy is being obtained.
                    if (busy && !recovering && !hfpClearedOnBusy) {
                        hfpClearedOnBusy = true
                        log("Bluetooth RFCOMM busy; clearing HFP channel before retry")
                        disconnectProfile(phone, PROFILE_HFP, "HFP")
                    }
                    if (!closed.get() && attempt < RFCOMM_CONNECT_ATTEMPTS) {
                        log("Bluetooth RFCOMM attempt $attempt failed ($message); retrying in ${delay}ms")
                        try {
                            Thread.sleep(delay)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return null
                        }
                    } else {
                        if (!closed.get()) failure("Bluetooth bootstrap", e)
                        return null
                    }
                } finally {
                    if (connected !== candidate) {
                        bluetoothSocketOwner.outgoingFailed(candidate)
                        runCatching { candidate.close() }
                    }
                    synchronized(bluetoothControlLock) {
                        if (connectingRfcommSocket === candidate && connected !== candidate) {
                            connectingRfcommSocket = null
                        }
                    }
                }
            }
            val socket = connected ?: return null
            if (closed.get() || (recovering && hasWirelessHandoff())) return null
            // Publish the stream before the session so a close() racing this window reaches
            // the socket through the stream, not through a half-built session.
            val rfcomm = BluetoothRfcommDuplexStream(
                socket.socket, { log("rfcomm: $it") }, { socket.close() },
            )
            stream = rfcomm
            synchronized(bluetoothControlLock) {
                if (closed.get()) return null
                rfcommStream = rfcomm
                connectingRfcommSocket = null
            }
            val session = Iap2Session.openWireless(rfcomm).also { controlSession = it }
            synchronized(bluetoothControlLock) {
                if (closed.get() || (recovering && hasWirelessHandoff())) {
                    runCatching { session.close() }
                    return null
                }
                rfcommSession = session
            }
            return Iap2WirelessControlClient(session, mfiClient).run(
                identification = identification,
                endpoint = endpoint,
                onReady = {
                    log("Bluetooth bootstrap ready; handing off to Wi-Fi")
                },
                keepAliveAfterDeadline = { !closed.get() && activeSession != null },
                onProgress = { log("Bluetooth: $it") },
            )
        } catch (e: Throwable) {
            if (!closed.get()) {
                if (hasWirelessHandoff()) {
                    log("Bluetooth control ended during Wi-Fi handoff; preserving session: ${e.message}")
                } else {
                    failure("Bluetooth bootstrap", e)
                }
            }
            return null
        } finally {
            // Detach before closing so a later run starts with a fresh channel; the stream owns
            // the socket and closes it idempotently. For a socket that never made it into a
            // stream (exit raced the hand-over), this thread still owns it and closes it directly.
            synchronized(bluetoothControlLock) {
                rfcommStream = null
                rfcommSession = null
                connectingRfcommSocket = null
            }
            runCatching { controlSession?.close() }
            if (stream != null) {
                runCatching { stream.close() }
            } else {
                runCatching { connected?.close() }
            }
            connected?.let { bluetoothSocketOwner.release(it) }
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
        synchronized(bluetoothControlLock) {
            if (!closed.compareAndSet(false, true)) return
        }
        // Stop accept and passive incoming waits before joining the bootstrap worker.
        bluetoothServices.forEach { runCatching { it.close() } }
        bluetoothServices.clear()
        bluetoothSocketOwner.close()
        // The bootstrap and its preparation tracks keep building and using the resources
        // below while they run. Give them first crack at stopping: interrupt so the
        // blocking stages (su round-trips, Bluetooth connects, latch waits) unwind
        // promptly, then wait a bounded time for each to exit. Without this, a quick
        // "start then exit" let the shutdown thread tear the network/hotspot down while
        // a preparation track was still using it, and the concurrent teardown crashed
        // the process natively (deadbaad invalid heap address in dlfree).
        val closingThread = Thread.currentThread()
        bootstrapThread?.takeUnless { it === closingThread }?.interrupt()
        trackThreads.filter { it !== closingThread }.forEach { it.interrupt() }
        // The interrupt misses the bootstrap's blocking RFCOMM recv (the stream re-asserts
        // the interrupt flag and keeps waiting), so close the session and stream to break
        // that recv and free the RFCOMM channel before the joins below.
        closeRfcomm()
        tunnelStreams.forEach { runCatching { it.close() } }
        bootstrapThread?.let { joinBounded(it, BOOTSTRAP_EXIT_WAIT_MS) }
        trackThreads.forEach { joinBounded(it, TRACK_EXIT_WAIT_MS) }
        bluetoothAcceptThreads.forEach { joinBounded(it, TRACK_EXIT_WAIT_MS) }
        bluetoothAcceptThreads.clear()
        // Defensive cleanup after the worker exits; all new publications check closed under
        // bluetoothControlLock, including the connecting socket before it has a stream.
        closeRfcomm()
        tunnelStreams.clear()
        // Graceful goodbye first: tell the iPhone (changeModes borrow + local stream teardown)
        // that this CarPlay session is ending before the RTSP socket is closed abruptly. This
        // lets it stop the screen stream itself and exit its CarPlay state machine cleanly, so it
        // can accept the next wired or wireless session right away instead of only noticing the
        // socket drop later.
        sessions.forEach { runCatching { it.endSession() } }
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
        // Leave A2DP/HFP alone on exit: disconnecting them here drops the iPhone from the OEM
        // Bluetooth stack entirely, forcing the driver back into the native Bluetooth screen to
        // re-link. The RFCOMM bootstrap channel is already closed above, and any stale HFP RFCOMM
        // is cleared on-demand by the RFCOMM EBUSY retry on the next connect instead.
    }

    /**
     * Closes the live Bluetooth control session and its RFCOMM stream. The session close
     * cascades channel -> link -> stream, which shuts the socket and wakes the reader; the
     * stream close afterwards is the idempotent fallback for a session that exists without a
     * published stream. Both are safe to call from any thread.
     */
    private fun closeRfcomm() {
        val (session, stream, connecting) = synchronized(bluetoothControlLock) {
            Triple(rfcommSession, rfcommStream, connectingRfcommSocket).also {
                rfcommSession = null
                rfcommStream = null
                connectingRfcommSocket = null
            }
        }
        runCatching { session?.close() }
        runCatching { stream?.close() }
        runCatching { connecting?.close() }
    }

    /** Waits for [thread] to exit, restoring this thread's interrupt flag if the wait is cut short. */
    private fun joinBounded(thread: Thread, timeoutMillis: Long) {
        if (thread === Thread.currentThread()) return
        try {
            thread.join(timeoutMillis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Restores the head unit's hotspot and network access after a CarPlay session ended and
     * the controller is being torn down. Only meaningful in `auto` network mode and only when
     * this controller brought the hotspot up itself; otherwise it is a no-op.
     */
    fun restoreHotspotAfterCarPlay() {
        network?.restoreAfterCarPlay()
    }

    private fun encode64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun base64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}
