package com.shilapi.mgxcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.shilapi.xcertplay.transport.BluetoothHandsFreeProfileApi
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Optional native profile control, discovered from the device's own framework APIs. */
internal object LegacyBluetoothProfiles {
    enum class Role { HANDS_FREE, A2DP }

    class Handle(
        val id: Int,
        val proxy: BluetoothProfile,
        private val handsFreeApi: BluetoothHandsFreeProfileApi? = null,
    ) {
        fun connect(device: BluetoothDevice): Boolean = handsFreeApi?.connect(proxy, device)
            ?: (proxy.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(proxy, device) == true)

        fun disconnect(device: BluetoothDevice): Boolean = handsFreeApi?.disconnect(proxy, device)
            ?: (proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java).invoke(proxy, device) == true)
    }

    private val handsFreeApis by lazy {
        BluetoothHandsFreeProfileApi.discover(
            BluetoothProfile::class.java, BluetoothProfile::class.java, BluetoothDevice::class.java,
        )
    }
    private val preferredHandsFreeByDevice = ConcurrentHashMap<String, Int>()

    fun connectedPhoneDevices(context: Context, log: (String) -> Unit): List<BluetoothDevice> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        val connected = ArrayList<BluetoothDevice>()
        val observedHandsFreeDevices = HashSet<String>()
        for (api in handsFreeApis) {
            withProxy(context, adapter, api.profileId, log) { proxy ->
                if (api.proxyClass.isInstance(proxy)) {
                    proxy.connectedDevices.forEach { device ->
                        if (observedHandsFreeDevices.add(device.address)) {
                            preferredHandsFreeByDevice[device.address] = api.profileId
                        }
                        connected += device
                    }
                }
            }
        }
        withProxy(context, adapter, BluetoothProfile.A2DP, log) { connected += it.connectedDevices }
        return connected.distinctBy { it.address }
    }

    fun <T> withProfile(
        context: Context,
        role: Role,
        device: BluetoothDevice,
        log: (String) -> Unit,
        action: (Handle) -> T,
    ): T? {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        if (role == Role.A2DP) {
            return withProxy(context, adapter, BluetoothProfile.A2DP, log) {
                action(Handle(BluetoothProfile.A2DP, it))
            }
        }
        val preferred = preferredHandsFreeByDevice[device.address]
        for (api in handsFreeApis.sortedBy { if (it.profileId == preferred) 0 else 1 }) {
            var supported = false
            val result = withProxy(context, adapter, api.profileId, log) { proxy ->
                if (!api.proxyClass.isInstance(proxy)) return@withProxy null
                supported = true
                preferredHandsFreeByDevice[device.address] = api.profileId
                action(Handle(api.profileId, proxy, api))
            }
            if (supported) return result
        }
        log("Native hands-free client API unavailable; leaving phone profiles to the system")
        return null
    }

    private fun <T> withProxy(
        context: Context,
        adapter: BluetoothAdapter,
        profileId: Int,
        log: (String) -> Unit,
        action: (BluetoothProfile) -> T,
    ): T? {
        val latch = CountDownLatch(1)
        val lock = Any()
        var finished = false
        var retainedProxy: BluetoothProfile? = null
        val closedProxies = IdentityHashMap<BluetoothProfile, Boolean>()
        fun closeOnce(proxy: BluetoothProfile) {
            val close = synchronized(lock) { closedProxies.put(proxy, true) != true }
            if (close) runCatching { adapter.closeProfileProxy(profileId, proxy) }
        }
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(connectedProfile: Int, proxy: BluetoothProfile) {
                val retained = synchronized(lock) {
                    if (finished || connectedProfile != profileId ||
                        (retainedProxy != null && retainedProxy !== proxy)) false else {
                        retainedProxy = proxy
                        latch.countDown()
                        true
                    }
                }
                if (!retained) closeOnce(proxy)
            }

            override fun onServiceDisconnected(disconnectedProfile: Int) { latch.countDown() }
        }
        try {
            if (!adapter.getProfileProxy(context.applicationContext, listener, profileId)) return null
            if (!latch.await(3, TimeUnit.SECONDS)) return null
            val proxy = synchronized(lock) { retainedProxy } ?: return null
            return action(proxy)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (error: Exception) {
            log("Bluetooth profile $profileId unavailable: ${error.javaClass.simpleName}")
            return null
        } catch (error: LinkageError) {
            log("Bluetooth profile $profileId API unavailable: ${error.javaClass.simpleName}")
            return null
        } finally {
            val proxy = synchronized(lock) {
                finished = true
                retainedProxy.also { retainedProxy = null }
            }
            if (proxy != null) closeOnce(proxy)
        }
    }
}
