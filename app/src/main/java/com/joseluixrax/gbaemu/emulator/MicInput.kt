package com.joseluixrax.gbaemu.emulator

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Records the phone's microphone for DS games and hands the samples to the
 * core as they come. It only runs while a game is listening (GameActivity
 * decides) and nothing is kept: the samples go straight to the game.
 */
class MicInput(private val context: Context) {
    private var thread: Thread? = null
    @Volatile private var running = false

    val isRunning: Boolean get() = thread != null

    fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun start() {
        if (thread != null || !hasPermission()) return
        running = true
        thread = Thread({ record() }, "Microphone").also { it.start() }
    }

    @Synchronized
    fun stop() {
        val t = thread ?: return
        running = false
        try {
            // read() returns after one small chunk (~5 ms).
            t.join(1000)
        } catch (_: InterruptedException) {
        }
        thread = null
    }

    // The permission is checked in start(); a revoked one ends in the catch.
    @SuppressLint("MissingPermission")
    private fun record() {
        val minBytes = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) return
        val recorder = try {
            // VOICE_RECOGNITION: the least processed input, so a blow sounds like one.
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBytes, CHUNK * 2 * 8),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open the microphone", e)
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return
        }
        val buffer = ShortArray(CHUNK)
        try {
            recorder.startRecording()
            NdsBridge.setMicLive(true)
            while (running) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read < 0) break
                if (read > 0) NdsBridge.pushMic(buffer, read)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Microphone stopped", e)
        } finally {
            NdsBridge.setMicLive(false)
            try {
                recorder.stop()
            } catch (_: IllegalStateException) {
            }
            recorder.release()
        }
    }

    private companion object {
        const val TAG = "MicInput"

        /** Close to the DS mic's 47.6 kHz; the core drops the small surplus. */
        const val RATE = 48000

        /** ~5 ms per read. */
        const val CHUNK = 256
    }
}
