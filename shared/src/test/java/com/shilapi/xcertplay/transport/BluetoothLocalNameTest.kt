package com.shilapi.xcertplay.transport

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BluetoothLocalNameTest {
    private class Radio {
        var name: String? = "Honda HFT"
        var accepted = true
        var enabled = true
        var cancelled = false
        var requests = 0
        var polls = 0
        var nowNanos = 0L
        var onRequest: () -> Unit = {}
        var onPoll: () -> Unit = {}

        fun synchronize(): Boolean = BluetoothLocalName.ensure(
            expected = "HondaHFT",
            currentName = { name },
            requestName = {
                assertEquals("HondaHFT", it)
                requests++
                onRequest()
                accepted
            },
            enabled = { enabled },
            cancelled = { cancelled },
            timeoutMillis = 250,
            clockNanos = { nowNanos },
            sleepMillis = {
                nowNanos += it * 1_000_000
                polls++
                onPoll()
            },
        )
    }

    @Test fun matchingNameDoesNotRequestAnotherRename() {
        val radio = Radio().apply { name = "HondaHFT" }
        assertFalse(radio.synchronize())
        assertEquals(0, radio.requests)
        assertEquals(0, radio.polls)
    }

    @Test fun waitsForTheActualNameRatherThanJustTheAcceptedRequest() {
        val radio = Radio().apply { onPoll = { if (polls == 2) name = "HondaHFT" } }
        assertTrue(radio.synchronize())
        assertEquals(1, radio.requests)
        assertEquals(2, radio.polls)
    }

    @Test fun rejectedRenameStopsBeforeAnyWaiting() {
        val radio = Radio().apply { accepted = false }
        assertTrue(assertThrows(IOException::class.java) { radio.synchronize() }.message!!.contains("rejected"))
        assertEquals(0, radio.polls)
    }

    @Test fun aConcurrentSuccessfulRenameCanSatisfyARejectedRequest() {
        val radio = Radio().apply {
            accepted = false
            onRequest = { name = "HondaHFT" }
        }
        assertTrue(radio.synchronize())
        assertEquals(0, radio.polls)
    }

    @Test fun anAcceptedRequestThatNeverUpdatesTheNameTimesOut() {
        val radio = Radio()
        assertTrue(assertThrows(IOException::class.java) { radio.synchronize() }.message!!.contains("timed out"))
        assertEquals(250_000_000L, radio.nowNanos)
        assertEquals(1, radio.requests)
    }

    @Test fun cancellationPreventsChangingTheRadio() {
        val radio = Radio().apply { cancelled = true }
        assertThrows(InterruptedException::class.java) { radio.synchronize() }
        assertEquals(0, radio.requests)
    }

    @Test fun cancellationDuringTheRenameStopsWaiting() {
        val radio = Radio().apply { onPoll = { cancelled = true } }
        assertThrows(InterruptedException::class.java) { radio.synchronize() }
        assertEquals(1, radio.polls)
    }

    @Test fun disablingBluetoothDuringTheRenameStopsWaiting() {
        val radio = Radio().apply { onPoll = { enabled = false } }
        assertThrows(IOException::class.java) { radio.synchronize() }
        assertEquals(1, radio.polls)
    }

    @Test fun anInterruptedWaitPreservesTheThreadInterrupt() {
        try {
            assertThrows(InterruptedException::class.java) {
                BluetoothLocalName.ensure(
                    expected = "HondaHFT",
                    currentName = { "Honda HFT" },
                    requestName = { true },
                    enabled = { true },
                    sleepMillis = { throw InterruptedException() },
                )
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
