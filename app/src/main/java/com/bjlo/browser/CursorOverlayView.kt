package com.bjlo.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Cursor virtual para controlar el WebView con el D-pad del control remoto.
 * Es solo un dibujo: no recibe toques ni foco.
 */
class CursorOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var cx = 0f
        private set
    var cy = 0f
        private set

    var cursorEnabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF0071E3.toInt()
        strokeWidth = 3f * density
    }
    private var placed = false

    init {
        isFocusable = false
        isClickable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (!placed && w > 0 && h > 0) {
            cx = w / 2f
            cy = h / 2f
            placed = true
        } else {
            moveBy(0f, 0f)
        }
    }

    /** Mueve el cursor y lo mantiene dentro de la vista. */
    fun moveBy(dx: Float, dy: Float) {
        cx = (cx + dx).coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat())
        cy = (cy + dy).coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat())
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!cursorEnabled) return
        val r = 9f * density
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, ring)
    }
}
