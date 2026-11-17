package com.joseluixrax.gbaemu.emulator

/** Bits of the GBA KEYINPUT register, the format the native core expects. */
object GbaKeys {
    const val A = 1 shl 0
    const val B = 1 shl 1
    const val SELECT = 1 shl 2
    const val START = 1 shl 3
    const val RIGHT = 1 shl 4
    const val LEFT = 1 shl 5
    const val UP = 1 shl 6
    const val DOWN = 1 shl 7
    const val R = 1 shl 8
    const val L = 1 shl 9
}

/** The DS buttons the GBA doesn't have, plus the mic and the lid, in the same key word. */
object DsKeys {
    const val X = 1 shl 10
    const val Y = 1 shl 11
    const val MIC = 1 shl 12

    /** Set while the lid is closed. */
    const val LID = 1 shl 13
}

/** The 3DS shoulder buttons the DS doesn't have (the rest reuse GbaKeys and DsKeys). */
object N3dsKeys {
    const val ZL = 1 shl 14
    const val ZR = 1 shl 15
}

/**
 * Turbo buttons travel in the same key word, shifted up: a held "turbo A" is
 * GbaKeys.A shl SHIFT. The emulator thread turns them into presses and
 * releases with [TurboClock].
 */
object TurboKeys {
    const val SHIFT = 16
    const val NORMAL_MASK = (1 shl SHIFT) - 1
}

/** Held turbo buttons go down for [halfPeriod] frames, up for as many, and so on. */
object TurboClock {
    /** [frame] counts from 0 when the first turbo button goes down, so it starts pressed. */
    fun keys(frame: Long, keys: Int, turbo: Int, halfPeriod: Int): Int {
        if (turbo == 0) return keys
        val down = (frame / halfPeriod.coerceAtLeast(1)) % 2 == 0L
        // A button held normally stays held, turbo or not.
        return if (down) keys or turbo else keys
    }
}
