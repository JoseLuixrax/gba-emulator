package com.joseluixrax.gbaemu.emulator

import java.nio.ByteBuffer

/**
 * Kotlin side of `app/src/main/cpp/jni_bridge.c`.
 *
 * The native core is a single global instance and is not thread safe:
 * call these functions only from [EmulatorThread] (use [EmulatorThread.post]).
 * The sensor setters are the exception, they only store a value.
 */
object NativeBridge {
    init {
        System.loadLibrary("gbaemu")
    }

    const val PLATFORM_NONE = -1
    const val PLATFORM_GBA = 0
    const val PLATFORM_GB = 1

    // Cartridge hardware flags returned by [hardware].
    const val HW_RTC = 1
    const val HW_RUMBLE = 2
    const val HW_LIGHT_SENSOR = 4
    const val HW_GYRO = 8
    const val HW_TILT = 16

    external fun loadRom(
        rom: ByteArray,
        savePath: String?,
        biosPath: String?,
        sampleRate: Int,
        skipBios: Boolean,
        frameskip: Int,
        idleLoopDetect: Boolean,
        gbColorize: Boolean,
    ): Boolean

    external fun unload()
    external fun platform(): Int
    external fun reset()
    external fun runFrame()
    external fun setKeys(keys: Int)

    /** Copies the frame (RGBA bytes) into a direct buffer. Returns (width shl 16) or height, 0 on error. */
    external fun copyFrame(buffer: ByteBuffer): Int

    /** Reads interleaved stereo samples, returns the number of stereo frames. */
    external fun readAudio(buffer: ShortArray): Int
    external fun discardAudio()

    external fun saveState(path: String): Boolean
    external fun loadState(path: String): Boolean

    external fun cheatsClear()
    external fun cheatsAdd(name: String, code: String, type: Int, enabled: Boolean): Boolean

    external fun setRewind(enabled: Boolean, capacity: Int, interval: Int)
    external fun rewindStep(): Boolean

    external fun setTilt(x: Float, y: Float)
    external fun setGyro(z: Float)
    external fun setSolarLevel(level: Int)
    external fun pollRumble(): Boolean

    external fun setFrameskip(frameskip: Int)
    external fun hardware(): Int
    external fun gameTitle(): String
    external fun gameCode(): String
}
