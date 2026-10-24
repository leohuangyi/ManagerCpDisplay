package com.shilapi.mgxcertplay

import android.content.Context
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.io.File
import java.security.MessageDigest

/**
 * Non-Compose wiring for the API 17 port. Copies the offline MFi identity into the controller's
 * directory, builds the CarPlay stack with [MfiTarget.LOCAL], and forwards touch to the iPhone.
 */
object LegacyCarPlayHost {
    private const val TAG = "LegacyCarPlayHost"
    private const val SCREEN_TYPE_MAIN = 110

    @Volatile private var controller: CarPlayController? = null
    @Volatile private var sink: AndroidMediaSink? = null
    @Volatile private var videoWidth = 1280
    @Volatile private var videoHeight = 720

    private val listener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) { Log.i(TAG, "CarPlay session active") }
        override fun onSessionEnded(session: AirPlaySession) { Log.i(TAG, "CarPlay session ended") }
        override fun onTransportError(message: String) { Log.w(TAG, "transport error: $message") }
        override fun onDebugLog(message: String) { Log.i(TAG, "debug: $message") }
    }

    /** Copies assets/offline-mfi into the controller's directory once, safe on API 17. */
    @JvmStatic
    fun installMfi(context: Context) {
        val target = LocalMfiAuthenticationClient.offlineDirectory(context)
        if (target.isDirectory) return
        val staging = File(target.parentFile, "offline-mfi-staging")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "could not prepare local MFi staging" }
        try {
            for (name in arrayOf("identity.pk8", "certificate.p7b")) {
                context.assets.open("offline-mfi/$name").use { input ->
                    File(staging, name).outputStream().use { output -> input.copyTo(output) }
                }
            }
            LocalMfiAuthenticationClient.load(staging)
            check(staging.renameTo(target)) { "could not install local MFi identity" }
        } finally {
            staging.deleteRecursively()
        }
    }

    @JvmStatic
    fun start(context: Context, surface: Surface, width: Int, height: Int) {
        if (controller != null) return
        installMfi(context)
        videoWidth = align(width)
        videoHeight = align(height)
        val identity = loadIdentity(context)
        val id = deviceId(identity)
        val hostMac = id.split(":").map { it.toInt(16).toByte() }.toByteArray()
        val appContext = context.applicationContext

        val config = CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.LOCAL,
            identification = Iap2IdentificationConfig(
                name = "CarPlay",
                modelIdentifier = "CarPlay",
                manufacturer = "CarPlay",
                serialNumber = identity.pairingId,
                firmwareVersion = "0.21",
                hardwareVersion = "iMX6",
                carPlayUsbInterfaceNumber = 3,
            ),
            label = "CarPlay",
            hostName = "carplay-" + id.replace(":", "").lowercase(),
            hostMac = hostMac,
            transport = CarPlayTransport.WIRED,
        )
        val airPlayConfig = AirPlayConfig(
            deviceName = "CarPlay",
            deviceId = id,
            btMac = id,
            sourceVersion = "320.17.3",
            main = AirPlayDisplayConfig(
                widthPixels = videoWidth,
                heightPixels = videoHeight,
                fps = 30,
                primaryInputDevice = 1,
            ),
            manufacturer = "CarPlay",
            model = "CarPlay",
            hevc = false,
            microphone = false,
        )
        val nextSink = AndroidMediaSink(
            surface = surface,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            context = appContext,
        )
        sink = nextSink
        val media = CarPlayMediaEngine(sink = nextSink, microphoneEnabled = false, audioCaptureDirectory = null)
        val next = CarPlayController(
            context = appContext,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = identity,
            pairings = PairingStore(null),
            listener = listener,
            media = media,
            reportStatus = { status -> Log.i(TAG, "status: $status") },
        )
        controller = next
        next.start()
    }

    /** Forwards a [MotionEvent] already in the surface's coordinate space. */
    @JvmStatic
    fun sendTouch(event: MotionEvent): Boolean {
        val next = controller ?: return false
        return next.sendTouch(CarPlayTouchMapper.contacts(event, videoWidth, videoHeight))
    }

    @JvmStatic
    fun shutdown() {
        val current = controller
        controller = null
        sink = null
        try {
            current?.close()
        } catch (_: Throwable) {
        }
    }

    private fun align(value: Int): Int = (value / 2 * 2).coerceAtLeast(2)

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
            .apply()
        return generated
    }

    /** First 6 bytes of the SHA-256 of the accessory public key, with the multicast/local bit set. */
    private fun deviceId(identity: AirPlayIdentity): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).copyOfRange(0, 6)
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }

    private fun encode64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun base64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}