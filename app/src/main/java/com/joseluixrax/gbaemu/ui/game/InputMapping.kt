package com.joseluixrax.gbaemu.ui.game

import android.content.SharedPreferences
import android.view.KeyEvent
import androidx.annotation.StringRes
// Aliased: the enum entry `R` (shoulder button) would otherwise hide the R class.
import com.joseluixrax.gbaemu.R as Res
import com.joseluixrax.gbaemu.emulator.DsKeys
import com.joseluixrax.gbaemu.emulator.GbaKeys
import com.joseluixrax.gbaemu.emulator.TurboKeys

/** Things a physical button (gamepad, keyboard) can be bound to. [key]: console key bit, 0 for app actions. */
enum class InputAction(val id: String, @StringRes val label: Int, val key: Int = 0) {
    UP("up", Res.string.key_up, GbaKeys.UP),
    DOWN("down", Res.string.key_down, GbaKeys.DOWN),
    LEFT("left", Res.string.key_left, GbaKeys.LEFT),
    RIGHT("right", Res.string.key_right, GbaKeys.RIGHT),
    A("a", Res.string.key_a, GbaKeys.A),
    B("b", Res.string.key_b, GbaKeys.B),
    X("x", Res.string.key_x, DsKeys.X),
    Y("y", Res.string.key_y, DsKeys.Y),
    L("l", Res.string.key_l, GbaKeys.L),
    R("r", Res.string.key_r, GbaKeys.R),
    START("start", Res.string.key_start, GbaKeys.START),
    SELECT("select", Res.string.key_select, GbaKeys.SELECT),
    MIC("mic", Res.string.key_mic, DsKeys.MIC),
    TURBO_A("turbo_a", Res.string.key_turbo_a, GbaKeys.A shl TurboKeys.SHIFT),
    TURBO_B("turbo_b", Res.string.key_turbo_b, GbaKeys.B shl TurboKeys.SHIFT),
    TURBO_X("turbo_x", Res.string.key_turbo_x, DsKeys.X shl TurboKeys.SHIFT),
    TURBO_Y("turbo_y", Res.string.key_turbo_y, DsKeys.Y shl TurboKeys.SHIFT),
    FAST_FORWARD("fast_forward", Res.string.key_fast_forward),
    REWIND("rewind", Res.string.key_rewind),
    MENU("menu", Res.string.key_menu),
    QUICK_SAVE("quick_save", Res.string.key_quick_save),
    QUICK_LOAD("quick_load", Res.string.key_quick_load),
    SWAP_SCREENS("swap_screens", Res.string.key_swap_screens),
    LID("lid", Res.string.key_lid),
}

/**
 * Key bindings for physical controllers. Each action can have several keys;
 * the user's choice from the mapping screen replaces the defaults.
 */
object InputMapping {
    private const val PREFIX = "keymap_"

    // Nintendo-style positions: the right face button is A, the bottom one B,
    // the top one X and the left one Y.
    private val DEFAULTS: Map<InputAction, List<Int>> = mapOf(
        InputAction.UP to listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W),
        InputAction.DOWN to listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S),
        InputAction.LEFT to listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A),
        InputAction.RIGHT to listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D),
        InputAction.A to listOf(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_L),
        InputAction.B to listOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_K),
        InputAction.X to listOf(KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_I),
        InputAction.Y to listOf(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_J),
        InputAction.L to listOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_Q),
        InputAction.R to listOf(KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_E),
        InputAction.START to listOf(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER),
        InputAction.SELECT to listOf(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_SHIFT_RIGHT),
        InputAction.MIC to listOf(KeyEvent.KEYCODE_M),
        InputAction.FAST_FORWARD to listOf(KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_SPACE),
        InputAction.REWIND to listOf(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_R),
        InputAction.MENU to listOf(KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_ESCAPE),
        InputAction.QUICK_SAVE to listOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_F5),
        InputAction.QUICK_LOAD to listOf(KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_F7),
    )

    fun keysFor(prefs: SharedPreferences, action: InputAction): List<Int> {
        val stored = prefs.getString(PREFIX + action.id, null) ?: return DEFAULTS[action].orEmpty()
        return stored.split(',').mapNotNull { it.trim().toIntOrNull() }
    }

    fun setKey(prefs: SharedPreferences, action: InputAction, keyCode: Int) {
        val editor = prefs.edit()
        // A key can only do one thing: remove it from other actions first.
        for (other in InputAction.entries) {
            if (other == action) continue
            val keys = keysFor(prefs, other)
            if (keyCode in keys) editor.putString(PREFIX + other.id, keys.filter { it != keyCode }.joinToString(","))
        }
        editor.putString(PREFIX + action.id, keyCode.toString())
        editor.apply()
    }

    fun clear(prefs: SharedPreferences, action: InputAction) {
        prefs.edit().putString(PREFIX + action.id, "").apply()
    }

    fun resetAll(prefs: SharedPreferences) {
        val editor = prefs.edit()
        InputAction.entries.forEach { editor.remove(PREFIX + it.id) }
        editor.apply()
    }

    /** keyCode -> action, for fast lookup while playing. */
    fun buildLookup(prefs: SharedPreferences): Map<Int, InputAction> {
        val map = HashMap<Int, InputAction>()
        for (action in InputAction.entries) {
            for (key in keysFor(prefs, action)) map.putIfAbsent(key, action)
        }
        return map
    }

    fun keyName(keyCode: Int): String =
        KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace('_', ' ')
}
