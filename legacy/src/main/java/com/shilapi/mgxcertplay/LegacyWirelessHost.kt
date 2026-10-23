package com.shilapi.mgxcertplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    /** How long a screen stream that died while the app stayed in the foreground may sit silent
     * before the full wireless reboot. The key frame request that goes out first revives a
     * live-but-wedged stream, so this window only catches a session that died without a pause
     * (the resume machinery never arms for those).
     */
    private const val SCREEN_STALL_TIMEOUT_MS = 10_000L

    /**
     * How long the final-exit teardown settles after the sockets are closed, before the
     * hotspot/Wi-Fi restore bounces the radio. The Bluetooth stack is still draining the
     * RFCOMM/HFP teardown right after close(); bouncing Wi-Fi concurrently with it crashed
     * the process natively (deadbaad invalid heap address in dlfree).
     */
    private const val EXIT_COOLDOWN_MS = 600L

    /**
     * Fast-reopen gate after a final exit: the activity finishes at once while the teardown
     * thread keeps closing sockets and restoring the hotspot/Wi-Fi in the background. A
     * reopen inside this window would race that teardown, so onCreate refuses it with a
     * "still exiting" toast. The teardown thread clears the gate as soon as it finishes;
     * this is only the cap for a teardown that hangs.
     */
    private const val EXIT_GATE_MS = 15_000L

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
    /** A final exit is in progress: recovery rebuilds and cold restarts must not run. */
    @Volatile private var exiting = false
    /**
     * Until when (elapsedRealtime) a fast reopen must be refused because the final-exit
     * teardown thread is still closing sockets and restoring the hotspot in the background.
     * 0 = no gate; the teardown thread clears it when it finishes.
     */
    @Volatile private var exitGateOpenUntil = 0L
    /**
     * The controller [prepareExit] handed off before nulling the live one; [shutdown] must
     * not tear it down again, its own teardown thread owns the close, cooldown and restore.
     */
    @Volatile private var pendingExitRestore: LegacyWirelessController? = null
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
        // A fresh boot cancels a previous exit that never finished; that exit's teardown
        // thread holds its own reference and the restore it runs is idempotent.
        exiting = false
        pendingExitRestore = null
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
                // CarPlay 画面出现：引导已完全切换到 Wi-Fi，此时补连 A2DP 不会再撞 RFCOMM/iAP2。
                controller?.ensureA2dpConnected()
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
            networkMode = "auto",
        )
        built = next
        controller = next
        // A wedged resume cannot recover at the session layer: tearing the session down kills
        // the iAP2/RFCOMM transport, and only the bootstrap opens the RFCOMM listener. The
        // recovery is therefore a full wireless reboot, re-run against the newest surface.
        restarter = { target ->
            Thread({
                if (exiting) {
                    log("wireless restart skipped: final exit in progress")
                } else {
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
                }
            }, "legacy-host-restart").apply {
                isDaemon = true
                start()
            }
        }
        // No paired iPhone: do not give up on the start. The pairing happens in the head
        // unit's Bluetooth settings, outside the app, so keep polling until a device shows
        // up and then boot the session. The activity shows its pairing hint meanwhile.
        var phone = findPhone(appContext)
        if (phone == null) {
            log("no paired iPhone found; pair an iPhone over Bluetooth first")
            onStatus(StatusUpdate(HostStatus.NO_PAIRED))
        }
        while (phone == null) {
            Thread.sleep(3000L)
            if (controller !== next) return // shut down while waiting for a pairing
            phone = findPhone(appContext)
            if (phone != null) log("paired iPhone found: ${phone.name ?: phone.address}")
        }
        // Persist this iPhone as the last CarPlay device for the next cold start's tier-2 pick.
        LegacySettings.setLastDeviceAddress(appContext, phone.address)
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
        if (exiting) {
            Log.i(TAG, "restartWireless abort: final exit in progress")
            return
        }
        Log.i(TAG, "restartWireless entry: restarter=${restarter != null}, currentSurface=${currentSurface != null}")
        val action = restarter
        if (action == null) { Log.w(TAG, "restartWireless abort: no restarter"); return }
        val target = currentSurface
        if (target == null) { Log.w(TAG, "restartWireless abort: no surface"); return }
        if (restartingWireless) { Log.i(TAG, "restartWireless: rebuild already in flight"); return }
        // All callers (resume/stall watchdogs, onSessionEnded) arrive on the main thread.
        // shutdown() closes the engine synchronously, and for a hotspot we started that ends
        // in an su round-trip whose reply broadcast is delivered back on the main looper -
        // blocking the main thread here would starve that delivery until the wait times out
        // (~20 s) and ANR the activity. Run the rebuild off the main thread instead; the flag
        // marks the window so the stall watchdog cannot arm mid-rebuild.
        restartingWireless = true
        Thread({
            shutdown()
            Log.i(TAG, "restartWireless: invoking restarter with surface")
            action(target)
        }, "legacy-wireless-restart").apply {
            isDaemon = true
            start()
        }
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

    /**
     * A final-exit teardown is still running in the background (or its gate has not elapsed):
     * the activity finishes instantly on exit, so a reopen inside the window would race the
     * socket close / hotspot restore. The caller shows a "still exiting" hint and closes.
     */
    @JvmStatic
    fun isExitCoolingDown(): Boolean = exitGateOpenUntil > SystemClock.elapsedRealtime()

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
        // prepareExit() detached the controller before this (the normal double-back-press
        // flow): its teardown thread owns the close, the settle cooldown and the Wi-Fi
        // restore, so nothing here may run against it again.
        val prepared = pendingExitRestore != null
        val current = controller ?: pendingExitRestore
        controller = null
        pendingExitRestore = null
        sink = null
        // The in-process wireless restart rebuilds the hotspot itself through prepareAuto and
        // must have the old engine fully closed before the restarter boots the next one, so its
        // teardown stays synchronous.
        if (restartingWireless) {
            try {
                current?.close()
            } catch (_: Throwable) {
            }
            return
        }
        if (prepared) return
        exiting = true
        // Final exit without the double-back path (the activity was destroyed another way):
        // every remaining step moves off the main thread. close() tears the hotspot down and
        // the restore then bounces Wi-Fi (svc wifi disable -> enable) over the su broadcast
        // channel, so the head unit rejoins its saved Wi-Fi networks by itself. Both su
        // round-trips can take tens of seconds; leaving them on the main thread starved
        // Activity.onDestroy past the 10 s destroy timeout and the UI appeared to ignore the exit.
        if (current != null) {
            // Refuse a fast reopen (new onCreate) while this teardown is still running.
            exitGateOpenUntil = SystemClock.elapsedRealtime() + EXIT_GATE_MS
            Thread({
                try {
                    current.close()
                } catch (_: Throwable) {
                }
                // Same settle window as prepareExit(): the radio bounce must never race the
                // Bluetooth teardown.
                try {
                    Thread.sleep(EXIT_COOLDOWN_MS)
                } catch (_: InterruptedException) {
                }
                runCatching { current.restoreHotspotAfterCarPlay() }
                    .onFailure { Log.w(TAG, "Hotspot restore after CarPlay exit failed", it) }
                exitGateOpenUntil = 0L
            }, "legacy-hotspot-restore").apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * Final exit (the double-back-press in LegacyCarPlayActivity): the activity finishes
     * right away, while the wireless teardown keeps running on a background thread. The
     * controller is detached first so its session-ended callback cannot kick off a wireless
     * rebuild; close() then runs off the main thread, a short settle window lets the
     * Bluetooth stack drain the RFCOMM/HFP teardown, and afterwards the hotspot/Wi-Fi
     * restore bounces the radio. [onDone] is posted to the main thread immediately, so the
     * activity finishes without waiting for the radio work; its onDestroy -> shutdown()
     * finds the pending hand-off and leaves this background thread to finish the job.
     */
    @JvmStatic
    fun prepareExit(onDone: () -> Unit) {
        // Finish first, disconnect in the background: posting onDone lets the caller run
        // finish() on the main thread right away, nothing here holds it up. The detach below
        // still happens synchronously before finish() is processed, so onDestroy -> shutdown()
        // sees the hand-off and does not tear the stack down a second time.
        mainHandler.post(onDone)
        if (exiting) return
        exiting = true
        val current = controller ?: return
        pendingExitRestore = current
        controller = null
        // The activity finishes right now, but this teardown keeps running below: refuse a
        // fast reopen while it does, or the new session would race the socket close.
        exitGateOpenUntil = SystemClock.elapsedRealtime() + EXIT_GATE_MS
        Thread({
            try {
                current.close()
            } catch (_: Throwable) {
            }
            // The crash signature (deadbaad invalid heap address in dlfree, inside the
            // framework's Bluetooth close native) came from tearing the sockets and the radio
            // down concurrently; let the Bluetooth stack settle before the Wi-Fi bounce.
            try {
                Thread.sleep(EXIT_COOLDOWN_MS)
            } catch (_: InterruptedException) {
            }
            runCatching { current.restoreHotspotAfterCarPlay() }
                .onFailure { Log.w(TAG, "Hotspot restore after CarPlay exit failed", it) }
            // Teardown done: a reopen from here on starts a clean session.
            exitGateOpenUntil = 0L
        }, "legacy-exit-prepare").apply {
            isDaemon = true
            start()
        }
    }

    private fun findPhone(context: Context): BluetoothDevice? {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Log.w(TAG, "findPhone: BluetoothAdapter.getDefaultAdapter() returned null")
            return null
        }
        if (!adapter.isEnabled) {
            Log.w(TAG, "findPhone: adapter off (state=${adapter.state})")
            return null
        }
        // 已连接的设备优先：CarPlay 刚打开时，正在通电话/放音乐的 iPhone 已经是 A2DP/HFP 连接态，
        // 应直接用它来链接，而不是不管连接状态就抓第一个已配对设备。
        val connected = listOf(101, 2)
            .flatMap { connectedDevices(context, adapter, it) }
            .distinctBy { it.address }
        if (connected.isNotEmpty()) {
            val chosen = connected.first()
            Log.i(TAG, "findPhone: ${connected.size} connected -> " +
                connected.joinToString { "${it.name ?: "<unnamed>"} (${it.address})" } +
                " ; picked ${chosen.name ?: "<unnamed>"} (${chosen.address})")
            return chosen
        }
        val bonded = runCatching { adapter.bondedDevices }
            .onFailure { error -> Log.w(TAG, "findPhone: getBondedDevices threw", error) }
            .getOrNull()
        if (bonded == null) {
            Log.w(TAG, "findPhone: bondedDevices == null (state=${adapter.state})")
            return null
        }
        if (bonded.isEmpty()) {
            Log.i(TAG, "findPhone: bondedDevices empty (state=${adapter.state})")
            return null
        }
        // 次优：上一次链接过 CarPlay 的已配对设备（若仍配对）。
        val lastAddress = LegacySettings.lastDeviceAddress(context)
        if (lastAddress != null) {
            val last = bonded.firstOrNull { it.address == lastAddress }
            if (last != null) {
                Log.i(TAG, "findPhone: using last CarPlay device ${last.name ?: "<unnamed>"} (${last.address})")
                return last
            }
        }
        // 兜底：已配对的第一个设备。
        val chosen = bonded.first()
        Log.i(TAG, "findPhone: ${bonded.size} bonded -> " +
            bonded.joinToString { "${it.name ?: "<unnamed>"} (${it.address})" } +
            " ; picked ${chosen.name ?: "<unnamed>"} (${chosen.address})")
        return chosen
    }

    /** Queries the currently connected devices of one Bluetooth profile via its proxy. */
    private fun connectedDevices(context: Context, adapter: BluetoothAdapter, profile: Int): List<BluetoothDevice> {
        val latch = CountDownLatch(1)
        var proxy: BluetoothProfile? = null
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(connectedProfile: Int, connectedProxy: BluetoothProfile) {
                proxy = connectedProxy
                latch.countDown()
            }

            override fun onServiceDisconnected(disconnectedProfile: Int) {
                latch.countDown()
            }
        }
        try {
            if (!adapter.getProfileProxy(context, listener, profile)) return emptyList()
            if (!latch.await(2, TimeUnit.SECONDS)) return emptyList()
            return proxy?.connectedDevices ?: emptyList()
        } catch (error: Throwable) {
            Log.w(TAG, "connectedDevices(profile=$profile) failed", error)
            return emptyList()
        } finally {
            proxy?.let { runCatching { adapter.closeProfileProxy(profile, it) } }
        }
    }

    private fun align(value: Int): Int = (value / 2 * 2).coerceAtLeast(2)
}