package com.joseluixrax.gbaemu.ui.game

import android.graphics.RectF
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Where the two DS screens go, in view pixels (origin top-left). The renderer
 * draws with it and the on-screen pad maps touches with it, so both always
 * agree on where the bottom screen is.
 */
object DsScreens {
    enum class Portrait { STACKED, SINGLE }

    /** STACKED: in the middle, one above the other. FOCUS: one big, one small. */
    enum class Landscape { STACKED, SIDE_BY_SIDE, FOCUS, SINGLE }

    /**
     * [swapped]: the bottom screen comes first (on top, on the left, the big
     * one or the only one shown).
     */
    data class Config(
        val portrait: Portrait = Portrait.STACKED,
        val landscape: Landscape = Landscape.STACKED,
        val swapped: Boolean = false,
        val integerScale: Boolean = false,
    )

    /** Screen rectangles; an empty one is not shown. */
    class Layout(val top: RectF, val bottom: RectF)

    /** Room left for the controls under the screens in portrait, in screen widths. */
    const val PORTRAIT_CONTROLS = 0.64f

    /** One big, one small: the big one takes at most this much of the width. */
    private const val FOCUS_MAX_WIDTH = 0.7f

    private const val W = 256f
    private const val H = 192f

    fun portraitFrom(value: String) = if (value == "single") Portrait.SINGLE else Portrait.STACKED

    fun landscapeFrom(value: String) = when (value) {
        "side" -> Landscape.SIDE_BY_SIDE
        "focus" -> Landscape.FOCUS
        "single" -> Landscape.SINGLE
        else -> Landscape.STACKED
    }

    fun compute(width: Int, height: Int, topInset: Int, config: Config): Layout {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val (first, second) = if (h > w) portrait(w, h, topInset.toFloat(), config) else landscape(w, h, config)
        return if (config.swapped) Layout(top = second, bottom = first) else Layout(top = first, bottom = second)
    }

    /** Both screens at the top, the controls underneath. */
    private fun portrait(w: Float, h: Float, top: Float, config: Config): Pair<RectF, RectF> {
        // On short phones the screens shrink a little instead of hiding under the buttons.
        val availH = max(h - top - PORTRAIT_CONTROLS * w, h * 0.4f)
        return when (config.portrait) {
            Portrait.STACKED -> {
                val s = fit(min(w / W, availH / (2 * H)), config)
                val x = (w - W * s) / 2
                screen(x, top, s) to screen(x, top + H * s, s)
            }
            Portrait.SINGLE -> {
                val s = fit(min(w / W, availH / H), config)
                screen((w - W * s) / 2, top, s) to RectF()
            }
        }
    }

    private fun landscape(w: Float, h: Float, config: Config): Pair<RectF, RectF> = when (config.landscape) {
        Landscape.STACKED -> {
            // In the middle, leaving both sides for the controls.
            val s = fit(min(w / W, h / (2 * H)), config)
            val x = (w - W * s) / 2
            val y = (h - 2 * H * s) / 2
            screen(x, y, s) to screen(x, y + H * s, s)
        }
        Landscape.SIDE_BY_SIDE -> {
            val s = fit(min(w / (2 * W), h / H), config)
            val x = (w - 2 * W * s) / 2
            val y = (h - H * s) / 2
            screen(x, y, s) to screen(x + W * s, y, s)
        }
        Landscape.FOCUS -> {
            // The big one as tall as the screen; the small one next to it,
            // top-aligned, in what's left of the width (at most half the height).
            val big = fit(min(h / H, w * FOCUS_MAX_WIDTH / W), config)
            val bigW = W * big
            val small = fit(min((w - bigW) / W, h / 2 / H), config)
            val x = (w - bigW - W * small) / 2
            val y = (h - H * big) / 2
            screen(x, y, big) to screen(x + bigW, y, small)
        }
        Landscape.SINGLE -> {
            val s = fit(min(w / W, h / H), config)
            screen((w - W * s) / 2, (h - H * s) / 2, s) to RectF()
        }
    }

    private fun screen(x: Float, y: Float, scale: Float) = RectF(x, y, x + W * scale, y + H * scale)

    private fun fit(scale: Float, config: Config) =
        if (config.integerScale && scale >= 1f) floor(scale) else scale
}
