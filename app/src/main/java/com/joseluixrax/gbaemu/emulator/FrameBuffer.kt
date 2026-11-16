package com.joseluixrax.gbaemu.emulator

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hands video frames from the emulator thread to the GL thread.
 *
 * The emulator thread copies each finished frame into [pixels] while holding
 * the lock; the renderer uploads it to a texture under the same lock.
 */
class FrameBuffer {
    private val lock = Any()
    private val pixels: ByteBuffer =
        ByteBuffer.allocateDirect(MAX_WIDTH * MAX_HEIGHT * 4).order(ByteOrder.nativeOrder())

    @Volatile
    var width = 240
        private set

    @Volatile
    var height = 160
        private set

    /** Incremented on every new frame so the renderer can skip unchanged ones. */
    @Volatile
    var version = 0L
        private set

    /** Emulator thread: grab the frame the core just finished. */
    fun captureFromCore(core: EmuCore): Boolean = synchronized(lock) {
        val packed = core.copyFrame(pixels)
        if (packed == 0) return false
        width = packed ushr 16
        height = packed and 0xFFFF
        version++
        true
    }

    /** Runs [block] with the pixel buffer locked, positioned at 0. */
    fun <T> withPixels(block: (buffer: ByteBuffer, width: Int, height: Int) -> T): T = synchronized(lock) {
        pixels.position(0)
        block(pixels, width, height)
    }

    /** Copy of the current frame, used for screenshots and save state thumbnails. */
    fun toBitmap(): Bitmap? = withPixels { buffer, w, h ->
        if (version == 0L) return@withPixels null
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(buffer) }
    }

    companion object {
        // Super Game Boy borders are 256x224; DS games are two 256x192 screens.
        const val MAX_WIDTH = 256
        const val MAX_HEIGHT = 384
    }
}
