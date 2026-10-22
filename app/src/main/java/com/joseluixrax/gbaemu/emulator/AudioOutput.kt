package com.joseluixrax.gbaemu.emulator

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log

/**
 * Streams the emulator's 16-bit stereo audio to an [AudioTrack].
 *
 * Writes are blocking: when the buffer is full the emulator thread waits,
 * which is exactly what keeps the game running at its real speed.
 */
class AudioOutput(context: Context) {
    /**
     * The device's native rate, so Android doesn't have to resample. Capped at
     * 48 kHz: x0.1 slow motion produces ten times the samples per frame and
     * the core can't buffer more than that.
     */
    val sampleRate: Int = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        ?.toIntOrNull()
        ?.takeIf { it in 22050..48000 }
        ?: 48000

    private var track: AudioTrack? = null
    private var volume = 1f

    @Synchronized
    fun start() {
        if (track != null) return
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        )
        // About four video frames of audio: low latency without crackling.
        val fourFramesBytes = sampleRate / 60 * 4 * 4
        val bufferBytes = maxOf(minBytes, fourFramesBytes)
        try {
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            track = builder.build().also {
                it.setVolume(volume)
                it.play()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open audio output", e)
            track = null
        }
    }

    /** Emulator thread. Returns false if there is no audio device. */
    fun write(samples: ShortArray, frames: Int): Boolean {
        val t = track ?: return false
        return t.write(samples, 0, frames * 2, AudioTrack.WRITE_BLOCKING) >= 0
    }

    @Synchronized
    fun pause() {
        track?.let {
            it.pause()
            it.flush()
        }
    }

    @Synchronized
    fun resume() {
        track?.play()
    }

    @Synchronized
    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        track?.setVolume(volume)
    }

    @Synchronized
    fun release() {
        track?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        track = null
    }

    private companion object {
        const val TAG = "AudioOutput"
    }
}
