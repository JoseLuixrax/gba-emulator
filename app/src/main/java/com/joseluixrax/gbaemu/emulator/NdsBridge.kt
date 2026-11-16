package com.joseluixrax.gbaemu.emulator

import java.nio.ByteBuffer
import java.util.Locale

/**
 * Kotlin side of `app/src/main/cpp/nds/jni_nds.cpp` (the melonDS core).
 *
 * Same rules as [NativeBridge]: one global instance, call it only from
 * [EmulatorThread].
 */
object NdsBridge {
    /**
     * False when the phone has no DS library: it is only built for 64-bit
     * processors, older 32-bit phones would be far too slow anyway.
     */
    val available: Boolean = try {
        System.loadLibrary("ndsemu")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    const val SCREEN_WIDTH = 256
    const val SCREEN_HEIGHT = 192

    /** Firmware language for the games, from the phone's language. English otherwise. */
    fun languageFor(locale: Locale): Int = when (locale.language) {
        "ja" -> 0
        "fr" -> 2
        "de" -> 3
        "it" -> 4
        "es" -> 5
        else -> 1
    }

    /** Reads the ROM from [fd] (left open), so big ROMs never go through the Java heap. */
    external fun loadRomFd(
        fd: Int,
        savePath: String?,
        dataDir: String?,
        sampleRate: Int,
        language: Int,
        useJit: Boolean,
    ): Boolean

    external fun unload()
    external fun reset()
    external fun runFrame()

    /** [keys]: [GbaKeys] + [DsKeys] bits. [touch]: [EmuCore.packTouch] or [EmuCore.NO_TOUCH]. */
    external fun setInput(keys: Int, touch: Int)

    /** Both screens, top then bottom (256x384 RGBA). Returns (width shl 16) or height. */
    external fun copyFrame(buffer: ByteBuffer): Int
    external fun readAudio(buffer: ShortArray): Int
    external fun discardAudio()
    external fun setAudioSpeed(speed: Float)

    /** Writes the battery save to disk now if the game changed it. */
    external fun flushSave()
    external fun gameTitle(): String
}
