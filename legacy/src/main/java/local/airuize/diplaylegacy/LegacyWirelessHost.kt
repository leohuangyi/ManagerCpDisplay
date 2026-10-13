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
    ) {
        if (controller != null) return
        LegacyCarPlayHost.installMfi(context)
        videoWidth = align(width)
        videoHeight = align(height)
        val appContext = context.applicationContext
        val nextSink = AndroidMediaSink(
            surface = surface,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            context = appContext,
        )
        sink = nextSink
        val next = LegacyWirelessController(
            context = appContext,
            sink = nextSink,
            displayWidth = videoWidth,
            displayHeight = videoHeight,
            log = log,
            onConnectionFailure = { reason -> log("wireless connection failure: $reason") },
        )
        controller = next
        val phone = findPhone() ?: run {
            log("no paired iPhone found; pair an iPhone over Bluetooth first")
            return
        }
        log("starting wireless CarPlay with ${phone.name ?: phone.address}")
        next.start(phone)
    }

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

    private fun findPhone(): BluetoothDevice? {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        if (!adapter.isEnabled) return null
        val bonded = adapter.bondedDevices ?: return null
        return bonded.firstOrNull()
    }

    private fun align(value: Int): Int = (value / 2 * 2).coerceAtLeast(2)
}