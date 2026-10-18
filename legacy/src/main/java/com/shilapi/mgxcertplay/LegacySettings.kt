package com.shilapi.mgxcertplay

import android.content.Context

/**
 * Settings of the legacy port, stored in their own preferences file. Mirroring the
 * reference project's settings (the DiPlayActivity groups), trimmed to what the legacy
 * stack can actually obey:
 *
 *  - [LegacyWirelessHost] takes the network mode,
 *  - [LegacyWirelessController.prepareSession] takes fps, right-hand drive and HEVC,
 *  - [LegacyCarPlayActivity] takes auto-connect,
 *  - the router password lives in the "receiver-ui" file under "routerPassword", the same
 *    key [LegacyNetwork.prepareRouter] already reads.
 */
object LegacySettings {
    private const val PREFS = "legacy_settings"

    /** Same file [LegacyWirelessController.loadPairings] keeps the paired devices in. */
    private const val PAIRINGS_PREFS = "receiver-pairings"

    private const val KEY_AUTO_CONNECT = "auto_connect"
    private const val KEY_NETWORK_MODE = "network_mode"
    private const val KEY_FPS = "fps"
    private const val KEY_RIGHT_HAND_DRIVE = "right_hand_drive"
    private const val KEY_HEVC = "hevc"

    /** The network modes [LegacyNetwork] understands; "app"/"saved" are broken on this firmware. */
    val networkModes: List<String> = listOf("auto", "direct", "router", "reuse")

    fun autoConnect(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_CONNECT, true)

    fun setAutoConnect(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_CONNECT, enabled).commit()
    }

    fun networkMode(context: Context): String {
        val stored = prefs(context).getString(KEY_NETWORK_MODE, "auto") ?: "auto"
        return if (stored in networkModes) stored else "auto"
    }

    fun setNetworkMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_NETWORK_MODE, mode).commit()
    }

    fun fps(context: Context): Int {
        val stored = prefs(context).getInt(KEY_FPS, 30)
        return if (stored == 60) 60 else 30
    }

    fun setFps(context: Context, fps: Int) {
        prefs(context).edit().putInt(KEY_FPS, if (fps == 60) 60 else 30).commit()
    }

    fun rightHandDrive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RIGHT_HAND_DRIVE, false)

    fun setRightHandDrive(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_RIGHT_HAND_DRIVE, enabled).commit()
    }

    fun hevc(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HEVC, false)

    fun setHevc(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HEVC, enabled).commit()
    }

    fun routerPassword(context: Context): String =
        routerPrefs(context).getString("routerPassword", "") ?: ""

    fun setRouterPassword(context: Context, password: String) {
        routerPrefs(context).edit().putString("routerPassword", password).commit()
    }

    /** One paired iPhone. The prefs key is the identifier the iPhone sent during pairing;
     *  no friendly name is stored, so the label is its printable part or a short hex digest. */
    data class PairedDevice(val key: String, val label: String)

    /** The devices [LegacyWirelessController.loadPairings] would trust on the next connection. */
    fun pairedDevices(context: Context): List<PairedDevice> =
        pairingsPrefs(context).all
            .filterValues { it is String }
            .map { (key, _) -> PairedDevice(key, deviceLabel(key)) }
            .sortedBy { it.key }

    /** Forgotten devices only leave the running session untouched; the next connection
     *  (which replays this file) drops them. */
    fun deletePairedDevice(context: Context, key: String) {
        pairingsPrefs(context).edit().remove(key).commit()
    }

    private fun pairingsPrefs(context: Context) =
        context.getSharedPreferences(PAIRINGS_PREFS, Context.MODE_PRIVATE)

    private fun deviceLabel(key: String): String {
        val clean = key.all { !Character.isISOControl(it) && !Character.isWhitespace(it) }
        if (!clean) {
            return "设备 " + key.toByteArray(Charsets.UTF_8).take(8)
                .joinToString("") { "%02X".format(it) }
        }
        return if (key.length > 24) key.take(24) + "…" else key
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun routerPrefs(context: Context) =
        context.getSharedPreferences("receiver-ui", Context.MODE_PRIVATE)
}