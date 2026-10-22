package com.joseluixrax.gbaemu

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Typed access to the settings screen values (res/xml/preferences.xml).
 * Keys here must match the android:key attributes in that file.
 */
class Prefs(context: Context) {
    val raw: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    // Video
    val videoFilter: String get() = raw.getString(KEY_VIDEO_FILTER, "sharp") ?: "sharp"
    val videoEffect: String get() = raw.getString(KEY_VIDEO_EFFECT, "none") ?: "none"
    val colorCorrection: Boolean get() = raw.getBoolean(KEY_COLOR_CORRECTION, false)
    val aspectMode: String get() = raw.getString(KEY_ASPECT, "keep") ?: "keep"
    val showFps: Boolean get() = raw.getBoolean(KEY_SHOW_FPS, false)
    val frameskip: Int get() = raw.getString(KEY_FRAMESKIP, "0")?.toIntOrNull() ?: 0
    val orientation: String get() = raw.getString(KEY_ORIENTATION, "auto") ?: "auto"

    // Audio
    val audioEnabled: Boolean get() = raw.getBoolean(KEY_AUDIO_ENABLED, true)
    val audioVolume: Int get() = raw.getInt(KEY_AUDIO_VOLUME, 100)

    // Controls
    val padOpacity: Int get() = raw.getInt(KEY_PAD_OPACITY, 55)
    val padScale: Int get() = raw.getInt(KEY_PAD_SCALE, 100)
    val haptics: Boolean get() = raw.getBoolean(KEY_HAPTICS, true)
    val fastForwardToggle: Boolean get() = raw.getBoolean(KEY_FF_TOGGLE, true)
    val hidePadWithGamepad: Boolean get() = raw.getBoolean(KEY_HIDE_PAD_GAMEPAD, true)
    val dpadDiagonals: Boolean get() = raw.getBoolean(KEY_DPAD_DIAGONALS, true)

    // Emulation
    val fastForwardSpeed: Int get() = raw.getString(KEY_FF_SPEED, "4")?.toIntOrNull() ?: 4
    val fastForwardAudio: Boolean get() = raw.getBoolean(KEY_FF_AUDIO, true)
    val rewindEnabled: Boolean get() = raw.getBoolean(KEY_REWIND_ENABLED, false)
    val rewindSeconds: Int get() = raw.getString(KEY_REWIND_SECONDS, "20")?.toIntOrNull() ?: 20
    val autoSaveState: Boolean get() = raw.getBoolean(KEY_AUTO_SAVE_STATE, true)
    val autoLoadState: Boolean get() = raw.getBoolean(KEY_AUTO_LOAD_STATE, true)
    val idleLoopDetect: Boolean get() = raw.getBoolean(KEY_IDLE_LOOP, true)
    val gbColorize: Boolean get() = raw.getBoolean(KEY_GB_COLORIZE, true)
    val skipBios: Boolean get() = raw.getBoolean(KEY_SKIP_BIOS, true)
    val gyroSensitivity: Int get() = raw.getInt(KEY_GYRO_SENSITIVITY, 100)

    // Library
    var romFolder: String?
        get() = raw.getString(KEY_ROM_FOLDER, null)
        set(value) = raw.edit().putString(KEY_ROM_FOLDER, value).apply()

    companion object {
        const val KEY_VIDEO_FILTER = "video_filter"
        const val KEY_VIDEO_EFFECT = "video_effect"
        const val KEY_COLOR_CORRECTION = "color_correction"
        const val KEY_ASPECT = "aspect_mode"
        const val KEY_SHOW_FPS = "show_fps"
        const val KEY_FRAMESKIP = "frameskip"
        const val KEY_ORIENTATION = "orientation"
        const val KEY_AUDIO_ENABLED = "audio_enabled"
        const val KEY_AUDIO_VOLUME = "audio_volume"
        const val KEY_PAD_OPACITY = "pad_opacity"
        const val KEY_PAD_SCALE = "pad_scale"
        const val KEY_HAPTICS = "haptics"
        const val KEY_FF_TOGGLE = "ff_toggle"
        const val KEY_HIDE_PAD_GAMEPAD = "hide_pad_gamepad"
        const val KEY_DPAD_DIAGONALS = "dpad_diagonals"
        const val KEY_RESET_LAYOUT = "reset_layout"
        const val KEY_KEY_MAPPING = "key_mapping"
        const val KEY_FF_SPEED = "ff_speed"
        const val KEY_FF_AUDIO = "ff_audio"
        const val KEY_REWIND_ENABLED = "rewind_enabled"
        const val KEY_REWIND_SECONDS = "rewind_seconds"
        const val KEY_AUTO_SAVE_STATE = "auto_save_state"
        const val KEY_AUTO_LOAD_STATE = "auto_load_state"
        const val KEY_IDLE_LOOP = "idle_loop"
        const val KEY_GB_COLORIZE = "gb_colorize"
        const val KEY_BIOS = "bios_file"
        const val KEY_SKIP_BIOS = "skip_bios"
        const val KEY_GYRO_SENSITIVITY = "gyro_sensitivity"
        const val KEY_ABOUT = "about"
        const val KEY_LICENSES = "licenses"
        const val KEY_ROM_FOLDER = "rom_folder"
    }
}
