package local.airuize.diplaylegacy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Minimal API 17 host screen. Holds the CarPlay video SurfaceView, shows a branded loading
 * screen whose check list follows the real start milestones, and fades it out once the
 * CarPlay screen stream becomes active.
 */
class LegacyCarPlayActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        private const val TAG = "LegacyCarPlayActivity"

        /** Loading-screen steps; stage N means steps 1..N are checked off. */
        private const val STAGE_HOTSPOT = 1
        private const val STAGE_IPHONE = 2
        private const val STAGE_CARPLAY = 3
        private const val STAGE_SCREEN = 4

        private const val COLOR_PENDING = 0xFF546E7A.toInt()
        private const val COLOR_LABEL = 0xFF90A4AE.toInt()
        private const val COLOR_FADED = 0xFF78909C.toInt()
        private const val COLOR_ACTIVE = 0xFFECEFF1.toInt()
        private const val COLOR_DONE = 0xFF81C784.toInt()
        private const val COLOR_FAILED = 0xFFEF5350.toInt()
    }

    /** One row of the loading check list: a state glyph area plus its label. */
    private class StepRow(
        val container: LinearLayout,
        val glyph: TextView,
        val spinner: ProgressBar,
        val label: TextView,
    ) {
        companion object {
            const val PENDING = 0
            const val ACTIVE = 1
            const val DONE = 2
            const val FAILED = 3
        }
    }

    /** The Bluetooth rune, drawn in code because no API 17 font glyph is guaranteed. */
    private class BluetoothRuneView(context: Context) : View(context) {
        private val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = Color.WHITE
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val stemLeft = 0.22f * w
            canvas.drawRect(stemLeft, 0.12f * h, stemLeft + 0.10f * w, 0.88f * h, paint)
            canvas.drawPath(Path().apply {
                moveTo(stemLeft, 0.18f * h)
                lineTo(0.82f * w, 0.34f * h)
                lineTo(stemLeft, 0.50f * h)
                close()
            }, paint)
            canvas.drawPath(Path().apply {
                moveTo(stemLeft, 0.50f * h)
                lineTo(0.82f * w, 0.66f * h)
                lineTo(stemLeft, 0.82f * h)
                close()
            }, paint)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var splash: View? = null
    private var status: TextView? = null
    /** The branded card (icon, title, check list, status) inside the splash overlay. */
    private var loadingCard: View? = null
    /** The pairing hint shown when the head unit has no paired iPhone yet. */
    private var waitingView: View? = null
    private var waitingShown = false
    /** The start prompt shown when auto-connect is off and no session has been started. */
    private var connectPrompt: View? = null
    private var promptShown = false
    private var manualStart = false
    private var pendingHolder: SurfaceHolder? = null
    private val rows = arrayOfNulls<StepRow>(STAGE_SCREEN)
    private var stage = 0
    private var started = false
    private var splashHidden = false
    private var destroyed = false
    /** The surface currently bound to the live session; reattach only when it is replaced. */
    private var boundSurface: Surface? = null
    /** Monotonic clock when the activity was created; logged when the first frame arrives. */
    private var coldStartAt = 0L

    // -- lifecycle -------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coldStartAt = SystemClock.elapsedRealtime()
        setContentView(buildUi())
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        LegacyWirelessHost.shutdown()
        started = false
        boundSurface = null
        super.onDestroy()
    }

    override fun onPause() {
        // The surface is torn down right after this: release the CarPlay video streams the
        // same way a TEARDOWN does, so the iPhone stops pushing frames into a decoder whose
        // surface is about to disappear. The control session stays alive.
        if (started) LegacyWirelessHost.suspendVideo()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Back from the background: rebind the live session to the surface so the iPhone's
        // re-established video stream renders again. A recreated surface attaches through
        // surfaceCreated (boundSurface is then already replaced); this covers a resume where
        // Android kept the surface alive.
        if (started) boundSurface?.let { LegacyWirelessHost.attachSurface(it) }
    }

    // -- ui --------------------------------------------------------------------------

    private fun buildUi(): FrameLayout {
        val density = resources.displayMetrics.density
        fun dp(value: Float): Int = (value * density).toInt()
        val pad = dp(24f)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        val surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Branded loading card: icon and title on top, then the check list that follows the
        // host milestones, then a two-line status area until the first frame replaces it.
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER

        fun centered(top: Int, bottom: Int = 0): LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = top
                bottomMargin = bottom
            }

        val icon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
        }
        content.addView(icon, centered(0).apply {
            width = dp(84f)
            height = dp(84f)
        })

        val title = TextView(this).apply {
            text = "DiPlay"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        content.addView(title, centered(dp(12f)))

        val subtitle = TextView(this).apply {
            text = "Wireless CarPlay"
            setTextColor(COLOR_LABEL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }
        content.addView(subtitle, centered(dp(4f)))

        val stepLabels = arrayOf(
            "Start the Wi-Fi hotspot",
            "Connect to iPhone",
            "Start the CarPlay session",
            "Show the screen",
        )
        val steps = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        for (index in stepLabels.indices) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val glyph = TextView(this).apply {
                text = "\u25cb" // ○: this step has not started yet
                setTextColor(COLOR_PENDING)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
            }
            val spinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
                visibility = View.INVISIBLE
            }
            val glyphArea = FrameLayout(this).apply {
                addView(glyph, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(spinner, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER))
            }
            val label = TextView(this).apply {
                text = stepLabels[index]
                setTextColor(if (index == 0) COLOR_ACTIVE else COLOR_FADED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            row.addView(glyphArea, LinearLayout.LayoutParams(dp(36f), dp(28f)))
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            steps.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30f)))
            rows[index] = StepRow(row, glyph, spinner, label)
        }
        content.addView(steps, centered(dp(22f)).apply {
            leftMargin = pad
            rightMargin = pad
        })

        val statusView = TextView(this).apply {
            text = "Preparing the connection…"
            setTextColor(COLOR_LABEL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            minLines = 2
            setPadding(pad, 0, pad, 0)
        }
        content.addView(statusView, centered(dp(16f)))
        status = statusView
        loadingCard = content

        // Pairing-hint card for when the head unit has no paired iPhone. It replaces the
        // check list until the pairing shows up (the host polls in the background), and
        // carries a shortcut to the Bluetooth settings where the pairing happens.
        val waiting = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        val badge = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF1565C0.toInt())
            }
        }
        badge.addView(
            BluetoothRuneView(this),
            FrameLayout.LayoutParams(dp(44f), dp(44f), Gravity.CENTER),
        )
        waiting.addView(badge, centered(0).apply {
            width = dp(104f)
            height = dp(104f)
        })

        waiting.addView(TextView(this).apply {
            text = "等待连接"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, centered(dp(24f)))

        waiting.addView(TextView(this).apply {
            text = "没有发现已配对的 iPhone"
            setTextColor(COLOR_ACTIVE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        }, centered(dp(10f)))

        waiting.addView(TextView(this).apply {
            text = "请开启车机蓝牙，并在蓝牙设置中\n与 iPhone 完成配对，配对成功后将自动连接"
            setTextColor(COLOR_LABEL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }, centered(dp(12f)))

        waiting.addView(TextView(this).apply {
            text = "打开蓝牙设置"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(36f), 0, dp(36f), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(22f).toFloat()
                setColor(0xFF1565C0.toInt())
            }
            setOnClickListener {
                runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f)
        ).apply {
            topMargin = dp(24f)
        })

        val finderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        finderRow.addView(
            ProgressBar(this, null, android.R.attr.progressBarStyleSmall),
            LinearLayout.LayoutParams(dp(22f), dp(22f)).apply {
                rightMargin = dp(8f)
            },
        )
        finderRow.addView(TextView(this).apply {
            text = "正在查找已配对的设备…"
            setTextColor(COLOR_FADED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        })
        waiting.addView(finderRow, centered(dp(20f)))
        waitingView = waiting

        // Start prompt for auto-connect off: replaces the check list until the driver
        // taps the connect button. Same look as the pairing hint.
        val prompt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        prompt.addView(TextView(this).apply {
            text = "尚未连接"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, centered(dp(0f)))
        prompt.addView(TextView(this).apply {
            text = "自动连接已关闭"
            setTextColor(COLOR_ACTIVE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        }, centered(dp(12f)))
        prompt.addView(TextView(this).apply {
            text = "点击下方按钮开始连接 iPhone"
            setTextColor(COLOR_LABEL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }, centered(dp(8f)))
        prompt.addView(TextView(this).apply {
            text = "开始连接"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(36f), 0, dp(36f), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(22f).toFloat()
                setColor(0xFF1565C0.toInt())
            }
            setOnClickListener { startManualConnect() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f)
        ).apply {
            topMargin = dp(24f)
        })
        connectPrompt = prompt

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(waiting, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(prompt, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        splash = overlay

        // The fleet hardware pauses a beat on cold start; without an entrance a silent
        // black frame reads as a hang. Fade the card in, pop the icon, and let each step
        // rise into place so the list builds up as the connection does.
        content.alpha = 0f
        content.animate().alpha(1f).setStartDelay(100).setDuration(600).start()
        icon.scaleX = 0.8f
        icon.scaleY = 0.8f
        icon.animate().scaleX(1f).scaleY(1f).setDuration(500).start()
        var delay = 200L
        for (row in rows) {
            val item = row ?: continue
            item.container.alpha = 0f
            item.container.translationY = dp(8f).toFloat()
            item.container.animate().alpha(1f).translationY(0f)
                .setStartDelay(delay).setDuration(350).start()
            delay += 70L
        }

        return root
    }

    private fun row(index: Int): StepRow = rows[index]!!

    /** Sets one check-list row to its visual state; DONE pops the check mark in. */
    private fun stepState(item: StepRow, state: Int) {
        when (state) {
            StepRow.ACTIVE -> {
                item.glyph.visibility = View.INVISIBLE
                item.spinner.visibility = View.VISIBLE
                item.label.setTextColor(COLOR_ACTIVE)
            }
            StepRow.DONE -> {
                item.spinner.visibility = View.INVISIBLE
                item.glyph.visibility = View.VISIBLE
                item.glyph.text = "\u2713" // ✓
                item.glyph.setTextColor(COLOR_DONE)
                item.glyph.alpha = 0f
                item.glyph.scaleX = 0.4f
                item.glyph.scaleY = 0.4f
                item.glyph.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(320).setInterpolator(OvershootInterpolator(2.5f)).start()
                item.label.setTextColor(COLOR_LABEL)
            }
            StepRow.PENDING -> {
                item.spinner.visibility = View.INVISIBLE
                item.glyph.visibility = View.VISIBLE
                item.glyph.text = "\u25cb" // ○
                item.glyph.setTextColor(COLOR_PENDING)
                item.glyph.alpha = 1f
                item.label.setTextColor(COLOR_FADED)
            }
            StepRow.FAILED -> {
                item.spinner.visibility = View.INVISIBLE
                item.glyph.visibility = View.VISIBLE
                item.glyph.text = "\u2717" // ✗
                item.glyph.setTextColor(COLOR_FAILED)
                item.glyph.alpha = 0f
                item.glyph.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300).start()
                item.label.setTextColor(COLOR_ACTIVE)
            }
        }
    }

    /** Stage N = steps 1..N done and step N+1 running. */
    private fun setStage(newStage: Int) {
        if (destroyed) return
        runOnUiThread {
            exitWaiting()
            if (newStage <= stage) return@runOnUiThread
            stage = newStage
            Log.i(TAG, "stage $newStage")
            for (index in 0 until newStage) stepState(row(index), StepRow.DONE)
            if (newStage < STAGE_SCREEN) stepState(row(newStage), StepRow.ACTIVE)
        }
    }

    /** No paired iPhone: swap the check list for the pairing hint until one appears. */
    private fun showWaiting() {
        if (destroyed || waitingShown || waitingView == null) return
        waitingShown = true
        runOnUiThread {
            val waiting = waitingView ?: return@runOnUiThread
            loadingCard?.visibility = View.INVISIBLE
            waiting.visibility = View.VISIBLE
            waiting.alpha = 0f
            waiting.animate().alpha(1f).setDuration(400).start()
        }
    }

    /** A device turned up (or the flow moved on): bring the check list back. */
    private fun exitWaiting() {
        if (!waitingShown) return
        waitingShown = false
        loadingCard?.visibility = View.VISIBLE
        waitingView?.visibility = View.GONE
    }

    /** Auto-connect is off and no session started: swap the check list for the start prompt. */
    private fun showConnectPrompt(holder: SurfaceHolder) {
        pendingHolder = holder
        if (promptShown) return
        promptShown = true
        runOnUiThread {
            loadingCard?.visibility = View.INVISIBLE
            val prompt = connectPrompt ?: return@runOnUiThread
            prompt.visibility = View.VISIBLE
            prompt.alpha = 0f
            prompt.animate().alpha(1f).setDuration(400).start()
        }
    }

    /** The driver tapped 开始连接: hide the prompt and boot the session as usual. */
    private fun startManualConnect() {
        if (started) return
        manualStart = true
        runOnUiThread {
            connectPrompt?.visibility = View.GONE
            loadingCard?.visibility = View.VISIBLE
        }
        val holder = pendingHolder ?: return
        startHost(holder)
    }

    /** The session dropped during start: the CarPlay step runs again, screen pending. */
    private fun rewindToSession() {
        runOnUiThread {
            if (stage <= STAGE_IPHONE) return@runOnUiThread
            stage = STAGE_IPHONE
            for (index in STAGE_IPHONE + 1 until STAGE_SCREEN) {
                stepState(row(index), StepRow.PENDING)
            }
            stepState(row(STAGE_IPHONE), StepRow.ACTIVE)
        }
    }

    /** Marks whatever step was running as failed; the status area shows the reason. */
    private fun failActiveRow() {
        runOnUiThread { stepState(row(stage.coerceIn(0, STAGE_SCREEN - 1)), StepRow.FAILED) }
    }

    /** Status updates arrive from the host's background threads; all UI work stays on the main thread. */
    private fun setStatus(text: String) {
        if (destroyed) return
        runOnUiThread {
            status?.text = text
            when {
                text.startsWith("Connection failed") -> failActiveRow()
                text.startsWith("No paired iPhone found") -> showWaiting()
                text.startsWith("Waiting for the iPhone to reconnect") -> rewindToSession()
            }
        }
    }

    private fun hideSplash() {
        if (destroyed || splashHidden) return
        splashHidden = true
        Log.i(TAG, "first CarPlay frame after ${SystemClock.elapsedRealtime() - coldStartAt} ms")
        runOnUiThread {
            mainHandler.removeCallbacksAndMessages(null)
            stage = STAGE_SCREEN
            stepState(row(STAGE_SCREEN - 1), StepRow.DONE)
            // Let the last check land before the picture takes over.
            mainHandler.postDelayed({
                val view = splash ?: return@postDelayed
                view.animate().alpha(0f).setDuration(450)
                    .withEndAction { view.visibility = View.GONE }
            }, 350L)
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
            failActiveRow()
            status?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            status?.setTextColor(COLOR_FAILED)
            status?.text = "Start failed: $detail"
        }
    }

    // -- host handshake ----------------------------------------------------------------

    private fun startHost(holder: SurfaceHolder) {
        val frame = holder.surfaceFrame
        val width = frame.width()
        val height = frame.height()
        if (width <= 0 || height <= 0) return
        if (started) {
            // Back from the background: the CarPlay session lives on and rebinds its main
            // stream to the holder's surface. Android may hand back the same Surface instance,
            // so reattach on every callback; the identity check only trims the log spam
            // (surfaceCreated is followed by surfaceChanged with the same surface).
            val recreated = holder.surface
            if (recreated !== boundSurface) {
                boundSurface = recreated
                Log.i(TAG, "surface recreated; reattaching live session")
            }
            LegacyWirelessHost.attachSurface(recreated)
            return
        }
        if (!manualStart && !LegacySettings.autoConnect(this)) {
            showConnectPrompt(holder)
            return
        }
        started = true
        val surface: Surface = holder.surface
        boundSurface = surface
        setStatus("Preparing the connection…")
        // The host builds the media sink and starts the controller bootstrap; run it off
        // the main thread so the loading screen keeps animating.
        Thread({
            try {
                LegacyWirelessHost.start(
                    context = this,
                    surface = surface,
                    width = width,
                    height = height,
                    log = { line -> Log.i(TAG, line) },
                    onStatus = { text -> setStatus(text) },
                    onStage = { step -> setStage(step) },
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
        // The SurfaceHolder contract: everything must leave this surface before the callback
        // returns, or a recreated one stays black. The main decoder is released and the next
        // frames start a fresh one on the recreated surface.
        val confirmed = LegacyWirelessHost.detachSurface(holder.surface)
        // The old surface is gone: onResume must not rebind it (surfaceCreated replaces it).
        boundSurface = null
        Log.i(TAG, "surface destroyed; detach confirmed=$confirmed")
    }
}