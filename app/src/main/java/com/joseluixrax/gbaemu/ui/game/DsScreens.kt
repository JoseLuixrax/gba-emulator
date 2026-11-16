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
    /** How the screens sit when the phone is sideways. Portrait is always one above the other. */
    enum class Landscape { STACKED, SIDE_BY_SIDE }

    class Layout(val top: RectF, val bottom: RectF)

    /** Room left for the controls under the screens in portrait, in screen widths. */
    const val PORTRAIT_CONTROLS = 0.64f

    private const val W = 256f
    private const val H = 192f

    fun landscapeFrom(value: String) = if (value == "side") Landscape.SIDE_BY_SIDE else Landscape.STACKED

    fun compute(width: Int, height: Int, topInset: Int, landscape: Landscape, integerScale: Boolean): Layout {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        if (h > w) {
            // Portrait: both screens at the top, the controls underneath. On
            // short phones the screens shrink a little instead of hiding
            // under the buttons.
            val availH = max(h - topInset - PORTRAIT_CONTROLS * w, h * 0.4f)
            val scale = fit(min(w / W, availH / (2 * H)), integerScale)
            val sw = W * scale
            val sh = H * scale
            val x = (w - sw) / 2
            val y = topInset.toFloat()
            return Layout(RectF(x, y, x + sw, y + sh), RectF(x, y + sh, x + sw, y + 2 * sh))
        }
        return when (landscape) {
            Landscape.STACKED -> {
                // In the middle, leaving both sides for the controls.
                val scale = fit(min(w / W, h / (2 * H)), integerScale)
                val sw = W * scale
                val sh = H * scale
                val x = (w - sw) / 2
                val y = (h - 2 * sh) / 2
                Layout(RectF(x, y, x + sw, y + sh), RectF(x, y + sh, x + sw, y + 2 * sh))
            }
            Landscape.SIDE_BY_SIDE -> {
                val scale = fit(min(w / (2 * W), h / H), integerScale)
                val sw = W * scale
                val sh = H * scale
                val x = (w - 2 * sw) / 2
                val y = (h - sh) / 2
                Layout(RectF(x, y, x + sw, y + sh), RectF(x + sw, y, x + 2 * sw, y + sh))
            }
        }
    }

    private fun fit(scale: Float, integer: Boolean) = if (integer && scale >= 1f) floor(scale) else scale
}
