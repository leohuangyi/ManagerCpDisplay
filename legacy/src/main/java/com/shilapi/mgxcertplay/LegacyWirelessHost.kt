package com.shilapi.mgxcertplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper

/**
 * Locale-independent status codes the host reports while booting the wireless link. The
 * activity maps each code to the locale string so the log-milestone matching stays stable
 * across translations.
 */
enum class HostStatus {
    PREPARING,
    HOTSPOT_READY,
    WAITING_IPHONE,
    IPHONE_CONNECTED,
    CARPLAY_READY,
    STARTING_SCREEN,
    RECONNECT_WAIT,
    NO_PAIRED,
    CONNECTION_FAILED,
}

/** A [HostStatus] plus an optional detail (usually the failure reason). */
class StatusUpdate(val status: HostStatus, val detail: String? = null)

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

    /**
     * How long [attachSurface] waits for the iPhone to re-establish the video stream before
     * declaring the session wedged. The resume first returns the borrowed main screen, which the
     * iPhone answers with a fresh SETUP in a couple of seconds when it is honored, so the
     * remaining window is for that SETUP plus its first frames. Only a session that answers none
     * of this gets the full reboot.
     */
    private const val SCREEN_RESUME_TIMEOUT_MS = 10_000L

    /**
     * The iPhone answers an unborrow with a fresh SETUP in a couple of seconds when it honors
     * it. One repeated unborrow plus a key-frame request covers a dropped command and a
     * still-live black stream before the watchdog falls back to the full reboot.
     */
    private const val SCREEN_RESUME_RETRY_DELAY_MS = 3_000L

    /**
     * How long a screen stream that died while the app stayed in the foreground may sit silent
     * before the full wireless reboot. The key frame request that goes out first revives a
     * live-but-wedged stream, so this window only catches a session that died without a pause
     * (the resume machinery never arms for those).
     */
    private const val SCREEN_STALL_TIMEOUT_MS = 10_000L

    @Volatile private var controller: LegacyWirelessController? = null
    @Volatile private var sink: AndroidMediaSink? = null
    @Volatile private var videoWidth = 1280
    @Volatile private var videoHeight = 720
    @Volatile private var screenStreamActive = false
    /** True only after [suspendVideo] tore down a live stream; arms the resume watchdog once. */
    @Volatile private var videoSuspended = false
    /** The main screen stream has carried frames at least once since the last shutdown. */
    @Volatile private var screenEverActive = false
    /** [suspendVideo] is tearing the stream down; that teardown's inactive edge is not a stall. */
    @Volatile private var suspendingScreen = false
    /** A full wireless reboot is being rebuilt; its teardown edges must not re-arm recovery. */
    @Volatile private var restartingWireless = false
    /** The newest surface [attachSurface] saw; a full restart rebinds to it. */
    @Volatile private var currentSurface: Surface? = null
    /** Cold-start re-entry built from the first [start] call; runs a full wireless reboot. */
    private var restarter: ((Surface) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Lightweight retry for a resume the first unborrow did not answer: one more unborrow plus a
     *  key frame, which covers a dropped command and a still-live but black stream. */
    private val resumeRetry = Runnable {
        if (!screenStreamActive && videoSuspended) {
            controller?.unborrowMainScreen()
            controller?.requestKeyFrame()
        }
    }
    private val resumeWatchdog = Runnable {
        videoSuspended = false
        // The unborrow / key-frame recovery ran its course: an iPhone that answered none of it
        // will not answer a lone SETUP either. Closing the AirPlay session tears down the
        // iAP2/RFCOMM transport too, and only the bootstrap opens the RFCOMM listener, so the
        // reliable recovery is a full wireless reboot.
        if (!screenStreamActive) restartWireless()
    }
    /** A screen stream that died while the app stayed in the foreground: the key frame probe
     *  already went out when it went inactive, so the silent survivors get the full reboot. */
    private val screenStallWatchdog = Runnable {
        if (!screenStreamActive && !videoSuspended && !restartingWireless) restartWireless()
    }

    @JvmStatic
    @JvmOverloads
    fun start(
        context: Context,
        surface: Surface,
        width: Int,
        height: Int,
        log: (String) -> Unit = { Log.i(TAG, it) },
        /** Short user-facing progress label, called from background threads. */
        onStatus: (StatusUpdate) -> Unit = {},
        /** Progress checks for the loading screen: 1 = hotspot ready, 2 = iPhone, 3 = CarPlay. */
        onStage: (Int) -> Unit = {},
        /** The CarPlay screen stream became active: the picture is arriving. */
        onVideo: () -> Unit = {},
    ) {
        if (controller != null) return
        // Record the surface this build renders to. restartWireless() rebinds to it, and on a
        // cold start the first surfaceChanged routes through the !started branch in the activity
        // (which only spawns start()) without an attachSurface, so currentSurface would stay null
        // and a session-death rebuild would abort with "no surface".
        currentSurface = surface
        // A restart build re-arms the foreground stall watchdog the moment the new sink
        // reports its first stream edge, so its protection window is closed here.
        restartingWireless = false
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
            screenStreamActive = active
            if (active) {
                screenEverActive = true
                suspendingScreen = false
                // The screen stream came back: the resume/stall recovery can stand down.
                mainHandler.removeCallbacks(resumeRetry)
                mainHandler.removeCallbacks(resumeWatchdog)
                mainHandler.removeCallbacks(screenStallWatchdog)
                videoSuspended = false
            } else if (screenEverActive && !videoSuspended && !suspendingScreen && !restartingWireless) {
                // The stream died while the app stayed in the foreground: the resume machinery
                // never arms without a pause, so probe with a key frame first and fall back to
                // the full wireless reboot when the session stays silent.
                controller?.requestKeyFrame()
                if (!mainHandler.hasCallbacks(screenStallWatchdog)) {
                    mainHandler.postDelayed(screenStallWatchdog, SCREEN_STALL_TIMEOUT_MS)
                }
            }
            if (active && !videoReported) {
                videoReported = true
                onVideo()
            }
        }
        sink = nextSink
        // Assigned only after construction so the onSessionEnded callback can compare against
        // "am I still the host's controller" without reading a local inside its own initializer.
        var built: LegacyWirelessController? = null
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
                onStatus(StatusUpdate(HostStatus.CONNECTION_FAILED, reason))
            },
            onSessionEnded = {
                // A session that had been established is gone for good (the iPhone ended the
                // control connection). This is a state transition, not a timeout: rebuild the
                // whole wireless link so the iPhone can reconnect from a clean slate.
                Log.i(TAG, "onSessionEnded callback: controller==built=${controller === built}, restarter=${restarter != null}, currentSurface=${currentSurface != null}")
                if (controller === built) {
                    mainHandler.post { restartWireless() }
                }
            },
            networkMode = LegacySettings.networkMode(appContext),
        )
        built = next
        controller = next
        // A wedged resume cannot recover at the session layer: tearing the session down kills
        // the iAP2/RFCOMM transport, and only the bootstrap opens the RFCOMM listener. The
        // recovery is therefore a full wireless reboot, re-run against the newest surface.
        restarter = { target ->
            Thread({
                runCatching {
                    start(
                        context = appContext,
                        surface = target,
                        width = videoWidth,
                        height = videoHeight,
                        log = log,
                        onStatus = onStatus,
                        onStage = onStage,
                        onVideo = onVideo,
                    )
                }.onFailure { error -> runCatching { log("wireless restart failed: $error") } }
            }, "legacy-host-restart").apply {
                isDaemon = true
                start()
            }
        }
        // No paired iPhone: do not give up on the start. The pairing happens in the head
        // unit's Bluetooth settings, outside the app, so keep polling until a device shows
        // up and then boot the session. The activity shows its pairing hint meanwhile.
        var phone = findPhone()
        if (phone == null) {
            log("no paired iPhone found; pair an iPhone over Bluetooth first")
            onStatus(StatusUpdate(HostStatus.NO_PAIRED))
        }
        while (phone == null) {
            Thread.sleep(3000L)
            if (controller !== next) return // shut down while waiting for a pairing
            phone = findPhone()
            if (phone != null) log("paired iPhone found: ${phone.name ?: phone.address}")
        }
        log("starting wireless CarPlay with ${phone.name ?: phone.address}")
        next.start(phone)
    }

    /** A short progress label for the milestone log lines the controller emits. */
    private fun statusFor(line: String): StatusUpdate? = when {
        "Network mode" in line -> StatusUpdate(HostStatus.HOTSPOT_READY)
        "listening on" in line -> StatusUpdate(HostStatus.WAITING_IPHONE)
        "Bluetooth RFCOMM connected" in line -> StatusUpdate(HostStatus.IPHONE_CONNECTED)
        "Wi-Fi iAP2 authenticated" in line -> StatusUpdate(HostStatus.CARPLAY_READY)
        "AirPlay session active" in line -> StatusUpdate(HostStatus.STARTING_SCREEN)
        "AirPlay session ended" in line -> StatusUpdate(HostStatus.RECONNECT_WAIT)
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
    // hasCallbacks exists as a public method on Handler in AOSP 4.2 (this head unit); lint only
    // knows the API 28 public release.
    @SuppressLint("NewApi")
    fun attachSurface(surface: Surface) {
        currentSurface = surface
        sink?.setSurface(SCREEN_TYPE_MAIN, surface)
        // A still CarPlay screen sends no frames; ask for one instead of showing black.
        sink?.refreshPicture(SCREEN_TYPE_MAIN)
        // Only arm recovery after a suspended stream: a cold start arms nothing, so a slow
        // bootstrap can never be mistaken for a wedged resume. Re-posting extends the window,
        // which surfaceChanged doing right after surfaceCreated must not cancel, but the unborrow
        // itself goes out once per pause.
        if (videoSuspended) {
            if (!mainHandler.hasCallbacks(resumeWatchdog)) {
                // The iPhone re-establishes the screen stream itself when the accessory returns
                // the borrowed main screen (changeModes unborrow), so a pause recovers over the
                // live control session instead of a reboot.
                controller?.unborrowMainScreen()
            }
            // surfaceCreated is followed by surfaceChanged with the same surface; arm each
            // timer once so the retry sends the unborrow exactly once instead of twice.
            if (!mainHandler.hasCallbacks(resumeRetry)) {
                mainHandler.postDelayed(resumeRetry, SCREEN_RESUME_RETRY_DELAY_MS)
            }
            mainHandler.postDelayed(resumeWatchdog, SCREEN_RESUME_TIMEOUT_MS)
        }
    }

    /**
     * The host activity went offscreen (onPause): borrows the CarPlay main screen from the
     * iPhone (changeModes) and releases the local video streams; the iPhone then stops pushing
     * frames into a decoder whose surface is about to go away, and it knows the stream is down.
     * The control session stays alive, and on resume the host returns the borrowed screen, which
     * makes the iPhone re-establish the screen stream.
     */
    @JvmStatic
    fun suspendVideo() {
        mainHandler.removeCallbacks(resumeRetry)
        mainHandler.removeCallbacks(resumeWatchdog)
        mainHandler.removeCallbacks(screenStallWatchdog)
        // A teardown edge from the pause below is not a foreground stall (the engine reports
        // it synchronously in some teardown paths), so mark the window before it fires.
        suspendingScreen = true
        // Only a real teardown arms the unborrow recovery in attachSurface.
        videoSuspended = controller?.pauseVideo() == true
        suspendingScreen = false
    }

    /**
     * Full wireless reboot for a wedged resume: the unborrow / key-frame recovery did not
     * bring the video stream back, and the transport cannot survive a session-level teardown
     * alone, so the stack restarts from the RFCOMM listener, exactly like a cold start.
     */
    private fun restartWireless() {
        Log.i(TAG, "restartWireless entry: restarter=${restarter != null}, currentSurface=${currentSurface != null}")
        val action = restarter
        if (action == null) { Log.w(TAG, "restartWireless abort: no restarter"); return }
        val target = currentSurface
        if (target == null) { Log.w(TAG, "restartWireless abort: no surface"); return }
        // shutdown() closes the engine, whose teardown edges must not re-arm the stall
        // watchdog for the boot that is about to replace the dead one.
        restartingWireless = true
        shutdown()
        Log.i(TAG, "restartWireless: invoking restarter with surface")
        action(target)
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
        mainHandler.removeCallbacks(resumeRetry)
        mainHandler.removeCallbacks(resumeWatchdog)
        mainHandler.removeCallbacks(screenStallWatchdog)
        screenStreamActive = false
        screenEverActive = false
        videoSuspended = false
        currentSurface = null
        restarter = null
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