package com.shilapi.xcertplay.compat

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
class AudioFocusRequestCompatTest {
    private val manager get() =
        RuntimeEnvironment.getApplication().getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val changes = mutableListOf<Int>()
    private val request = AudioFocusRequestCompat(
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        AudioManager.STREAM_VOICE_CALL,
        { change -> changes += change },
        Handler(Looper.getMainLooper()),
    )

    @Test fun requestsFocusOnTheGivenStreamTypeAndDeliversCallbacks() {
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, request.request(manager))
        val sent = shadowOf(manager).lastAudioFocusRequest
        assertEquals(AudioManager.STREAM_VOICE_CALL, sent.streamType)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, sent.durationHint)
        sent.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(listOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT), changes)

        request.abandon(manager)
        assertSame(sent.listener, shadowOf(manager).lastAbandonedAudioFocusListener)
    }
}