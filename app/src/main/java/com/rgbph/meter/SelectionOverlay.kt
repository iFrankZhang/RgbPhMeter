package com.rgbph.meter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 可拖动 / 缩放的选框。
 * 框内拖动 = 移动；拖右下角圆点 = 缩放；在框外拖动 = 重新画框。
 */
class SelectionOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    val rect = RectF()
    var onChanged: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val handleR = 11f * d
    private val minSize = 16f * d

    private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 5f * d
    }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#0F7C8C"); strokeWidth = 2.5f * d
        pathEffect = DashPathEffect(floatArrayOf(10f * d, 6f * d), 0f)
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0F7C8C") }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2.5f * d
    }

    private enum class Mode { NONE, MOVE, RESIZE, NEW }
    private var mode = Mode.NONE
    private var dx = 0f
    private var dy = 0f
    private var ox = 0f
    private var oy = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (rect.isEmpty && w > 0 && h > 0) {
            val side = min(w, h) * 0.2f
            rect.set(w / 2f - side / 2, h / 2f - side / 2, w / 2f + side / 2, h / 2f + side / 2)
            onChanged?.invoke()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(rect, outer)
        canvas.drawRect(rect, inner)
        canvas.drawCircle(rect.right, rect.bottom, handleR, handleFill)
        canvas.drawCircle(rect.right, rect.bottom, handleR, handleStroke)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x.coerceIn(0f, width.toFloat())
        val y = e.y.coerceIn(0f, height.toFloat())
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                mode = when {
                    hypot(x - rect.right, y - rect.bottom) < handleR * 2.2f -> Mode.RESIZE
                    rect.contains(x, y) -> { dx = x - rect.left; dy = y - rect.top; Mode.MOVE }
                    else -> { ox = x; oy = y; Mode.NEW }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    Mode.MOVE -> {
                        val w = rect.width(); val h = rect.height()
                        val l = (x - dx).coerceIn(0f, width - w)
                        val t = (y - dy).coerceIn(0f, height - h)
                        rect.set(l, t, l + w, t + h)
                    }
                    Mode.RESIZE -> {
                        rect.right = max(rect.left + minSize, x)
                        rect.bottom = max(rect.top + minSize, y)
                    }
                    Mode.NEW -> {
                        if (abs(x - ox) > minSize / 2 || abs(y - oy) > minSize / 2) {
                            rect.set(min(ox, x), min(oy, y), max(ox, x), max(oy, y))
                            if (rect.width() < minSize) rect.right = rect.left + minSize
                            if (rect.height() < minSize) rect.bottom = rect.top + minSize
                        }
                    }
                    Mode.NONE -> {}
                }
                invalidate(); onChanged?.invoke()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = Mode.NONE
        }
        return true
    }
}
