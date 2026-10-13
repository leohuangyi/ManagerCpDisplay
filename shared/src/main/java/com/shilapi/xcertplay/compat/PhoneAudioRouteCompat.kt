package com.shilapi.xcertplay.compat

import android.os.Build

/**
 * Version-safe access to the BYD phone-audio routing.
 *
 * The full route (Telecom self-managed call, Android 8 recording callbacks, AudioPlaybackConfiguration)
 * depends on API 21+/24+/25+ classes that reject on Dalvik (API 17) during whole-class verification. The
 * media path must not reference those classes as types, so this helper loads the route object reflectively
 * and only on API 21+. Below that, Telecom cannot take over the call echo, so DiPlay's own echo canceller
 * and platform effects stay active, which is the correct fallback.
 */
object PhoneAudioRouteCompat {
    fun carPlayCallsUseCarAudio(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        return try {
            val cls = Class.forName("com.shilapi.xcertplay.telecom.PhoneAudioRoute")
            val instance = cls.getField("INSTANCE").get(null)
            cls.getMethod("carPlayCallsUseCarAudio").invoke(instance) as Boolean
        } catch (_: Throwable) {
            false
        }
    }
}