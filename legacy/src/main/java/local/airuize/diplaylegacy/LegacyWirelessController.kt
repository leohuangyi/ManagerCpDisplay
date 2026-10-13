package local.airuize.diplaylegacy

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Base64
import android.util.Log
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
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
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
    private val onHostUi: () -> Unit = {},
    private val networkMode: String = "router",
) : Closeable {

    companion object {
        private const val TAG = "LegacyWireless"
        private const val RFCOMM_UUID = "00000000-deca-fade-deca-deafdecacafe"
        private const val SOURCE_VERSION = "950.7.1"
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
        }

        override fun onSessionEnded(session: AirPlaySession) {
            sessions.remove(session)
            if (activeSession === session) activeSession = null
            log("AirPlay session ended")
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

        val network = LegacyNetwork(context, networkMode, { closed.get() }, log).also { resources += it }
        val endpoint = network.prepare()

        val btMac = adapter.address ?: throw IllegalStateException("unable to read Bluetooth address")
        val deviceId = deviceId(endpoint.iface)

        val identity = loadIdentity(context)
        val pairings = loadPairings(context)

        LegacyCarPlayHost.installMfi(context)
        val mfi = LocalMfiAuthenticationClient.load(LocalMfiAuthenticationClient.offlineDirectory(context))
        val mfiClient = Iap2MfiAuthenticationClient(mfi)

        val config = AirPlayConfig(
            deviceName = "CarPlay",
            deviceId = deviceId,
            btMac = btMac,
            sourceVersion = SOURCE_VERSION,
            main = AirPlayDisplayConfig(
                widthPixels = displayWidth,
                heightPixels = displayHeight,
                fps = 30,
                primaryInputDevice = 1,
            ),
            cluster = null,
            rightHandDrive = false,
            port = 7100,
            hevc = false,
            disableAudioOutput = false,
            microphone = false,
            microphoneOpus = false,
            manufacturer = "CarPlay",
            model = "CarPlay",
            oemLabel = "CarPlay",
            icons = emptyList(),
        )
        val media = CarPlayMediaEngine(sink = sink, microphoneEnabled = false, audioCaptureDirectory = null)

        val identification = Iap2IdentificationConfig(
            name = "CarPlay",
            modelIdentifier = "CarPlay",
            manufacturer = "CarPlay",
            serialNumber = identity.pairingId,
            firmwareVersion = "0.21",
            hardwareVersion = "iMX6",
            wireless = Iap2WirelessIdentification(btMac, endpoint.ssid),
        )
        val wirelessEndpoint = Iap2WirelessCarPlayEndpoint(
            ssid = endpoint.ssid,
            passphrase = endpoint.password,
            channel = endpoint.channel,
            security = Iap2WirelessSecurity.WPA_WPA2,
            ipAddresses = listOf(endpoint.address.hostAddress),
            airPlayPort = config.port,
            deviceIdentifier = deviceId,
            publicKey = identity.publicKeyHex,
            sourceVersion = config.sourceVersion,
        )

        media.setIapTunnelHandler { stream: BlockingDuplexByteStream ->
            if (closed.get()) {
                false
            } else {
                log("Wi-Fi iAP2 tunnel request accepted")
                val tunnelThread = Thread({
                    try {
                        val session = Iap2Session.openTunnel(stream)
                        log("Wi-Fi iAP2 tunnel established")
                        Iap2WirelessControlClient(session, mfiClient).run(
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
            InetSocketAddress(endpoint.address, config.port),
            { closed.get() },
            { attempt -> if (attempt == 1) log("AirPlay port busy; waiting up to 5s") },
        )
        resources += server
        Thread({ runAirPlayServer(server, config, identity, pairings, mfi, media) }, "legacy-airplay-server").apply {
            isDaemon = true
            start()
        }

        val mcLock = wifi.createMulticastLock("legacy-carplay-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
        multicastLock = mcLock

        val dns = JmDNS.create(endpoint.address, "DiPlay-Legacy")
        resources += dns
        dns.registerService(
            ServiceInfo.create(
                "_airplay._tcp.local.",
                config.deviceName,
                config.port,
                0,
                0,
                CarPlayBonjourProtocol.airPlayTxtRecords(config, identity),
            ),
        )
        dns.addServiceListener(
            "_carplay-ctrl._tcp.local.",
            controlServiceListener(endpoint.address, config, deviceId),
        )

        log("listening on ${endpoint.iface.name}; starting Bluetooth handshake")
        runBluetoothBootstrap(phone, mfiClient, identification, wirelessEndpoint)
        log("Bluetooth bootstrap ended; waiting for Wi-Fi session")
    }

    private fun runAirPlayServer(
        server: ServerSocket,
        config: AirPlayConfig,
        identity: AirPlayIdentity,
        pairings: PairingStore,
        mfi: LocalMfiAuthenticationClient,
        media: CarPlayMediaEngine,
    ) {
        while (!closed.get()) {
            val socket: Socket = try {
                server.accept()
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
    }

    private fun runBluetoothBootstrap(
        phone: BluetoothDevice,
        mfiClient: Iap2MfiAuthenticationClient,
        identification: Iap2IdentificationConfig,
        endpoint: Iap2WirelessCarPlayEndpoint,
    ) {
        val socket = phone.createRfcommSocketToServiceRecord(UUID.fromString(RFCOMM_UUID))
        try {
            socket.connect()
            log("Bluetooth RFCOMM connected")
            val stream = BluetoothRfcommDuplexStream(socket) { log("rfcomm: $it") }
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
            runCatching { socket.close() }
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

    private fun deviceId(iface: NetworkInterface): String {
        val hw = iface.hardwareAddress
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