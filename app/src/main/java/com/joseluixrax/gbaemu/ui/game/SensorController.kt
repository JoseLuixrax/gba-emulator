package com.joseluixrax.gbaemu.ui.game

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import com.joseluixrax.gbaemu.emulator.NativeBridge

/**
 * Feeds the phone's motion sensors to cartridges that had them:
 * tilt (Yoshi Topsy-Turvy, Kirby Tilt 'n' Tumble) and gyro (WarioWare: Twisted!).
 * Sensors are only switched on for games that need them.
 */
class SensorController(private val activity: Activity) : SensorEventListener {
    private val manager = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var wantTilt = false
    private var wantGyro = false
    private var running = false

    /** Gyro multiplier, 1.0 = default. */
    var gyroSensitivity = 1f

    fun configure(hardware: Int) {
        wantTilt = hardware and NativeBridge.HW_TILT != 0
        wantGyro = hardware and NativeBridge.HW_GYRO != 0
        if (running) {
            stop()
            start()
        }
    }

    fun start() {
        if (running) return
        running = true
        if (wantTilt) {
            manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        if (wantGyro) {
            manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
                manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    fun stop() {
        running = false
        manager.unregisterListener(this)
        NativeBridge.setTilt(0f, 0f)
        NativeBridge.setGyro(0f)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // Convert device axes to screen axes for the current rotation.
                val ax = event.values[0]
                val ay = event.values[1]
                val (x, y) = when (displayRotation()) {
                    Surface.ROTATION_90 -> -ay to ax
                    Surface.ROTATION_180 -> -ax to -ay
                    Surface.ROTATION_270 -> ay to -ax
                    else -> ax to ay
                }
                NativeBridge.setTilt(x, y)
            }
            // Z = rotation around the axis coming out of the screen, like the
            // sensor inside the WarioWare: Twisted! cartridge.
            Sensor.TYPE_GYROSCOPE -> NativeBridge.setGyro(event.values[2] * gyroSensitivity)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun displayRotation(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        activity.display?.rotation ?: Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.rotation
    }
}
