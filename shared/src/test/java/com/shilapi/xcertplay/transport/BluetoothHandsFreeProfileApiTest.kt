package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothHandsFreeProfileApiTest {
    @Test fun discoversRuntimeIdsAndBothSupportedConnectSignatures() {
        val apis = discover(RuntimeConstants::class.java) { name ->
            when (name) {
                "android.bluetooth.BluetoothHeadsetHf" -> VendorHandsFree::class.java
                "android.bluetooth.BluetoothHeadsetClient" -> ClientHandsFree::class.java
                else -> throw ClassNotFoundException(name)
            }
        }
        assertEquals(listOf(203, 77), apis.map { it.profileId })
        val device = Device()
        val vendor = VendorHandsFree()
        assertTrue(apis[0].connect(vendor, device))
        assertTrue(vendor.repairAllowed)
        assertTrue(apis[0].disconnect(vendor, device))
        val client = ClientHandsFree()
        assertTrue(apis[1].connect(client, device))
        assertEquals(1, client.connectCalls)
    }

    @Test fun headsetAudioGatewayIsNotUsedAsHandsFreeFallback() {
        val loaded = ArrayList<String>()
        assertTrue(discover(AudioGatewayOnlyConstants::class.java) {
            loaded += it
            VendorHandsFree::class.java
        }.isEmpty())
        assertTrue(loaded.isEmpty())
    }

    @Test fun missingOrIncompatibleApiDoesNotAdvertiseManualControl() {
        assertTrue(discover(RuntimeConstants::class.java) { throw ClassNotFoundException(it) }.isEmpty())
        assertTrue(discover(RuntimeConstants::class.java) { String::class.java }.isEmpty())
        assertTrue(discover(InvalidConstants::class.java) { VendorHandsFree::class.java }.isEmpty())
        assertTrue(discover(RuntimeConstants::class.java) { InvalidConnectResult::class.java }.isEmpty())
    }

    @Test fun booleanConnectRejectionIsPreserved() {
        val api = discover(RuntimeConstants::class.java) { RejectingHandsFree::class.java }.first()
        assertFalse(api.connect(RejectingHandsFree(), Device()))
    }

    private fun discover(constants: Class<*>, load: (String) -> Class<*>) =
        BluetoothHandsFreeProfileApi.discover(constants, Profile::class.java, Device::class.java, load)

    class RuntimeConstants {
        companion object {
            @JvmField val HEADSET_HF = 203
            @JvmField val HEADSET_CLIENT = 77
        }
    }
    class AudioGatewayOnlyConstants { companion object { @JvmField val HEADSET = 1 } }
    class InvalidConstants { companion object { @JvmField val HEADSET_HF = "not an integer" } }
    class Device
    interface Profile {
        fun getConnectedDevices(): List<Device>
        fun getConnectionState(device: Device): Int
    }
    open class BaseHandsFree : Profile {
        override fun getConnectedDevices(): List<Device> = emptyList()
        override fun getConnectionState(device: Device): Int = 0
        fun disconnect(device: Device): Boolean = true
    }
    class VendorHandsFree : BaseHandsFree() {
        var repairAllowed = false
        fun connect(device: Device, allowRepair: Boolean): Boolean {
            repairAllowed = allowRepair
            return true
        }
    }
    class ClientHandsFree : BaseHandsFree() {
        var connectCalls = 0
        fun connect(device: Device): Boolean { connectCalls++; return true }
    }
    class RejectingHandsFree : BaseHandsFree() { fun connect(device: Device): Boolean = false }
    class InvalidConnectResult : BaseHandsFree() { fun connect(device: Device): String = "not boolean" }
}
