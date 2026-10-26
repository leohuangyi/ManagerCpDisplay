package com.shilapi.xcertplay.adb

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class LocalAdbReplyRoutingTest {
    @Test fun awaitedSourceSwitchRequiresItsOwnDispatchReply() {
        val application = RuntimeEnvironment.getApplication()
        val context = CapturingContext(application)
        val client = LocalAdb(context)
        val worker = Executors.newSingleThreadExecutor()
        try {
            client.requestSourceSwitch()
            val oldRequest = context.requests.poll(5, TimeUnit.SECONDS)
                ?: throw AssertionError("fire-and-forget source request missing")
            val waiting = worker.submit<LocalAdb.RootResult?> { client.requestSourceSwitch(5_000) }
            val request = context.requests.poll(5, TimeUnit.SECONDS)
                ?: throw AssertionError("awaited source request missing")
            deliverReply(application, oldRequest, 0, "dispatched;sourceId=19")
            assertFalse("source request accepted an earlier dispatch reply", waiting.isDone)
            deliverReply(application, request, 1, "capability=not_applicable;dispatched=false")
            assertEquals(1, waiting.get(5, TimeUnit.SECONDS)?.exit)

            val supported = worker.submit<LocalAdb.RootResult?> { client.requestSourceSwitch(5_000) }
            val nextRequest = context.requests.poll(5, TimeUnit.SECONDS)
                ?: throw AssertionError("next source request missing")
            deliverReply(application, nextRequest, 0, "dispatched;sourceId=19")
            assertEquals("dispatched;sourceId=19", supported.get(5, TimeUnit.SECONDS)?.output)
        } finally {
            client.cancelPendingOperations()
            worker.shutdownNow()
            worker.awaitTermination(5, TimeUnit.SECONDS)
            client.close()
        }
    }

    @Test fun overlappingClientsDoNotAcceptEachOthersSuccessReply() {
        val application = RuntimeEnvironment.getApplication()
        val context = CapturingContext(application)
        val firstClient = LocalAdb(context)
        val secondClient = LocalAdb(context)
        val workers = Executors.newFixedThreadPool(2)
        val first = workers.submit<LocalAdb.RootResult?> {
            firstClient.execOp("first", null, 5_000)
        }
        val second = workers.submit<LocalAdb.RootResult?> {
            secondClient.execOp("second", null, 5_000)
        }
        try {
            // Hold both requests before replying, then deliver the second success first.
            val requests = List(2) {
                context.requests.poll(5, TimeUnit.SECONDS)
                    ?: throw AssertionError("client did not send its request")
            }.associateBy { it.getStringExtra("op") }
            deliverReply(application, requests.getValue("second"), 0, "second-success")

            val secondResult = second.get(5, TimeUnit.SECONDS)
            assertEquals(0, secondResult?.exit)
            assertEquals("second-success", secondResult?.output)
            assertFalse("first request accepted another client's success", first.isDone)

            deliverReply(application, requests.getValue("first"), 1, "first-failure")
            val firstResult = first.get(5, TimeUnit.SECONDS)
            assertEquals(1, firstResult?.exit)
            assertEquals("first-failure", firstResult?.output)
        } finally {
            first.cancel(true)
            second.cancel(true)
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
            firstClient.close()
            secondClient.close()
        }
    }

    private fun deliverReply(context: Context, request: Intent, exit: Int, output: String) {
        context.sendBroadcast(Intent("com.automanager.cp.RESULT").apply {
            setPackage(request.getStringExtra("replyTo"))
            putExtra("requestId", request.getLongExtra("requestId", -1L))
            putExtra("exit", exit)
            putExtra("output", output)
        })
        // Exercise LocalAdb's actual registered receiver and its reply Handler.
        shadowOf(Looper.getMainLooper()).idle()
        ShadowLooper.getAllLoopers().filter { it != Looper.getMainLooper() }
            .forEach { shadowOf(it).idle() }
    }

    /** Only capture outgoing helper requests; receiver registration/delivery stays with Android. */
    private class CapturingContext(base: Context) : ContextWrapper(base) {
        val requests = LinkedBlockingQueue<Intent>()

        override fun getApplicationContext(): Context = this

        override fun sendBroadcast(intent: Intent) {
            if (intent.action == "com.automanager.cp.OP" || intent.action == "com.automanager.cp.SWITCH_SOURCE") {
                requests.add(Intent(intent))
            } else {
                super.sendBroadcast(intent)
            }
        }
    }
}
