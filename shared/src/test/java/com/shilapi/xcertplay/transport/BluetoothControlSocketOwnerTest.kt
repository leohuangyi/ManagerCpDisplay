package com.shilapi.xcertplay.transport

import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothControlSocketOwnerTest {
    @Test fun earlyConnectionCannotAuthenticateUntilItsPhoneIsSelected() {
        val closed = ArrayList<Any>()
        val owner = BluetoothControlSocketOwner<Any> { closed.add(it) }
        val wrongPhone = Any()
        val selectedPhone = Any()
        assertTrue(owner.offerIncoming("other-phone", wrongPhone))
        assertNull(owner.takeIncoming())

        owner.selectPeer("selected-phone")
        assertEquals(listOf(wrongPhone), closed)
        assertTrue(owner.offerIncoming("selected-phone", selectedPhone))
        assertSame(selectedPhone, owner.takeIncoming())
        owner.close()
        assertEquals(listOf(wrongPhone, selectedPhone), closed)
    }

    @Test fun incomingCancelsPendingOutboundAndUsesTheOnlyControlSlot() {
        val closed = ArrayList<Any>()
        val owner = BluetoothControlSocketOwner<Any> { closed.add(it) }
        val outbound = Any()
        val incoming = Any()
        val duplicate = Any()
        owner.selectPeer("phone")
        assertTrue(owner.beginOutgoing("phone", outbound))
        assertTrue(owner.offerIncoming("phone", incoming))
        assertEquals(listOf(outbound), closed)
        assertFalse(owner.outgoingConnected(outbound))
        assertSame(incoming, owner.takeIncoming())
        assertFalse(owner.offerIncoming("phone", duplicate))
        assertEquals(listOf(outbound, duplicate), closed)
        owner.close()
    }

    @Test fun simultaneousInboundAndOutboundCompletionNeverClaimTwoLinks() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(50) {
                val closed = Collections.synchronizedList(ArrayList<Any>())
                val owner = BluetoothControlSocketOwner<Any> { socket -> closed.add(socket) }
                val outbound = Any()
                val incoming = Any()
                owner.selectPeer("phone")
                assertTrue(owner.beginOutgoing("phone", outbound))
                val barrier = CyclicBarrier(2)
                val outgoingWon = workers.submit<Boolean> {
                    barrier.await(5, TimeUnit.SECONDS)
                    owner.outgoingConnected(outbound)
                }
                val incomingWon = workers.submit<Boolean> {
                    barrier.await(5, TimeUnit.SECONDS)
                    owner.offerIncoming("phone", incoming)
                }
                val outboundClaimed = outgoingWon.get(5, TimeUnit.SECONDS)
                val inboundAccepted = incomingWon.get(5, TimeUnit.SECONDS)
                assertTrue("exactly one connection must win", outboundClaimed != inboundAccepted)
                if (outboundClaimed) {
                    assertNull(owner.takeIncoming())
                    assertEquals(listOf(incoming), closed)
                } else {
                    assertSame(incoming, owner.takeIncoming())
                    assertEquals(listOf(outbound), closed)
                }
                owner.close()
                assertEquals(2, closed.size)
            }
        } finally {
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test fun closeWakesPassiveIncomingWaitAndRejectsLateSockets() {
        val closed = Collections.synchronizedList(ArrayList<Any>())
        val owner = BluetoothControlSocketOwner<Any> { closed.add(it) }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val waiting = worker.submit<Boolean> { owner.awaitIncoming() }
            owner.close()
            assertFalse(waiting.get(5, TimeUnit.SECONDS))
            val late = Any()
            assertFalse(owner.offerIncoming("phone", late))
            assertEquals(listOf(late), closed)
        } finally {
            owner.close()
            worker.shutdownNow()
            worker.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
