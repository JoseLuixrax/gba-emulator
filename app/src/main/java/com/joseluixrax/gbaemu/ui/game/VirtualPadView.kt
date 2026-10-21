package com.joseluixrax.gbaemu.ui.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.preference.PreferenceManager
import com.joseluixrax.gbaemu.emulator.GbaKeys
import java.util.EnumMap
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The on-screen controller: draws the buttons and turns touches into GBA keys.
 *
 * - Multi-touch, with sliding between A/B/L/R like on My Boy!.
 * - 8-way D-pad that keeps tracking the thumb even outside its circle.
 * - Edit mode: drag a control to move it, pinch (or +/-) to resize it.
 */
class VirtualPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onPadKeysChanged(keys: Int)
        fun onMenuPressed()
        fun onFastForwardButton(down: Boolean)
        fun onRewindButton(down: Boolean)
    }

    var listener: Listener? = null

    var opacity = 0.55f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Global size multiplier from the settings (applies on top of per-control scale). */
    var globalScale = 1f
        set(value) {
            field = value
            computeRects()
            invalidate()
        }

    var hapticsEnabled = true
    var diagonals = true

    var showRewind = false
        set(value) {
            field = value
            invalidate()
        }

    /** Fast forward is toggled on: draw its button highlighted. */
    var fastForwardActive = false
        set(value) {
            field = value
            invalidate()
        }

    /** Hidden while a physical gamepad is in use; a touch brings it back. */
    var padVisible = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var editMode = false
        set(value) {
            field = value
            selected = null
            releaseAll()
            invalidate()
        }

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val haptics = Haptics(context)
    private var layout: MutableMap<PadElement, Placement> = mutableMapOf()
    private val rects = EnumMap<PadElement, RectF>(PadElement::class.java)

    private class Touch(var element: PadElement?, var keys: Int = 0)

    private val touches = HashMap<Int, Touch>()
    private val pressed = HashSet<PadElement>()
    private var currentKeys = 0

    // Edit mode state
    private var selected: PadElement? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val element = selected ?: return false
            val p = layout[element] ?: return false
            p.scale = (p.scale * detector.scaleFactor).coerceIn(PadLayout.MIN_SCALE, PadLayout.MAX_SCALE)
            computeRects()
            invalidate()
            return true
        }
    })

    private val density = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.rgb(255, 200, 0)
        pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
    }
    private val path = Path()
    private val tmpRect = RectF()

    // ----------------------------------------------------------------- layout

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        releaseAll()
        reloadLayout()
    }

    /** Re-reads the saved layout for the current orientation. */
    fun reloadLayout() {
        if (width == 0 || height == 0) return
        layout = PadLayout.load(prefs, width, height)
        computeRects()
        invalidate()
    }

    fun saveLayout() {
        if (width == 0 || height == 0) return
        PadLayout.save(prefs, width > height, layout)
    }

    fun resetLayoutToDefaults() {
        if (width == 0 || height == 0) return
        layout = PadLayout.defaults(width, height)
        computeRects()
        invalidate()
    }

    /** Edit mode +/- buttons. Returns false when no control is selected. */
    fun scaleSelected(factor: Float): Boolean {
        val element = selected ?: return false
        val p = layout[element] ?: return false
        p.scale = (p.scale * factor).coerceIn(PadLayout.MIN_SCALE, PadLayout.MAX_SCALE)
        computeRects()
        invalidate()
        return true
    }

    private fun computeRects() {
        if (width == 0 || height == 0) return
        val unit = min(width, height).toFloat()
        for ((element, p) in layout) {
            val w = element.baseWidth * unit * globalScale * p.scale
            val h = element.baseHeight * unit * globalScale * p.scale
            val cx = p.x * width
            val cy = p.y * height
            rects[element] = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        }
    }

    private fun isShown(element: PadElement) = element != PadElement.REWIND || showRewind

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!padVisible && !editMode) return
        val alpha = if (editMode) max(opacity, 0.75f) else opacity
        for (element in PadElement.entries) {
            if (!isShown(element)) continue
            val r = rects[element] ?: continue
            val isPressed = element in pressed || (element == PadElement.FAST_FORWARD && fastForwardActive)
            setPaints(alpha, isPressed)
            when (element.shape) {
                PadElement.Shape.DPAD -> drawDpad(canvas, r, alpha)
                PadElement.Shape.CIRCLE -> {
                    val radius = min(r.width(), r.height()) / 2
                    canvas.drawCircle(r.centerX(), r.centerY(), radius, fillPaint)
                    canvas.drawCircle(r.centerX(), r.centerY(), radius, strokePaint)
                    drawLabel(canvas, element.label, r, r.height() * 0.5f)
                }
                PadElement.Shape.PILL -> {
                    val radius = r.height() / 2
                    canvas.drawRoundRect(r, radius, radius, fillPaint)
                    canvas.drawRoundRect(r, radius, radius, strokePaint)
                    drawLabel(canvas, element.label, r, r.height() * if (element.label.length > 1) 0.42f else 0.6f)
                }
            }
            if (editMode && element == selected) {
                tmpRect.set(r)
                tmpRect.inset(-4 * density, -4 * density)
                canvas.drawRect(tmpRect, selectionPaint)
            }
        }
    }

    private fun setPaints(alpha: Float, isPressed: Boolean) {
        val a = (alpha * 255).toInt()
        fillPaint.color = if (isPressed) Color.argb((a * 0.85f).toInt(), 255, 255, 255) else Color.argb((a * 0.55f).toInt(), 20, 20, 30)
        strokePaint.color = Color.argb(a, 255, 255, 255)
        textPaint.color = if (isPressed) Color.argb(a, 20, 20, 30) else Color.argb(a, 255, 255, 255)
    }

    private fun drawLabel(canvas: Canvas, label: String, r: RectF, size: Float) {
        if (label.isEmpty()) return
        textPaint.textSize = size
        val y = r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(label, r.centerX(), y, textPaint)
    }

    private fun drawDpad(canvas: Canvas, r: RectF, alpha: Float) {
        val arm = r.width() / 3
        val cx = r.centerX()
        val cy = r.centerY()
        val corner = arm * 0.2f
        path.reset()
        path.addRoundRect(RectF(r.left, cy - arm / 2, r.right, cy + arm / 2), corner, corner, Path.Direction.CW)
        val vertical = Path().apply {
            addRoundRect(RectF(cx - arm / 2, r.top, cx + arm / 2, r.bottom), corner, corner, Path.Direction.CW)
        }
        path.op(vertical, Path.Op.UNION)
        setPaints(alpha, false)
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)

        // Highlight pressed directions.
        val keys = dpadPressedKeys()
        if (keys != 0) {
            setPaints(alpha, true)
            if (keys and GbaKeys.UP != 0) canvas.drawRoundRect(cx - arm / 2, r.top, cx + arm / 2, cy - arm / 2, corner, corner, fillPaint)
            if (keys and GbaKeys.DOWN != 0) canvas.drawRoundRect(cx - arm / 2, cy + arm / 2, cx + arm / 2, r.bottom, corner, corner, fillPaint)
            if (keys and GbaKeys.LEFT != 0) canvas.drawRoundRect(r.left, cy - arm / 2, cx - arm / 2, cy + arm / 2, corner, corner, fillPaint)
            if (keys and GbaKeys.RIGHT != 0) canvas.drawRoundRect(cx + arm / 2, cy - arm / 2, r.right, cy + arm / 2, corner, corner, fillPaint)
            setPaints(alpha, false)
        }

        // Arrow marks.
        val a = arm * 0.22f
        val off = arm * 1.0f
        fillPaint.color = strokePaint.color
        drawArrow(canvas, cx, cy - off, a, 0)
        drawArrow(canvas, cx, cy + off, a, 2)
        drawArrow(canvas, cx - off, cy, a, 3)
        drawArrow(canvas, cx + off, cy, a, 1)
    }

    /** direction: 0 up, 1 right, 2 down, 3 left. */
    private fun drawArrow(canvas: Canvas, x: Float, y: Float, size: Float, direction: Int) {
        val p = Path()
        when (direction) {
            0 -> { p.moveTo(x, y - size); p.lineTo(x + size, y + size * 0.6f); p.lineTo(x - size, y + size * 0.6f) }
            2 -> { p.moveTo(x, y + size); p.lineTo(x + size, y - size * 0.6f); p.lineTo(x - size, y - size * 0.6f) }
            3 -> { p.moveTo(x - size, y); p.lineTo(x + size * 0.6f, y - size); p.lineTo(x + size * 0.6f, y + size) }
            else -> { p.moveTo(x + size, y); p.lineTo(x - size * 0.6f, y - size); p.lineTo(x - size * 0.6f, y + size) }
        }
        p.close()
        canvas.drawPath(p, fillPaint)
    }

    private fun dpadPressedKeys(): Int {
        var keys = 0
        for (t in touches.values) if (t.element == PadElement.DPAD) keys = keys or t.keys
        return keys
    }

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) return onEditTouch(event)
        if (!padVisible) padVisible = true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val touch = Touch(hitTest(event.getX(i), event.getY(i), PadElement.entries))
                touches[event.getPointerId(i)] = touch
                onTouchStart(touch)
                updateTouch(touch, event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val touch = touches[event.getPointerId(i)] ?: continue
                    updateTouch(touch, event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                touches.remove(event.getPointerId(event.actionIndex))?.let { onTouchEnd(it) }
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        publishKeys()
        return true
    }

    private fun onTouchStart(touch: Touch) {
        when (touch.element) {
            PadElement.MENU -> {
                if (hapticsEnabled) haptics.tick()
                listener?.onMenuPressed()
            }
            PadElement.FAST_FORWARD -> {
                if (hapticsEnabled) haptics.tick()
                listener?.onFastForwardButton(true)
            }
            PadElement.REWIND -> {
                if (hapticsEnabled) haptics.tick()
                listener?.onRewindButton(true)
            }
            else -> Unit
        }
    }

    private fun onTouchEnd(touch: Touch) {
        when (touch.element) {
            PadElement.FAST_FORWARD -> listener?.onFastForwardButton(false)
            PadElement.REWIND -> listener?.onRewindButton(false)
            else -> Unit
        }
    }

    private fun updateTouch(touch: Touch, x: Float, y: Float) {
        when (touch.element) {
            PadElement.DPAD -> {
                touch.keys = dpadKeys(rects[PadElement.DPAD] ?: return, x, y)
            }
            PadElement.MENU, PadElement.FAST_FORWARD, PadElement.REWIND -> touch.keys = 0
            PadElement.START, PadElement.SELECT -> touch.keys = keyFor(touch.element)
            else -> {
                // Face and shoulder buttons: the finger may slide to a neighbour.
                touch.element = hitTest(x, y, SLIDABLE)
                touch.keys = keyFor(touch.element)
            }
        }
    }

    private fun releaseAll() {
        touches.values.forEach { onTouchEnd(it) }
        touches.clear()
        if (currentKeys != 0 || pressed.isNotEmpty()) {
            pressed.clear()
            currentKeys = 0
            listener?.onPadKeysChanged(0)
            invalidate()
        }
    }

    private fun publishKeys() {
        var keys = 0
        val nowPressed = HashSet<PadElement>()
        for (t in touches.values) {
            keys = keys or t.keys
            val e = t.element ?: continue
            if (t.keys != 0 || e == PadElement.MENU || e == PadElement.FAST_FORWARD || e == PadElement.REWIND) {
                nowPressed.add(e)
            }
        }
        val newlyPressed = keys and currentKeys.inv()
        if (newlyPressed != 0 && hapticsEnabled) haptics.tick()
        val changed = keys != currentKeys || nowPressed != pressed
        if (keys != currentKeys) {
            currentKeys = keys
            listener?.onPadKeysChanged(keys)
        }
        if (changed) {
            pressed.clear()
            pressed.addAll(nowPressed)
            invalidate()
        }
    }

    private fun keyFor(element: PadElement?) = when (element) {
        PadElement.A -> GbaKeys.A
        PadElement.B -> GbaKeys.B
        PadElement.L -> GbaKeys.L
        PadElement.R -> GbaKeys.R
        PadElement.START -> GbaKeys.START
        PadElement.SELECT -> GbaKeys.SELECT
        else -> 0
    }

    private fun dpadKeys(r: RectF, x: Float, y: Float): Int {
        val dx = x - r.centerX()
        val dy = y - r.centerY()
        if (hypot(dx, dy) < r.width() * 0.09f) return 0
        val degrees = (Math.toDegrees(atan2(-dy, dx).toDouble()) + 360.0) % 360.0
        return if (diagonals) {
            when (((degrees + 22.5) / 45.0).toInt() % 8) {
                0 -> GbaKeys.RIGHT
                1 -> GbaKeys.RIGHT or GbaKeys.UP
                2 -> GbaKeys.UP
                3 -> GbaKeys.UP or GbaKeys.LEFT
                4 -> GbaKeys.LEFT
                5 -> GbaKeys.LEFT or GbaKeys.DOWN
                6 -> GbaKeys.DOWN
                else -> GbaKeys.DOWN or GbaKeys.RIGHT
            }
        } else {
            when (((degrees + 45.0) / 90.0).toInt() % 4) {
                0 -> GbaKeys.RIGHT
                1 -> GbaKeys.UP
                2 -> GbaKeys.LEFT
                else -> GbaKeys.DOWN
            }
        }
    }

    /** Closest visible element whose (slightly enlarged) area contains the point. */
    private fun hitTest(x: Float, y: Float, candidates: Collection<PadElement>): PadElement? {
        var best: PadElement? = null
        var bestDistance = Float.MAX_VALUE
        for (element in candidates) {
            if (!isShown(element)) continue
            val r = rects[element] ?: continue
            val slop = if (element == PadElement.DPAD) 0.15f else 0.25f
            val halfW = r.width() / 2 * (1 + slop)
            val halfH = r.height() / 2 * (1 + slop) + (if (element.shape == PadElement.Shape.PILL) 6 * density else 0f)
            val dx = x - r.centerX()
            val dy = y - r.centerY()
            val inside = if (element.shape == PadElement.Shape.CIRCLE) {
                hypot(dx, dy) <= halfW
            } else {
                kotlin.math.abs(dx) <= halfW && kotlin.math.abs(dy) <= halfH
            }
            if (inside) {
                val d = hypot(dx, dy)
                if (d < bestDistance) {
                    bestDistance = d
                    best = element
                }
            }
        }
        return best
    }

    // ------------------------------------------------------------- edit mode

    private fun onEditTouch(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selected = hitTest(event.x, event.y, PadElement.entries)
                selected?.let { e ->
                    val r = rects[e]!!
                    dragOffsetX = event.x - r.centerX()
                    dragOffsetY = event.y - r.centerY()
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val e = selected ?: return true
                    val p = layout[e] ?: return true
                    p.x = ((event.x - dragOffsetX) / width).coerceIn(0f, 1f)
                    p.y = ((event.y - dragOffsetY) / height).coerceIn(0f, 1f)
                    computeRects()
                    invalidate()
                }
            }
        }
        return true
    }

    private companion object {
        val SLIDABLE = listOf(PadElement.A, PadElement.B, PadElement.L, PadElement.R)
    }
}
