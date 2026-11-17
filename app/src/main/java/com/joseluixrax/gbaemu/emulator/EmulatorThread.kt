package com.joseluixrax.gbaemu.emulator

import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.LockSupport
import kotlin.math.roundToInt

/**
 * Runs the emulator: one loop iteration = one emulated frame.
 *
 * Pacing:
 *  - Normal speed with sound: the blocking audio write sets the pace
 *    (the GBA produces exactly one second of audio per emulated second).
 *  - Muted, fast forward, rewind: a timer at the console's refresh rate
 *    (59.73 Hz on GBA, 59.83 Hz on DS).
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

        /** The game started or stopped listening to the microphone (DS). */
        fun onMicWanted(wanted: Boolean) {}
    }

    /** The core being run. Set it on this thread, right after loading a game. */
    @Volatile var core: EmuCore = GbaCore
        set(value) {
            field = value
            coreAudioSpeed = 1f
        }

    @Volatile var keys = 0

    /** Finger on the DS touch screen ([EmuCore.packTouch]), or [EmuCore.NO_TOUCH]. */
    @Volatile var touch = EmuCore.NO_TOUCH
    @Volatile var fastForward = false

    /**
     * Speed while the » button is on: above 1 fast forward, below 1 slow
     * motion (0.5 = half speed), 0 means "as fast as possible".
     */
    @Volatile var fastForwardSpeed = 4f
    @Volatile var rewinding = false
    @Volatile var audioEnabled = true

    /** Play sound while fast forwarding / in slow motion, at the game's speed. */
    @Volatile var fastForwardAudio = true

    @Volatile private var paused = true
    @Volatile private var quitRequested = false
    private val tasks = ConcurrentLinkedQueue<Runnable>()
    private val monitor = Object()
    // x0.1 slow motion: ten frames' worth of audio per frame, with room to spare.
    private val audioBuffer = ShortArray(16384 * 2)

    /** Audio speed currently set in the core (1 = normal). */
    private var coreAudioSpeed = 1f

    private var micWanted = false

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
                if (core.pollRumble()) callbacks.onRumble()
                val wantsMic = core.micWanted()
                if (wantsMic != micWanted) {
                    micWanted = wantsMic
                    callbacks.onMicWanted(wantsMic)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Emulation error", e)
                paused = true
            }
        }
        audio.pause()
    }

    private fun stepNormal(nextFrameTime: Long): Long {
        setCoreAudioSpeed(1f)
        core.setInput(keys, touch)
        core.runFrame()
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
        if (speed <= 0f) {
            // Unlimited: as fast as the phone can go, there is no speed to play the music at.
            setCoreAudioSpeed(1f)
            core.setInput(keys, touch)
            core.runFrame()
            core.discardAudio()
            publishFrame(1)
            return System.nanoTime()
        }
        if (speed < 1f) return stepSlowMotion(speed, nextFrameTime)
        val frames = speed.roundToInt()
        val withSound = audioEnabled && fastForwardAudio
        setCoreAudioSpeed(if (withSound) frames.toFloat() else 1f)
        core.setInput(keys, touch)
        repeat(frames) { core.runFrame() }
        publishFrame(frames)
        return playAudioOrPace(withSound, nextFrameTime)
    }

    /**
     * One emulated frame every 1/[speed] screen refreshes. With sound on, the
     * core stretches the frame's audio by the same factor (lower and slower
     * music), and writing it takes exactly as long as the frame should last.
     */
    private fun stepSlowMotion(speed: Float, nextFrameTime: Long): Long {
        val withSound = audioEnabled && fastForwardAudio
        setCoreAudioSpeed(if (withSound) speed else 1f)
        core.setInput(keys, touch)
        core.runFrame()
        publishFrame(1)
        return playAudioOrPace(withSound, nextFrameTime, (core.frameNanos / speed).toLong())
    }

    /** Writes this step's audio (blocking = real-time pacing), or sleeps if there is none. */
    private fun playAudioOrPace(withSound: Boolean, nextFrameTime: Long, period: Long = core.frameNanos): Long {
        if (withSound) {
            val frames = core.readAudio(audioBuffer)
            if (frames > 0 && audio.write(audioBuffer, frames)) {
                return System.nanoTime()
            }
        } else {
            core.discardAudio()
        }
        // No audio output to wait on: keep time ourselves.
        return pace(nextFrameTime, period)
    }

    private fun setCoreAudioSpeed(speed: Float) {
        if (speed != coreAudioSpeed) {
            core.setAudioSpeed(speed)
            coreAudioSpeed = speed
        }
    }

    private fun stepRewind(nextFrameTime: Long): Long {
        setCoreAudioSpeed(1f)
        core.setInput(0, EmuCore.NO_TOUCH)
        if (core.rewindStep()) {
            publishFrame(1)
        }
        core.discardAudio()
        // One snapshot per half interval: twice the normal speed, backwards.
        return pace(nextFrameTime, core.frameNanos * core.rewindInterval / 2)
    }

    private fun publishFrame(emulatedFrames: Int) {
        if (frameBuffer.captureFromCore(core)) {
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
    private fun pace(previous: Long, period: Long = core.frameNanos): Long {
        val now = System.nanoTime()
        var target = previous + period
        if (target < now - period * 3) target = now
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
    }
}
