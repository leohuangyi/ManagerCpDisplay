package com.shilapi.mgxcertplay

import android.content.Context

/**
 * Settings of the legacy port, stored in their own preferences file. Mirroring the
 * reference project's settings (the DiPlayActivity groups), trimmed to what the legacy
 * stack can actually obey:
 *
 *  - [LegacyWirelessController.prepareSession] takes fps, right-hand drive and HEVC,
 *  - [LegacyCarPlayActivity] takes auto-connect.
 */
object LegacySettings {
    private const val PREFS = "legacy_settings"

    /** Same file [LegacyWirelessController.loadPairings] keeps the paired devices in. */
    private const val PAIRINGS_PREFS = "receiver-pairings"

    private const val KEY_AUTO_CONNECT = "auto_connect"
    private const val KEY_FPS = "fps"
    private const val KEY_RIGHT_HAND_DRIVE = "right_hand_drive"
    private const val KEY_HEVC = "hevc"
    private const val KEY_LAST_DEVICE = "last_device"

    fun autoConnect(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_CONNECT, true)

    fun setAutoConnect(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_CONNECT, enabled).commit()
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

    /** The Bluetooth address of the iPhone that last ran a CarPlay session, used as the tier-2
     *  fallback device on the next cold start. Null until the first session is booted. */
    fun lastDeviceAddress(context: Context): String? =
        prefs(context).getString(KEY_LAST_DEVICE, null)

    fun setLastDeviceAddress(context: Context, address: String) {
        prefs(context).edit().putString(KEY_LAST_DEVICE, address).commit()
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
}