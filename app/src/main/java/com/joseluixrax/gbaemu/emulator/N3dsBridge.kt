package com.joseluixrax.gbaemu.emulator

import android.view.Surface

/**
 * Kotlin side of `app/src/main/cpp/n3ds/n3ds_android.cpp`: the 3DS game runs
 * on a native thread of its own (Azahar's libretro core); these calls hand it
 * work and input and are safe from any thread.
 */
object N3dsBridge {
    /** False on phones without the 3DS library (it's built for 64-bit ARM only). */
    val available: Boolean = try {
        System.loadLibrary("n3ds")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    // n3ds_rom.h
    const val KIND_UNKNOWN = 0
    const val KIND_CIA = 4
    const val PROBLEM_NONE = 0
    const val PROBLEM_ENCRYPTED = 1
    const val PROBLEM_NOT_A_GAME = 2
    const val PROBLEM_DAMAGED = 3

    /** libretro joypad bits (RETRO_DEVICE_ID_JOYPAD_*). */
    object Retro {
        const val B = 1 shl 0
        const val Y = 1 shl 1
        const val SELECT = 1 shl 2
        const val START = 1 shl 3
        const val UP = 1 shl 4
        const val DOWN = 1 shl 5
        const val LEFT = 1 shl 6
        const val RIGHT = 1 shl 7
        const val A = 1 shl 8
        const val X = 1 shl 9
        const val L = 1 shl 10
        const val R = 1 shl 11
        const val ZL = 1 shl 12
        const val ZR = 1 shl 13
        const val HOME = 1 shl 14 // L3: Azahar's "Home" button
    }

    /**
     * Loads the core and the game (can take a few seconds). [gamePath] is the
     * name the core sees; its contents are [size] bytes of [fd] from [offset].
     * [options] alternates core option keys and values. Returns "" when the
     * game is running (paused), otherwise why it couldn't start.
     */
    external fun start(
        systemDir: String,
        saveDir: String,
        gamePath: String,
        fd: Int,
        offset: Long,
        size: Long,
        options: Array<String>,
    ): String

    external fun stop()
    external fun setSurface(surface: Surface?)

    /** Where to draw the game in the surface, in pixels from the top-left. */
    external fun setViewport(x: Int, y: Int, width: Int, height: Int)
    external fun setPaused(paused: Boolean)

    /** Above 1 fast forward (no sound), below 1 slow motion. */
    external fun setSpeed(speed: Float)

    /**
     * [buttons] and [turbo]: [Retro] bits (turbo ones are pressed and released
     * over and over). Sticks -32768..32767 (circle pad, C-stick). The pointer
     * is libretro's -32767..32767 over the whole game frame.
     */
    external fun setInput(
        buttons: Int,
        turbo: Int,
        lx: Int,
        ly: Int,
        rx: Int,
        ry: Int,
        pointerX: Int,
        pointerY: Int,
        pressed: Boolean,
    )

    /** 0..1 (0 = muted, the game still runs at its pace). */
    external fun setVolume(volume: Float)

    /** Frames a turbo button stays down, and then up. */
    external fun setTurboSpeed(halfPeriod: Int)

    /** (width shl 16) or height of the frame the core draws, 0 before the first one. */
    external fun frameSize(): Int
    external fun fps(): Float
    external fun saveState(path: String): Boolean
    external fun loadState(path: String): Boolean
    external fun reset()
    external fun setOption(key: String, value: String)

    /** [kind, problem, offset, size] of a ROM file. */
    external fun inspectRom(fd: Int, fileSize: Long): LongArray
}
