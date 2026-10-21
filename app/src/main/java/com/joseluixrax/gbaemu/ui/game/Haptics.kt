package com.joseluixrax.gbaemu.ui.game

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Short vibrations for button presses and the game's rumble motor. */
class Haptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    @Volatile private var rumbleUntil = 0L

    val available: Boolean get() = vibrator?.hasVibrator() == true

    /** Tiny "click" when a virtual button is pressed. */
    fun tick() {
        val v = vibrator ?: return
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                v.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
            else -> @Suppress("DEPRECATION") v.vibrate(12)
        }
    }

    /**
     * Called every frame the game has rumble on. Vibrations are chained in
     * short pulses so the motor stops quickly when the game turns it off.
     */
    fun rumble() {
        val v = vibrator ?: return
        val now = SystemClock.uptimeMillis()
        if (now < rumbleUntil - 20) return
        rumbleUntil = now + RUMBLE_PULSE_MS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(RUMBLE_PULSE_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(RUMBLE_PULSE_MS)
        }
    }

    private companion object {
        const val RUMBLE_PULSE_MS = 60L
    }
}
