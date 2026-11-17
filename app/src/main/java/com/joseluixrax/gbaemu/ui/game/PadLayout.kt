package com.joseluixrax.gbaemu.ui.game

import android.content.SharedPreferences
import org.json.JSONObject

/** Everything that can appear on the on-screen controller. */
enum class PadElement(val id: String, val label: String, val shape: Shape, val baseWidth: Float, val baseHeight: Float) {
    // Sizes are fractions of the short side of the screen.
    DPAD("dpad", "", Shape.DPAD, 0.40f, 0.40f),
    A("a", "A", Shape.CIRCLE, 0.17f, 0.17f),
    B("b", "B", Shape.CIRCLE, 0.17f, 0.17f),
    // DS only.
    X("x", "X", Shape.CIRCLE, 0.17f, 0.17f),
    Y("y", "Y", Shape.CIRCLE, 0.17f, 0.17f),
    MIC("mic", "MIC", Shape.PILL, 0.13f, 0.065f),
    SWAP("swap", "↕", Shape.CIRCLE, 0.10f, 0.10f),
    // Optional (settings): press A / B over and over while held.
    TURBO_A("turbo_a", "A", Shape.CIRCLE, 0.13f, 0.13f),
    TURBO_B("turbo_b", "B", Shape.CIRCLE, 0.13f, 0.13f),
    L("l", "L", Shape.PILL, 0.23f, 0.09f),
    R("r", "R", Shape.PILL, 0.23f, 0.09f),
    SELECT("select", "SELECT", Shape.PILL, 0.17f, 0.065f),
    START("start", "START", Shape.PILL, 0.17f, 0.065f),
    MENU("menu", "≡", Shape.CIRCLE, 0.10f, 0.10f),
    FAST_FORWARD("ff", "»", Shape.CIRCLE, 0.10f, 0.10f),
    REWIND("rw", "«", Shape.CIRCLE, 0.10f, 0.10f),
    ;

    enum class Shape { DPAD, CIRCLE, PILL }

    val isTurbo: Boolean get() = this == TURBO_A || this == TURBO_B

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
    }
}

/** Center position (fractions of view width/height) and user scale of an element. */
data class Placement(var x: Float, var y: Float, var scale: Float = 1f)

/**
 * Positions of the on-screen controls, stored separately for portrait and
 * landscape, and for GBA and DS games, so each can be customised in the
 * layout editor.
 */
object PadLayout {
    private fun prefKey(landscape: Boolean, ds: Boolean) =
        (if (ds) "pad_layout_ds_" else "pad_layout_") + (if (landscape) "landscape" else "portrait")

    fun load(prefs: SharedPreferences, width: Int, height: Int, ds: Boolean): MutableMap<PadElement, Placement> {
        val landscape = width > height
        val layout = defaults(width, height, ds)
        val json = prefs.getString(prefKey(landscape, ds), null) ?: return layout
        try {
            val obj = JSONObject(json)
            for (key in obj.keys()) {
                val element = PadElement.byId(key) ?: continue
                if (element !in layout) continue
                val p = obj.getJSONObject(key)
                layout[element] = Placement(
                    p.getDouble("x").toFloat().coerceIn(0f, 1f),
                    p.getDouble("y").toFloat().coerceIn(0f, 1f),
                    p.optDouble("s", 1.0).toFloat().coerceIn(MIN_SCALE, MAX_SCALE),
                )
            }
        } catch (_: Exception) {
            // Corrupt data: fall back to defaults.
        }
        return layout
    }

    fun save(prefs: SharedPreferences, landscape: Boolean, ds: Boolean, layout: Map<PadElement, Placement>) {
        val obj = JSONObject()
        layout.forEach { (element, p) ->
            obj.put(element.id, JSONObject().put("x", p.x.toDouble()).put("y", p.y.toDouble()).put("s", p.scale.toDouble()))
        }
        prefs.edit().putString(prefKey(landscape, ds), obj.toString()).apply()
    }

    fun reset(prefs: SharedPreferences) {
        val editor = prefs.edit()
        for (landscape in listOf(true, false)) {
            for (ds in listOf(true, false)) editor.remove(prefKey(landscape, ds))
        }
        editor.apply()
    }

