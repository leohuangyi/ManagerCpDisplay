package com.shilapi.xcertplay.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A privileged shell executing in the assistant app ("小助手", `com.automanager`), reached over
 * broadcast.
 *
 * This app no longer owns any root/adb logic: every privileged command is forwarded to the
 * assistant app's `CpManager`, which runs it with `su` and answers with broadcasts directed back
 * at this app. There is no local adbd connection and no fallback — when the assistant app is
 * offline or its answers time out, [connect] reports [Access.UNREACHABLE] and [shell]/[openShell]
 * fail fast with null.
 *
 * Protocol (kept in sync with `com.automanager.cp.CpManager`):
 * - PROBE → RESULT carrying `capability` = root | no-root | no-su. `root` maps to
 *   [Access.READY], anything else to [Access.NOT_APPROVED]. A missing answer is
 *   [Access.UNREACHABLE]. [Access.UNSUPPORTED] is no longer produced but the enum value stays.
 * - CMD (requestId/command/timeoutMs) → RESULT carrying exit/output/error.
 * - SWITCH_SOURCE (requestId/sourceId) → RESULT carrying exit; the assistant forwards
 *   MG_CHANGE_SOURCE_MANUAL so the avap service executes `changeSource(sourceId)`.
 * - SESSION_OPEN (session/command) → SESSION_ACK (ok/error); afterwards every output line is
 *   broadcast as SESSION_OUT (line), and the end of the stream is announced with SESSION_OUT
 *   (closed=true). SESSION_WRITE (data) feeds stdin, SESSION_CLOSE ends the session.
 */
class LocalAdb(context: Context) : Closeable {
    enum class Access { READY, NOT_APPROVED, UNREACHABLE, UNSUPPORTED }

    /** The exit status and combined output of a one-shot [exec]. */
    class RootResult(val exit: Int, val output: String?)

