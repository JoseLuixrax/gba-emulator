package com.joseluixrax.gbaemu.ui.game

import android.content.SharedPreferences
import org.json.JSONObject

/** Everything that can appear on the on-screen controller. */
enum class PadElement(val id: String, val label: String, val shape: Shape, val baseWidth: Float, val baseHeight: Float) {
    // Sizes are fractions of the short side of the screen.
    DPAD("dpad", "", Shape.DPAD, 0.40f, 0.40f),
    A("a", "A", Shape.CIRCLE, 0.17f, 0.17f),
    B("b", "B", Shape.CIRCLE, 0.17f, 0.17f),
    L("l", "L", Shape.PILL, 0.23f, 0.09f),
    R("r", "R", Shape.PILL, 0.23f, 0.09f),
    SELECT("select", "SELECT", Shape.PILL, 0.17f, 0.065f),
    START("start", "START", Shape.PILL, 0.17f, 0.065f),
    MENU("menu", "≡", Shape.CIRCLE, 0.10f, 0.10f),
    FAST_FORWARD("ff", "»", Shape.CIRCLE, 0.10f, 0.10f),
    REWIND("rw", "«", Shape.CIRCLE, 0.10f, 0.10f),
    ;

    enum class Shape { DPAD, CIRCLE, PILL }

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
    }
}

/** Center position (fractions of view width/height) and user scale of an element. */
data class Placement(var x: Float, var y: Float, var scale: Float = 1f)

/**
 * Positions of the on-screen controls, stored separately for portrait and
 * landscape so each can be customised in the layout editor.
 */
object PadLayout {
    private fun prefKey(landscape: Boolean) = if (landscape) "pad_layout_landscape" else "pad_layout_portrait"

    fun load(prefs: SharedPreferences, width: Int, height: Int): MutableMap<PadElement, Placement> {
        val landscape = width > height
        val layout = defaults(width, height)
        val json = prefs.getString(prefKey(landscape), null) ?: return layout
        try {
            val obj = JSONObject(json)
            for (key in obj.keys()) {
                val element = PadElement.byId(key) ?: continue
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

    fun save(prefs: SharedPreferences, landscape: Boolean, layout: Map<PadElement, Placement>) {
        val obj = JSONObject()
        layout.forEach { (element, p) ->
            obj.put(element.id, JSONObject().put("x", p.x.toDouble()).put("y", p.y.toDouble()).put("s", p.scale.toDouble()))
        }
        prefs.edit().putString(prefKey(landscape), obj.toString()).apply()
    }

    fun reset(prefs: SharedPreferences) {
        prefs.edit().remove(prefKey(true)).remove(prefKey(false)).apply()
    }

    /** Default positions, modelled on the classic My Boy! layout. */
    fun defaults(width: Int, height: Int): MutableMap<PadElement, Placement> {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val map = mutableMapOf<PadElement, Placement>()
        fun put(e: PadElement, x: Float, y: Float) {
            map[e] = Placement((x / w).coerceIn(0f, 1f), (y / h).coerceIn(0f, 1f))
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
        }
        return map
    }

    const val MIN_SCALE = 0.5f
    const val MAX_SCALE = 2.0f
}
