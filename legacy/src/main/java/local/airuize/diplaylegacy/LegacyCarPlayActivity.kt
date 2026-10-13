package local.airuize.diplaylegacy

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Minimal API 17 host screen. Holds the CarPlay video SurfaceView, shows a branded loading
 * screen with live progress, and fades it out once the CarPlay screen stream becomes active.
 */
class LegacyCarPlayActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        private const val TAG = "LegacyCarPlayActivity"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val dotFrames = arrayOf("", ".", "..", "...")
    private var dotFrame = 0
    private var splash: View? = null
    private var status: TextView? = null
    private var dots: TextView? = null
    private var started = false
    private var splashHidden = false
    private var destroyed = false

    // -- lifecycle -------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        startDots()
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        LegacyWirelessHost.shutdown()
        started = false
        super.onDestroy()
    }

    // -- ui --------------------------------------------------------------------------

    private fun buildUi(): FrameLayout {
        val density = resources.displayMetrics.density
        val pad = (24f * density).toInt()

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        val surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER

        val title = TextView(this).apply {
            text = "DiPlay"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(pad, 0, pad, 0)
        }
        content.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val subtitle = TextView(this).apply {
            text = "Wireless CarPlay"
            setTextColor(0xFF9E9E9E.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
        }
        content.addView(subtitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, 0)
        }
        content.addView(statusView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        status = statusView

        val dotsView = TextView(this).apply {
            setTextColor(0xFF9E9E9E.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
        }
        val dotsParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dotsParams.topMargin = pad / 2
        content.addView(dotsView, dotsParams)
        dots = dotsView

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        splash = overlay

        return root
    }

    /** A tiny animated liveness hint under the status line, so a long phone handshake reads as progress. */
    private fun startDots() {
        mainHandler.post(object : Runnable {
            override fun run() {
                if (destroyed || splashHidden) return
                dots?.text = dotFrames[dotFrame]
                dotFrame = (dotFrame + 1) % dotFrames.size
                mainHandler.postDelayed(this, 400L)
            }
        })
    }

    /** Status updates arrive from the host's background threads; all UI work stays on the main thread. */
    private fun setStatus(text: String) {
        if (destroyed) return
        runOnUiThread { status?.text = text }
    }

    private fun hideSplash() {
        if (destroyed || splashHidden) return
        splashHidden = true
        runOnUiThread {
            mainHandler.removeCallbacksAndMessages(null)
            val view = splash ?: return@runOnUiThread
            view.animate().alpha(0f).setDuration(500)
                .withEndAction { view.visibility = View.GONE }
        }
    }

    private fun showFailure(error: Throwable) {
        splashHidden = true
        val detail = StringBuilder(error.toString())
        var cause: Throwable? = error.cause
        while (cause != null) {
            detail.append("\n").append(cause.toString())
            cause = cause.cause
        }
        Log.e(TAG, "carplay start failed", error)
        runOnUiThread {
            mainHandler.removeCallbacksAndMessages(null)
            status?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            status?.setTextColor(0xFFFF8A80.toInt())
            status?.text = "Start failed: $detail"
            dots?.visibility = View.INVISIBLE
        }
    }

    // -- host handshake ----------------------------------------------------------------

    private fun startHost(holder: SurfaceHolder) {
        if (started) return
        val frame = holder.surfaceFrame
        val width = frame.width()
        val height = frame.height()
        if (width <= 0 || height <= 0) return
        started = true
        val surface: Surface = holder.surface
        setStatus("Preparing the connection…")
        // The host installs MFI assets and builds the media sink up front, which takes a moment.
        // Run it off the main thread so the loading screen keeps animating.
        Thread({
            try {
                LegacyWirelessHost.start(
                    context = this,
                    surface = surface,
                    width = width,
                    height = height,
                    log = { line -> Log.i(TAG, line) },
                    onStatus = { text -> setStatus(text) },
                    onVideo = { hideSplash() },
                )
            } catch (error: Throwable) {
                showFailure(error)
            }
        }, "legacy-host-start").apply {
            isDaemon = true
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (started && LegacyWirelessHost.sendTouch(event)) {
            return true
        }
        return super.onTouchEvent(event)
    }

    // -- SurfaceHolder.Callback ----------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "surface created")
        startHost(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.i(TAG, "surface changed: ${width}x$height")
        startHost(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "surface destroyed")
    }
}