    private val appContext = context.applicationContext
    private val cancelled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val requestIds = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val replyReceiver = ReplyReceiver()
    @Volatile private var interactive: InteractiveShell? = null
    @Volatile private var lastReadyAtMillis = 0L

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_CP_RESULT)
            addAction(ACTION_CP_SESSION_ACK)
            addAction(ACTION_CP_SESSION_OUT)
        }
        // The reply receiver must never run on the main looper: exec/connect await their
        // answers with a CountDownLatch, and a caller doing that on the main thread would
        // otherwise block the very thread the reply needs, ANRing after the timeout. The
        // looper is shared process-wide so short-lived `use { }` clients do not spawn one
        // thread per command.
        val scheduler = Handler(replyLooper())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 必须显式 exported：广播来自小助手进程。
            appContext.registerReceiver(replyReceiver, filter, null, scheduler, Context.RECEIVER_EXPORTED)
        } else {
            appContext.registerReceiver(replyReceiver, filter, null, scheduler)
        }
    }

    /**
     * Checks that the assistant app is online and runs commands as root. The old "Allow
     * debugging?" approval dialog no longer exists: `mayAsk` is accepted for API compatibility
     * but does not change the probe. A READY answer is cached for 10 s on long-lived clients to
     * avoid one `su` spawn per background read.
     */
    @Synchronized
    fun connect(mayAsk: Boolean): Access {
        if (cancelled.get() || closed.get()) return Access.UNREACHABLE
        val readyAge = System.currentTimeMillis() - lastReadyAtMillis
        if (readyAge in 0..READY_CACHE_MILLIS) return Access.READY
        val requestId = requestIds.incrementAndGet()
        val probe = Pending()
        pending[requestId] = probe
        broadcast(ACTION_CP_PROBE) { intent ->
            intent.putExtra(EXTRA_REQUEST_ID, requestId)
        }
        if (!awaitPending(probe, requestId, CONNECT_TIMEOUT_MS)) return Access.UNREACHABLE
        if (probe.capability == CAP_ROOT) {
            lastReadyAtMillis = System.currentTimeMillis()
            return Access.READY
        }
        return Access.NOT_APPROVED
    }

    /** Runs [command] in the assistant app's root shell and returns its output, or null on failure. */
    fun shell(command: String): String? = shell(command, READ_TIMEOUT_MS)

    /** Same as [shell], with a larger bounded wait for a one-shot slow command. */
    @Synchronized
    fun shell(command: String, readTimeoutMillis: Int): String? {
        if (cancelled.get() || closed.get()) return null
        require(readTimeoutMillis in 1..MAX_COMMAND_TIMEOUT_MS)
        val result = exec(command, readTimeoutMillis) ?: return null
        if (result.exit < 0) return null
        return result.output?.trim()
    }

    /**
     * Upstream one-shot write path compatibility: [stdin] is forwarded to the assistant app
     * together with the command (EXTRA_STDIN), to be fed to the command's stdin once CpManager
     * consumes it. Keeps the newly merged upstream hotspot-config writer compiling.
     */
    @Synchronized
    fun shell(command: String, readTimeoutMillis: Int, stdin: ByteArray?): String? {
        if (cancelled.get() || closed.get()) return null
        require(readTimeoutMillis in 1..MAX_COMMAND_TIMEOUT_MS)
        val result = exec(command, readTimeoutMillis, stdin) ?: return null
        if (result.exit < 0) return null
        return result.output?.trim()
    }

    /**
     * One-shot command returning exit status and raw output. Unlike [shell] this also reports
     * execution failures (exit = -1 with the error text in [RootResult.output]); it is null only
     * when the assistant app did not answer.
     */
    @Synchronized
    fun exec(command: String, timeoutMillis: Int, stdin: ByteArray? = null): RootResult? {
        if (cancelled.get() || closed.get()) return null
        val bounded = timeoutMillis.coerceIn(1, MAX_COMMAND_TIMEOUT_MS)
        val requestId = requestIds.incrementAndGet()
        val result = Pending()
        pending[requestId] = result
        broadcast(ACTION_CP_CMD) { intent ->
            intent.putExtra(EXTRA_REQUEST_ID, requestId)
            intent.putExtra(EXTRA_COMMAND, command)
            intent.putExtra(EXTRA_TIMEOUT_MS, bounded.toLong())
            if (stdin != null) intent.putExtra(EXTRA_STDIN, stdin)
        }
        if (!awaitPending(result, requestId, bounded + RESULT_GRACE_MILLIS)) return null
        // 助手应答失败（未激活/timed out/无 su 等）时 error 非空且 output 常被填成空串，
        // 必须优先透传 error；只有 error 为空才代表命令真正执行，output 才是真实输出。
        return if (result.error != null) {
            RootResult(result.exit, result.error)
        } else {
            RootResult(result.exit, result.output)
        }
    }

    /**
     * One-shot opcode command. The assistant app's CpManager translates [op] (an opaque token
     * such as `n0`/`n1`) plus optional newline-joined [args] into the real privileged command it
     * owns; this app never holds the underlying shell text, so decompiling it does not reveal the
     * hotspot wiring. Answer semantics match [exec].
     */
    @Synchronized
    fun execOp(op: String, args: String?, timeoutMillis: Int): RootResult? {
        if (cancelled.get() || closed.get()) return null
        val bounded = timeoutMillis.coerceIn(1, MAX_COMMAND_TIMEOUT_MS)
        val requestId = requestIds.incrementAndGet()
        val result = Pending()
        pending[requestId] = result
        broadcast(ACTION_CP_OP) { intent ->
            intent.putExtra(EXTRA_REQUEST_ID, requestId)
            intent.putExtra(EXTRA_OP, op)
            if (args != null) intent.putExtra(EXTRA_ARGS, args)
            intent.putExtra(EXTRA_TIMEOUT_MS, bounded.toLong())
        }
        if (!awaitPending(result, requestId, bounded + RESULT_GRACE_MILLIS)) return null
        return if (result.error != null) {
            RootResult(result.exit, result.error)
        } else {
            RootResult(result.exit, result.output)
        }
    }

    /**
     * 请求小助手把车机媒体源切换到 [sourceId]（43=APPS，44=CarPlay）。仅投递不等待应答：
     * 小助手 CpManager 收到后转发 MG_CHANGE_SOURCE_MANUAL 完成切换，其 RESULT 回执因无
     * 对应 Pending 而被忽略。调用方通常用短期实例（`use`）发出即可。
     */
    fun requestSourceSwitch() {
        broadcast(ACTION_CP_SWITCH_SOURCE) { intent ->
            intent.putExtra(EXTRA_REQUEST_ID, requestIds.incrementAndGet())
        }
    }

    /** Exclusive interactive stream. Use a dedicated LocalAdb instance; never mix with [shell]. */
    @Synchronized
    fun openShell(command: String): InteractiveShell? {
        if (cancelled.get() || closed.get()) return null
        val sessionId = requestIds.incrementAndGet()
        val ack = Pending()
        pending[sessionId] = ack
        // 在等待 ACK 之前就注册 interactive：worker 可能在 ACK 之后立刻输出首行，
        // 避免首行广播到达时 interactive 还是 null 而被丢弃。
        val shell = InteractiveShell(sessionId, ::writeSession, ::closeSession)
        interactive = shell
        broadcast(ACTION_CP_SESSION_OPEN) { intent ->
            intent.putExtra(EXTRA_SESSION, sessionId)
            intent.putExtra(EXTRA_COMMAND, command)
        }
        if (!awaitPending(ack, sessionId, ACK_TIMEOUT_MILLIS)) {
            shell.close()
            interactive = null
            return null
        }
        if (!ack.ackOk) {
            interactive = null
            shell.close()
            return null
        }
        return shell
    }

    /**
     * One request at a time, with response size limits and a total deadline. Every output line
     * arrives as a broadcast and is buffered in an instance queue; the request completes when the
     * response text is exactly `done\n` (or ends with `\ndone\n`), as with the former ADB stream.
     */
    class InteractiveShell internal constructor(
        internal val sessionId: Long,
        private val write: (Long, String) -> Unit,
        private val closeSession: (Long) -> Unit,
    ) : Closeable {
        private val queue = LinkedBlockingQueue<StreamEvent>()
        @Volatile private var closed = false

        internal fun onLine(line: String) {
            if (!closed && queue.size < MAX_QUEUED_LINES) queue.offer(StreamEvent(line, false))
        }

        internal fun onClosed() {
            if (closed) return
            closed = true
            queue.offer(StreamEvent("", true))
        }

        @Synchronized
        fun exchangeBounded(request: String, timeoutMillis: Int = 5000): String? {
            if (closed || request.toByteArray(Charsets.UTF_8).size > 1024 ||
                '\n' in request || '\r' in request
            ) return null
            val boundedMillis = timeoutMillis.coerceIn(1, 10_000)
            write(sessionId, "$request\n")
            val response = InteractiveResponseBuffer()
            val deadline = System.nanoTime() + boundedMillis * 1_000_000L
            while (!response.complete) {
                val remainingMillis = (deadline - System.nanoTime()) / 1_000_000L
                if (remainingMillis <= 0) {
                    close()
                    return null
                }
                val event = queue.poll(remainingMillis, TimeUnit.MILLISECONDS) ?: run {
                    close()
                    return null
                }
                if (event.eof) {
                    closed = true
                    return null
                }
                try {
                    response.accept((event.line + "\n").toByteArray(Charsets.UTF_8))
                } catch (_: IOException) {
                    close()
                    return null
                }
            }
            return response.text()
        }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            runCatching { closeSession(sessionId) }
            queue.offer(StreamEvent("", true))
        }
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        interactive?.close()
        interactive = null
        for (waiting in pending.values) waiting.latch.countDown()
        pending.clear()
        runCatching { appContext.unregisterReceiver(replyReceiver) }
    }

    /** Retires this client and unblocks pending waits without closing its broadcast receiver. */
    fun cancelPendingOperations() {
        cancelled.set(true)
        for (waiting in pending.values) waiting.latch.countDown()
    }

    // ---- 协议发送与应答缓存 ----

    private fun broadcast(action: String, apply: (Intent) -> Unit) {
        if (closed.get()) return
        val intent = Intent(action).setPackage(PKG_ASSISTANT)
        // replyTo 用调用方自己的包名：DiPlay 主 APK 与 legacy APK 共用本类，应答必须定向回到正确的请求方。
        intent.putExtra(EXTRA_REPLY_TO, appContext.packageName)
        apply(intent)
        appContext.sendBroadcast(intent)
    }

    private fun writeSession(sessionId: Long, data: String) {
        broadcast(ACTION_CP_SESSION_WRITE) { intent ->
            intent.putExtra(EXTRA_SESSION, sessionId)
            intent.putExtra(EXTRA_DATA, data)
        }
    }

    private fun closeSession(sessionId: Long) {
        interactive = null
        broadcast(ACTION_CP_SESSION_CLOSE) { intent ->
            intent.putExtra(EXTRA_SESSION, sessionId)
        }
    }

    private inner class ReplyReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val received = intent ?: return
            when (received.action) {
                ACTION_CP_RESULT -> {
                    val requestId = received.getLongExtra(EXTRA_REQUEST_ID, -1L)
                    val waiting = pending.remove(requestId) ?: return
                    if (received.hasExtra(EXTRA_CAPABILITY)) {
                        waiting.capability = received.getStringExtra(EXTRA_CAPABILITY)
                    } else {
                        waiting.exit = received.getIntExtra(EXTRA_EXIT, -1)
                        waiting.output = received.getStringExtra(EXTRA_OUTPUT)
                        waiting.error = received.getStringExtra(EXTRA_ERROR)
                    }
                    waiting.latch.countDown()
                }
                ACTION_CP_SESSION_ACK -> {
                    val sessionId = received.getLongExtra(EXTRA_SESSION, -1L)
                    val waiting = pending.remove(sessionId) ?: return
                    waiting.ackOk = received.getBooleanExtra(EXTRA_OK, false)
                    waiting.error = received.getStringExtra(EXTRA_ERROR)
                    waiting.latch.countDown()
                }
                ACTION_CP_SESSION_OUT -> {
                    val sessionId = received.getLongExtra(EXTRA_SESSION, -1L)
                    val shell = interactive ?: return
                    if (shell.sessionId != sessionId) return
                    if (received.getBooleanExtra(EXTRA_CLOSED, false)) {
                        shell.onClosed()
                    } else {
                        shell.onLine(received.getStringExtra(EXTRA_LINE) ?: "")
                    }
                }
            }
        }
    }

    private fun awaitPending(waiting: Pending, requestId: Long, timeoutMillis: Long): Boolean {
        return try {
            val answered = waiting.latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
            if (!answered) pending.remove(requestId)
            answered
        } catch (_: InterruptedException) {
            pending.remove(requestId)
            Thread.currentThread().interrupt()
            false
        }
    }

    private class Pending {
        val latch = CountDownLatch(1)
        @Volatile var capability: String? = null
        @Volatile var exit = -1
        @Volatile var output: String? = null
        @Volatile var error: String? = null
        @Volatile var ackOk = false
    }

    private class StreamEvent(val line: String, val eof: Boolean)

    private companion object {
        /** Lazily started, process-wide looper for reply broadcasts; daemon so it never blocks process death. */
        @Volatile
        private var replyLooperInstance: Looper? = null

        fun replyLooper(): Looper {
            replyLooperInstance?.let { return it }
            return synchronized(this) {
                replyLooperInstance?.let { return it }
                var built: Looper? = null
                val lock = Object()
                val thread = object : Thread() {
                    override fun run() {
                        Looper.prepare()
                        synchronized(lock) {
                            built = Looper.myLooper()
                            lock.notifyAll()
                        }
                        Looper.loop()
                    }
                }.apply {
                    name = "xcertplay-adb-replies"
                    isDaemon = true
                }
                thread.start()
                synchronized(lock) {
                    while (built == null) {
                        try {
                            lock.wait()
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }
                val result = checkNotNull(built) { "reply looper failed to start" }
                replyLooperInstance = result
                result
            }
        }

        const val PKG_ASSISTANT = "com.automanager"

        const val ACTION_CP_CMD = "com.automanager.cp.CMD"
        const val ACTION_CP_PROBE = "com.automanager.cp.PROBE"
        const val ACTION_CP_SESSION_OPEN = "com.automanager.cp.SESSION_OPEN"
        const val ACTION_CP_SESSION_WRITE = "com.automanager.cp.SESSION_WRITE"
        const val ACTION_CP_SESSION_CLOSE = "com.automanager.cp.SESSION_CLOSE"
        const val ACTION_CP_SWITCH_SOURCE = "com.automanager.cp.SWITCH_SOURCE"
        const val ACTION_CP_OP = "com.automanager.cp.OP"
        const val ACTION_CP_RESULT = "com.automanager.cp.RESULT"
        const val ACTION_CP_SESSION_ACK = "com.automanager.cp.SESSION_ACK"
        const val ACTION_CP_SESSION_OUT = "com.automanager.cp.SESSION_OUT"

        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_REPLY_TO = "replyTo"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_TIMEOUT_MS = "timeoutMs"
        const val EXTRA_STDIN = "stdin"
        const val EXTRA_EXIT = "exit"
        const val EXTRA_OUTPUT = "output"
        const val EXTRA_ERROR = "error"
        const val EXTRA_CAPABILITY = "capability"
        const val EXTRA_SESSION = "session"
        const val EXTRA_DATA = "data"
        const val EXTRA_LINE = "line"
        const val EXTRA_OK = "ok"
        const val EXTRA_CLOSED = "closed"
        const val EXTRA_OP = "op"
        const val EXTRA_ARGS = "args"

        const val CAP_ROOT = "root"

        const val CONNECT_TIMEOUT_MS = 5_000L
        const val READY_CACHE_MILLIS = 10_000L
        const val RESULT_GRACE_MILLIS = 2_000L
        const val ACK_TIMEOUT_MILLIS = 10_000L
        const val READ_TIMEOUT_MS = 5_000
        const val MAX_COMMAND_TIMEOUT_MS = 30_000
        const val MAX_QUEUED_LINES = 2_000
    }
}

/**
 * The interactive response is complete when the accumulated text is exactly `done\n` or ends
 * with `\ndone\n`, matching the former ADB stream contract. Design decision: this class is
 * provided for historical parity; the broadcast bridge feeds it one whole line at a time.
 */
internal class InteractiveResponseBuffer {
    private val bytes = java.io.ByteArrayOutputStream()
    var complete = false
        private set
    fun accept(payload: ByteArray) {
        if (complete || bytes.size() + payload.size > 4096) throw IOException("interactive response limit")
        bytes.write(payload)
        val text = bytes.toString("UTF-8")
        if (text == "done\n" || text.endsWith("\ndone\n")) complete = true
    }
    fun text(): String = bytes.toString("UTF-8").removeSuffix("done\n").trimEnd('\n', '\r')
}