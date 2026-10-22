package com.joseluixrax.gbaemu.emulator

import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.LockSupport

/**
 * Runs the emulator: one loop iteration = one emulated frame.
 *
 * Pacing:
 *  - Normal speed with sound: the blocking audio write sets the pace
 *    (the GBA produces exactly one second of audio per emulated second).
 *  - Muted, fast forward, rewind: a timer at the GBA refresh rate (59.73 Hz).
 *
 * Anything that touches the native core must run on this thread, so other
 * threads send work with [post].
 */
class EmulatorThread(
    private val frameBuffer: FrameBuffer,
    private val audio: AudioOutput,
    private val callbacks: Callbacks,
) : Thread("Emulator") {

    interface Callbacks {
        /** A new frame is in the [FrameBuffer]. Called on the emulator thread. */
        fun onFrameReady()

        /** Emulated frames per second, roughly once a second. */
        fun onFpsUpdate(fps: Float)

        /** The game turned the rumble motor on during the last frame. */
        fun onRumble()
    }

    @Volatile var keys = 0
    @Volatile var fastForward = false

    /** Speed multiplier while fast forwarding; 0 means "as fast as possible". */
    @Volatile var fastForwardSpeed = 4
    @Volatile var rewinding = false
    @Volatile var audioEnabled = true

    /** Play sound while fast forwarding, sped up like the game. */
    @Volatile var fastForwardAudio = true

    @Volatile private var paused = true
    @Volatile private var quitRequested = false
    private val tasks = ConcurrentLinkedQueue<Runnable>()
    private val monitor = Object()
    private val audioBuffer = ShortArray(8192 * 2)

    /** Audio speed currently set in the core (1 = normal). */
    private var coreAudioSpeed = 1

    private var fpsFrames = 0
    private var fpsStart = 0L

    /** Runs [task] on the emulator thread, even while paused. */
    fun post(task: Runnable) {
        tasks.add(task)
        wake()
    }

    fun post(task: () -> Unit) = post(Runnable(task))

    fun setPaused(value: Boolean) {
        paused = value
        wake()
    }

    val isPaused: Boolean get() = paused

    /** Finishes queued tasks, then stops the thread. */
    fun quit() {
        post { quitRequested = true }
    }

    private fun wake() {
        synchronized(monitor) { monitor.notifyAll() }
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        var nextFrameTime = System.nanoTime()
        var audioRunning = false

        while (true) {
            runPendingTasks()
            if (quitRequested) break

            if (paused) {
                if (audioRunning) {
                    audio.pause()
                    audioRunning = false
                }
                synchronized(monitor) {
                    if (paused && tasks.isEmpty()) monitor.wait(500)
                }
                fpsStart = 0L
                continue
            }
            if (!audioRunning) {
                audio.resume()
                audioRunning = true
                nextFrameTime = System.nanoTime()
            }

            try {
                nextFrameTime = when {
                    rewinding -> stepRewind(nextFrameTime)
                    fastForward -> stepFastForward(nextFrameTime)
                    else -> stepNormal(nextFrameTime)
                }
                if (NativeBridge.pollRumble()) callbacks.onRumble()
            } catch (e: Exception) {
                Log.e(TAG, "Emulation error", e)
                paused = true
            }
        }
        audio.pause()
    }

    private fun stepNormal(nextFrameTime: Long): Long {
        setCoreAudioSpeed(1)
        NativeBridge.setKeys(keys)
        NativeBridge.runFrame()
        publishFrame(1)
        return playAudioOrPace(audioEnabled, nextFrameTime)
    }

    /**
     * Runs [fastForwardSpeed] frames per screen refresh. With sound on, the core
     * squeezes their audio into one frame's worth of samples, so the music
     * plays at the same speed as the game and the audio write sets the pace.
     */
    private fun stepFastForward(nextFrameTime: Long): Long {
        val speed = fastForwardSpeed
        if (speed <= 0) {
            // Unlimited: as fast as the phone can go, there is no speed to play the music at.
            setCoreAudioSpeed(1)
            NativeBridge.setKeys(keys)
            NativeBridge.runFrame()
            NativeBridge.discardAudio()
            publishFrame(1)
            return System.nanoTime()
        }
        val withSound = audioEnabled && fastForwardAudio
        setCoreAudioSpeed(if (withSound) speed else 1)
        NativeBridge.setKeys(keys)
        repeat(speed) { NativeBridge.runFrame() }
        publishFrame(speed)
        return playAudioOrPace(withSound, nextFrameTime)
    }

    /** Writes this step's audio (blocking = real-time pacing), or sleeps if there is none. */
    private fun playAudioOrPace(withSound: Boolean, nextFrameTime: Long): Long {
        if (withSound) {
            val frames = NativeBridge.readAudio(audioBuffer)
            if (frames > 0 && audio.write(audioBuffer, frames)) {
                return System.nanoTime()
            }
        } else {
            NativeBridge.discardAudio()
        }
        // No audio output to wait on: keep time ourselves.
        return pace(nextFrameTime)
    }

    private fun setCoreAudioSpeed(speed: Int) {
        if (speed != coreAudioSpeed) {
            NativeBridge.setAudioSpeed(speed.toFloat())
            coreAudioSpeed = speed
        }
    }

    private fun stepRewind(nextFrameTime: Long): Long {
        setCoreAudioSpeed(1)
        NativeBridge.setKeys(0)
        if (NativeBridge.rewindStep()) {
            publishFrame(1)
        }
        NativeBridge.discardAudio()
        return pace(nextFrameTime)
    }

    private fun publishFrame(emulatedFrames: Int) {
        if (frameBuffer.captureFromCore()) {
            callbacks.onFrameReady()
        }
        countFps(emulatedFrames)
    }

    private fun countFps(emulatedFrames: Int) {
        val now = SystemClock.elapsedRealtime()
        if (fpsStart == 0L) {
            fpsStart = now
            fpsFrames = 0
            return
        }
        fpsFrames += emulatedFrames
        val elapsed = now - fpsStart
        if (elapsed >= 1000) {
            callbacks.onFpsUpdate(fpsFrames * 1000f / elapsed)
            fpsStart = now
            fpsFrames = 0
        }
    }

    /** Sleeps until the next frame is due; never tries to "catch up" a backlog. */
    private fun pace(previous: Long): Long {
        val now = System.nanoTime()
        var target = previous + FRAME_NANOS
        if (target < now - FRAME_NANOS * 3) target = now
        val wait = target - now
        if (wait > 0) LockSupport.parkNanos(wait)
        return target
    }

    private fun runPendingTasks() {
        while (true) {
            val task = tasks.poll() ?: return
            try {
                task.run()
            } catch (e: Exception) {
                Log.e(TAG, "Task failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "EmulatorThread"

        /** One GBA frame: 280896 cycles at 16.78 MHz = 16.743 ms (59.73 Hz). */
        const val FRAME_NANOS = 16_742_706L
    }
}
