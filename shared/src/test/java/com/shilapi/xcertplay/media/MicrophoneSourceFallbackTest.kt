package com.shilapi.xcertplay.media

import android.Manifest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioEffect
import org.robolectric.shadows.ShadowAudioRecord

/** Exercises the real sink/uplink, including creation, teardown and exported diagnostics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class MicrophoneSourceFallbackTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val id = AudioStreamId(100, "speechrecognition")
    private val diagnostics = CopyOnWriteArrayList<String>()
    private val captured = AtomicReference<AudioRecord?>()
    private val captureStarted = CountDownLatch(1)
    private val attempts = CopyOnWriteArrayList<Int>()
    private val built = CopyOnWriteArrayList<AudioRecord>()
    private var rejectSources = emptySet<Int>()
    private var uninitializedSources = emptySet<Int>()
    private lateinit var sink: AndroidMediaSink

    @Before fun setUp() {
        attempts.clear()
        built.clear()
        rejectSources = emptySet()
        uninitializedSources = emptySet()
        AudioRecorderFactory.create = { source, sampleRate, channelMask, encoding, bufferSize ->
            attempts += source
            if (source in rejectSources) throw UnsupportedOperationException("Unsupported test capture source")
            (if (source in uninitializedSources) {
                RejectedRecorder(source, sampleRate, channelMask, encoding, bufferSize)
            } else {
                AudioRecord(source, sampleRate, channelMask, encoding, bufferSize)
            }).also { built += it }
        }
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS)
        context.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_RINGTONE
        sink = AndroidMediaSink(context = context, onAudioDiagnostic = diagnostics::add)
        ShadowAudioRecord.setSourceProvider { recorder ->
            captured.set(recorder)
            captureStarted.countDown()
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                    Thread.sleep(5)
                    return 0
                }
            }
        }
    }

    @After fun tearDown() {
        sink.close()
        ShadowAudioRecord.clearSource()
        AudioRecorderFactory.reset()
    }

    @Test fun siriCreationFailureRetriesCommunicationOnceWithoutCallEffectsOrModeChanges() {
        rejectSources = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)

        sink.onMicrophoneStarted(id, config("speechrecognition"))

        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        val recorder = requireNotNull(captured.get())
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.VOICE_COMMUNICATION), attempts)
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, recorder.audioSource)
        assertEquals(AudioManager.MODE_RINGTONE, context.getSystemService(AudioManager::class.java).mode)
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
        assertTrue(diagnostics.any { it.contains("failure type=speechrecognition source=VOICE_RECOGNITION") && it.contains("stage=RECORDER_CREATION") })
        assertTrue(diagnostics.any { it.startsWith("Microphone: start type=speechrecognition source=VOICE_COMMUNICATION") })
        sink.onMicrophoneStopped(id)
        assertEquals(AudioRecord.STATE_UNINITIALIZED, recorder.state)
        assertTrue(diagnostics.any { it.contains("stats type=speechrecognition source=VOICE_COMMUNICATION") && it.endsWith("ended=true") })
    }

    @Test fun siriInitializationFailureReleasesRejectedRecorderBeforeFallbackCapture() {
        uninitializedSources = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)

        sink.onMicrophoneStarted(id, config("speechrecognition"))

        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        assertEquals(2, built.size)
        assertTrue((built.first() as RejectedRecorder).released)
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, built.last().recordingState)
        assertTrue(diagnostics.any { it.contains("source=VOICE_RECOGNITION") && it.contains("stage=RECORDER_INITIALIZATION") })
    }

    @Test fun bothUnsupportedSourcesFailOnceAndALaterStreamCanStart() {
        rejectSources = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        sink.onMicrophoneStarted(id, config("speechrecognition"))
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.VOICE_COMMUNICATION), attempts)
        assertNull(captured.get())
        assertEquals(2, diagnostics.count { it.contains("stage=RECORDER_CREATION") })
        assertTrue(diagnostics.none { it.startsWith("Microphone: start") })

        rejectSources = emptySet()
        sink.onMicrophoneStarted(id, config("speechrecognition"))
        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, requireNotNull(captured.get()).audioSource)
    }

    @Test fun telephonyCreationFailureDoesNotRetryOrLeaveTheCallModeActive() {
        rejectSources = setOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        sink.onMicrophoneStarted(AudioStreamId(101, "telephony"), config("telephony"))
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION), attempts)
        assertNull(captured.get())
        assertEquals(AudioManager.MODE_RINGTONE, context.getSystemService(AudioManager::class.java).mode)
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
    }

    private fun config(type: String) = MicrophoneConfig(audioType = type, sampleRate = 16_000,
        channels = 1, payloadType = 100, frameMillis = 20, host = InetAddress.getLoopbackAddress(),
        port = 9, key = ByteArray(32))

    class RejectedRecorder(
        source: Int,
        sampleRate: Int,
        channelMask: Int,
        encoding: Int,
        bufferSize: Int,
    ) : AudioRecord(source, sampleRate, channelMask, encoding, bufferSize) {
        var released = false
            private set
        override fun getState(): Int = STATE_UNINITIALIZED
        override fun release() {
            released = true
            super.release()
        }
    }
}