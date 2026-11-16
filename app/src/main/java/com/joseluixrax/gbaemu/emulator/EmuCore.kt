package com.joseluixrax.gbaemu.emulator

import java.nio.ByteBuffer

/**
 * What [EmulatorThread] needs from an emulation core, so the same loop runs
 * GBA/GB games (mGBA) and DS games (melonDS). Emulator thread only.
 */
interface EmuCore {
    /** Length of one emulated frame, for pacing when there is no sound to wait on. */
    val frameNanos: Long

    fun setInput(keys: Int, touch: Int)
    fun runFrame()

    /** Copies the frame (RGBA bytes) into a direct buffer. Returns (width shl 16) or height, 0 on error. */
    fun copyFrame(buffer: ByteBuffer): Int
    fun readAudio(buffer: ShortArray): Int
    fun discardAudio()

    /** 4 = x4 fast forward with faster music, 0.5 = half speed. 1 = normal. */
    fun setAudioSpeed(speed: Float)
    fun rewindStep(): Boolean
    fun pollRumble(): Boolean
    fun reset()
    fun unload()

    companion object {
        /** No finger on the DS touch screen. */
        const val NO_TOUCH = -1

        /** A point on the DS touch screen, in DS pixels. */
        fun packTouch(x: Int, y: Int) = (x shl 16) or y
    }
}

/** mGBA: Game Boy Advance, Game Boy and Game Boy Color. */
object GbaCore : EmuCore {
    /** One GBA frame: 280896 cycles at 16.78 MHz = 16.743 ms (59.73 Hz). */
    override val frameNanos = 16_742_706L

    // Only the ten GBA keys; the DS extras mean nothing here.
    override fun setInput(keys: Int, touch: Int) = NativeBridge.setKeys(keys and 0x3FF)
    override fun runFrame() = NativeBridge.runFrame()
    override fun copyFrame(buffer: ByteBuffer) = NativeBridge.copyFrame(buffer)
    override fun readAudio(buffer: ShortArray) = NativeBridge.readAudio(buffer)
    override fun discardAudio() = NativeBridge.discardAudio()
    override fun setAudioSpeed(speed: Float) = NativeBridge.setAudioSpeed(speed)
    override fun rewindStep() = NativeBridge.rewindStep()
    override fun pollRumble() = NativeBridge.pollRumble()
    override fun reset() = NativeBridge.reset()
    override fun unload() = NativeBridge.unload()
}

/** melonDS: Nintendo DS. */
object NdsCore : EmuCore {
    /** One DS frame: 560190 cycles at 33.51 MHz = 16.715 ms (59.83 Hz). */
    override val frameNanos = 16_715_113L

    override fun setInput(keys: Int, touch: Int) = NdsBridge.setInput(keys, touch)
    override fun runFrame() = NdsBridge.runFrame()
    override fun copyFrame(buffer: ByteBuffer) = NdsBridge.copyFrame(buffer)
    override fun readAudio(buffer: ShortArray) = NdsBridge.readAudio(buffer)
    override fun discardAudio() = NdsBridge.discardAudio()
    override fun setAudioSpeed(speed: Float) = NdsBridge.setAudioSpeed(speed)
    override fun rewindStep() = false
    override fun pollRumble() = false
    override fun reset() = NdsBridge.reset()
    override fun unload() = NdsBridge.unload()
}
