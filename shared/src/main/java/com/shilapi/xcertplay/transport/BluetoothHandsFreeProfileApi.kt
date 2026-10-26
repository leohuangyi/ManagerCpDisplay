package com.shilapi.xcertplay.transport

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** A hands-free/client API advertised by the running framework, rather than a car-model table. */
data class BluetoothHandsFreeProfileApi(
    val profileId: Int,
    val proxyClass: Class<*>,
    private val connectMethod: Method,
    private val disconnectMethod: Method,
) {
    fun connect(proxy: Any, device: Any): Boolean = if (connectMethod.parameterTypes.size == 2) {
        connectMethod.invoke(proxy, device, true) == true
    } else {
        connectMethod.invoke(proxy, device) == true
    }

    fun disconnect(proxy: Any, device: Any): Boolean = disconnectMethod.invoke(proxy, device) == true

    companion object {
        /** HEADSET is the audio-gateway role and must never be substituted for a missing HF API. */
        fun discover(
            profileConstants: Class<*>,
            profileInterface: Class<*>,
            deviceClass: Class<*>,
            loadClass: (String) -> Class<*> = { Class.forName(it) },
        ): List<BluetoothHandsFreeProfileApi> = listOf(
            "HEADSET_HF" to "android.bluetooth.BluetoothHeadsetHf",
            "HEADSET_CLIENT" to "android.bluetooth.BluetoothHeadsetClient",
        ).mapNotNull { (fieldName, className) ->
            try {
                val field = profileConstants.getField(fieldName)
                if (field.type != Integer.TYPE || !Modifier.isStatic(field.modifiers)) return@mapNotNull null
                val profileId = field.getInt(null)
                val api = loadClass(className)
                if (profileId <= 0 || !Modifier.isPublic(api.modifiers) ||
                    !profileInterface.isAssignableFrom(api)) return@mapNotNull null
                if (!List::class.java.isAssignableFrom(api.getMethod("getConnectedDevices").returnType) ||
                    api.getMethod("getConnectionState", deviceClass).returnType != Integer.TYPE) {
                    return@mapNotNull null
                }
                val disconnect = api.getMethod("disconnect", deviceClass)
                val connect = try {
                    api.getMethod("connect", deviceClass, java.lang.Boolean.TYPE)
                } catch (_: NoSuchMethodException) {
                    api.getMethod("connect", deviceClass)
                }
                if (connect.returnType != java.lang.Boolean.TYPE ||
                    disconnect.returnType != java.lang.Boolean.TYPE) return@mapNotNull null
                BluetoothHandsFreeProfileApi(profileId, api, connect, disconnect)
            } catch (_: Exception) {
                null
            } catch (_: LinkageError) {
                null
            }
        }.distinctBy { it.profileId }
    }
}
