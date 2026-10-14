package local.airuize.diplaylegacy

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper

/**
 * API 17 entry point for the wireless CarPlay stack. Builds the media sink and [LegacyWirelessController]
 * and forwards touch. The wired host ([LegacyCarPlayHost]) is left intact.
 */
object LegacyWirelessHost {
    private const val TAG = "LegacyWirelessHost"

    /** CarPlay's main screen stream, the only screen in the legacy config. */
    private const val SCREEN_TYPE_MAIN = 110

    /** Loading-screen steps, in the order the driver sees them. */
    private const val STAGE_NETWORK = 1
    private const val STAGE_IPHONE = 2
    private const val STAGE_CARPLAY = 3

    @Volatile private var controller: LegacyWirelessController? = null
    @Volatile private var sink: AndroidMediaSink? = null
    @Volatile private var videoWidth = 1280
    @Volatile private var videoHeight = 720

    @JvmStatic
    @JvmOverloads
    fun start(
        context: Context,
        surface: Surface,
        width: Int,
        height: Int,
        log: (String) -> Unit = { Log.i(TAG, it) },
        /** Short user-facing progress label, called from background threads. */
        onStatus: (String) -> Unit = {},
        /** Progress checks for the loading screen: 1 = hotspot ready, 2 = iPhone, 3 = CarPlay. */
        onStage: (Int) -> Unit = {},
        /** The CarPlay screen stream became active: the picture is arriving. */
        onVideo: () -> Unit = {},
    ) {
        if (controller != null) return
        // MFi setup moved inside the controller's session track so it overlaps the
        // hotspot bring-up on cold start instead of running before it.
        videoWidth = align(width)
        videoHeight = align(height)
        val appContext = context.applicationContext
        val nextSink = AndroidMediaSink(
            surface = surface,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            context = appContext,
            // No-op on non-MTK codecs; on MTK it drops decode-to-display latency and display reorder.
            mtkDecoderTuningEnabled = true,
        )
        // CarPlay's main screen stream is type 110 (CarPlayMediaEngine.STREAM_TYPE_MAIN_SCREEN,
        // the only screen in the legacy config): once it is active the host screen can drop its
        // loading overlay. The sink replays already-active streams when the listener registers.
        var videoReported = false
        nextSink.setScreenStreamActiveChangedListener { _, active ->
            if (active && !videoReported) {
                videoReported = true
                onVideo()
            }
        }
        sink = nextSink
        val next = LegacyWirelessController(
            context = appContext,
            sink = nextSink,
            displayWidth = videoWidth,
            displayHeight = videoHeight,
            log = { line ->
                log(line)
                statusFor(line)?.let(onStatus)
                stageFor(line)?.let(onStage)
            },
            onConnectionFailure = { reason ->
                log("wireless connection failure: $reason")
                onStatus("Connection failed: $reason")
            },
            networkMode = "auto",
        )
        controller = next
        val phone = findPhone() ?: run {
            log("no paired iPhone found; pair an iPhone over Bluetooth first")
            onStatus("No paired iPhone found\nPair an iPhone over Bluetooth first")
            return
        }
        log("starting wireless CarPlay with ${phone.name ?: phone.address}")
        next.start(phone)
    }

    /** A short progress label for the milestone log lines the controller emits. */
    private fun statusFor(line: String): String? = when {
        "Network mode" in line -> "Wi-Fi hotspot ready"
        "listening on" in line -> "Waiting for the iPhone to connect"
        "Bluetooth RFCOMM connected" in line -> "iPhone connected"
        "Wi-Fi iAP2 authenticated" in line -> "CarPlay session ready"
        "AirPlay session active" in line -> "Starting the screen…"
        "AirPlay session ended" in line -> "Waiting for the iPhone to reconnect"
        else -> null
    }

    /** Loading-screen steps: hotspot ready, iPhone on RFCOMM, CarPlay session up. */
    private fun stageFor(line: String): Int? = when {
        "Network mode" in line -> STAGE_NETWORK
        "Bluetooth RFCOMM connected" in line -> STAGE_IPHONE
        "Wi-Fi iAP2 authenticated" in line -> STAGE_CARPLAY
        "AirPlay session active" in line -> STAGE_CARPLAY
        else -> null
    }

    @JvmStatic
    fun sendTouch(event: MotionEvent): Boolean {
        val next = controller ?: return false
        return next.sendTouch(CarPlayTouchMapper.contacts(event, videoWidth, videoHeight))
    }

    /**
     * Reattaches the live CarPlay session to a recreated SurfaceView surface after the activity
     * came back from the background. The main stream's next frames render onto it, so the
     * picture returns within a frame or two of the key frame.
     */
    @JvmStatic
    fun attachSurface(surface: Surface) {
        sink?.setSurface(SCREEN_TYPE_MAIN, surface)
        // A still CarPlay screen sends no frames; ask for one instead of showing black.
        sink?.refreshPicture(SCREEN_TYPE_MAIN)
    }

    /**
     * SurfaceHolder contract: nothing may render to [surface] after this returns. The main
     * decoder is released rather than parked: the park path uses ImageReader, which this
     * API 17 head unit does not have. The stream's next frame starts a fresh decoder, and
     * [attachSurface] binds it to the recreated surface when the activity comes back.
     */
    @JvmStatic
    fun detachSurface(surface: Surface): Boolean {
        val next = sink ?: return true
        return next.beginSurfaceDetach(surface, parkMain = false).await()
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

    private fun findPhone(): BluetoothDevice? {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        if (!adapter.isEnabled) return null
        val bonded = adapter.bondedDevices ?: return null
        return bonded.firstOrNull()
    }

    private fun align(value: Int): Int = (value / 2 * 2).coerceAtLeast(2)
}