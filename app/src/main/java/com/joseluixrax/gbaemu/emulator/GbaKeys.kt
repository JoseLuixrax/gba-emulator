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

/** The DS buttons the GBA doesn't have, plus "blow into the mic", in the same key word. */
object DsKeys {
    const val X = 1 shl 10
    const val Y = 1 shl 11
    const val MIC = 1 shl 12
}
