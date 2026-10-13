package com.shilapi.xcertplay.compat

import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * One audio focus request using the stream-based API, present on every Android version
 * (deprecated on 26+ but still functional). A single legacy path means this class has no typed
 * references to [android.media.AudioAttributes] or [android.media.AudioFocusRequest], both absent
 * below API 21 / 26, so it loads on Dalvik (API 17).
 */
class AudioFocusRequestCompat(
    val gain: Int,
    private val streamType: Int,
    private val listener: AudioManager.OnAudioFocusChangeListener,
    private val handler: Handler,
) {
    private val legacyListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (Looper.myLooper() == handler.looper) listener.onAudioFocusChange(change)
        else handler.post { listener.onAudioFocusChange(change) }
    }

    @Suppress("DEPRECATION")
    fun request(manager: AudioManager): Int =
        manager.requestAudioFocus(legacyListener, streamType, gain)

    @Suppress("DEPRECATION")
    fun abandon(manager: AudioManager): Int =
        manager.abandonAudioFocus(legacyListener)
}