    /** Default positions of the on-screen buttons. */
    fun defaults(width: Int, height: Int, ds: Boolean): MutableMap<PadElement, Placement> {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val map = mutableMapOf<PadElement, Placement>()
        fun put(e: PadElement, x: Float, y: Float, scale: Float = 1f) {
            map[e] = Placement((x / w).coerceIn(0f, 1f), (y / h).coerceIn(0f, 1f), scale)
        }
        if (ds) {
            // A, B, X and Y in a diamond like on the console, a bit smaller
            // than the GBA ones so the four fit.
            fun faceButtons(cx: Float, cy: Float, step: Float) {
                put(PadElement.A, cx + step, cy, DS_FACE_SCALE)
                put(PadElement.B, cx, cy + step, DS_FACE_SCALE)
                put(PadElement.X, cx, cy - step, DS_FACE_SCALE)
                put(PadElement.Y, cx - step, cy, DS_FACE_SCALE)
            }
            if (w > h) {
                // Landscape: screens in the middle, everything on the sides.
                val u = h
                put(PadElement.DPAD, 0.27f * u, h - 0.36f * u)
                faceButtons(w - 0.27f * u, h - 0.36f * u, 0.13f * u)
                put(PadElement.L, 0.14f * u, 0.09f * u)
                put(PadElement.R, w - 0.14f * u, 0.09f * u)
                put(PadElement.SELECT, 0.27f * u, h - 0.06f * u)
                put(PadElement.START, w - 0.27f * u, h - 0.06f * u)
                put(PadElement.MENU, 0.10f * u, 0.27f * u)
                put(PadElement.MIC, 0.30f * u, 0.27f * u)
                put(PadElement.FAST_FORWARD, w - 0.10f * u, 0.27f * u)
                put(PadElement.SWAP, w - 0.30f * u, 0.27f * u)
                put(PadElement.TURBO_A, w - 0.09f * u, h - 0.15f * u, DS_TURBO_SCALE)
                put(PadElement.TURBO_B, w - 0.45f * u, h - 0.15f * u, DS_TURBO_SCALE)
            } else {
                // Portrait: both screens on top (see DsScreens), controls in the space left.
                val u = w
                put(PadElement.DPAD, 0.25f * u, h - 0.30f * u)
                faceButtons(w - 0.25f * u, h - 0.30f * u, 0.13f * u)
                put(PadElement.L, 0.14f * u, h - 0.585f * u)
                put(PadElement.R, w - 0.14f * u, h - 0.585f * u)
                put(PadElement.MIC, w / 2f - 0.13f * u, h - 0.585f * u)
                put(PadElement.MENU, w / 2f, h - 0.585f * u)
                put(PadElement.FAST_FORWARD, w / 2f + 0.13f * u, h - 0.585f * u)
                put(PadElement.SELECT, w / 2f - 0.10f * u, h - 0.045f * u)
                put(PadElement.START, w / 2f + 0.10f * u, h - 0.045f * u)
                put(PadElement.SWAP, w - 0.07f * u, h - 0.06f * u)
                put(PadElement.TURBO_A, w - 0.07f * u, h - 0.165f * u, DS_TURBO_SCALE)
                put(PadElement.TURBO_B, w - 0.43f * u, h - 0.165f * u, DS_TURBO_SCALE)
            }
            return map
        }
        if (w > h) {
            // Landscape: game in the middle, controls on both sides.
            val u = h
            put(PadElement.DPAD, 0.24f * u, h - 0.30f * u)
            put(PadElement.A, w - 0.12f * u, h - 0.40f * u)
            put(PadElement.B, w - 0.31f * u, h - 0.26f * u)
            put(PadElement.L, 0.14f * u, 0.09f * u)
            put(PadElement.R, w - 0.14f * u, 0.09f * u)
            put(PadElement.SELECT, w / 2f - 0.11f * u, h - 0.05f * u)
            put(PadElement.START, w / 2f + 0.11f * u, h - 0.05f * u)
            put(PadElement.MENU, w / 2f, 0.07f * u)
            put(PadElement.REWIND, 0.10f * u, 0.27f * u)
            put(PadElement.FAST_FORWARD, w - 0.10f * u, 0.27f * u)
            put(PadElement.TURBO_A, w - 0.12f * u, h - 0.60f * u)
            put(PadElement.TURBO_B, w - 0.31f * u, h - 0.44f * u)
        } else {
            // Portrait: game on top, controls underneath.
            val u = w
            put(PadElement.DPAD, 0.25f * u, h - 0.33f * u)
            put(PadElement.A, w - 0.14f * u, h - 0.40f * u)
            put(PadElement.B, w - 0.34f * u, h - 0.29f * u)
            put(PadElement.L, 0.14f * u, h - 0.67f * u)
            put(PadElement.R, w - 0.14f * u, h - 0.67f * u)
            put(PadElement.SELECT, w / 2f - 0.12f * u, h - 0.07f * u)
            put(PadElement.START, w / 2f + 0.12f * u, h - 0.07f * u)
            put(PadElement.MENU, w / 2f, h - 0.67f * u)
            put(PadElement.REWIND, w / 2f - 0.13f * u, h - 0.67f * u)
            put(PadElement.FAST_FORWARD, w / 2f + 0.13f * u, h - 0.67f * u)
            put(PadElement.TURBO_A, w - 0.14f * u, h - 0.55f * u)
            put(PadElement.TURBO_B, w - 0.34f * u, h - 0.47f * u)
        }
        return map
    }

    private const val DS_FACE_SCALE = 0.85f
    private const val DS_TURBO_SCALE = 0.77f
    const val MIN_SCALE = 0.5f
    const val MAX_SCALE = 2.0f
}
