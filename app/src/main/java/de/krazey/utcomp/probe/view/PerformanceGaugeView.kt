package de.krazey.utcomp.probe.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

class PerformanceGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var minValue: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var maxValue: Float = 1f
        set(value) {
            field = value
            invalidate()
        }

    var currentValue: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var centerZero: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var accentColor: Int = Color.rgb(0, 210, 255)
        set(value) {
            field = value
            invalidate()
        }

    var warningValue: Float = Float.NaN
        set(value) {
            field = value
            invalidate()
        }

    var criticalValue: Float = Float.NaN
        set(value) {
            field = value
            invalidate()
        }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(42, 48, 62)
        strokeWidth = 12f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 12f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 22f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        alpha = 48
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(88, 98, 116)
        strokeWidth = 2f
    }

    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 225, 235)
        strokeWidth = 3f
    }

    private val arcRect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = (72f * resources.displayMetrics.density).toInt().coerceAtLeast(56)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val pad = 18f
        val top = 8f
        val bottom = height.toFloat() + 54f
        arcRect.set(pad, top, width.toFloat() - pad, bottom)

        val startAngle = 205f
        val sweepAngle = 130f

        canvas.drawArc(arcRect, startAngle, sweepAngle, false, trackPaint)
        drawTicks(canvas)

        fillPaint.color = accentForValue()
        glowPaint.color = fillPaint.color

        if (centerZero && minValue < 0f && maxValue > 0f) {
            drawCenterZeroGauge(canvas)
        } else {
            val frac = normalized(currentValue)
            canvas.drawArc(arcRect, startAngle, sweepAngle * frac, false, glowPaint)
            canvas.drawArc(arcRect, startAngle, sweepAngle * frac, false, fillPaint)
        }
    }

    private fun drawCenterZeroGauge(canvas: Canvas) {
        val startAngle = 205.0f
        val sweepAngle = 130.0f
        val zeroFrac = normalized(0f)
        val zeroAngle = startAngle + sweepAngle * zeroFrac
        val valueFrac = normalized(currentValue)
        val valueAngle = startAngle + sweepAngle * valueFrac

        drawNeedleTick(canvas, zeroAngle, zeroPaint)

        val sweep = valueAngle - zeroAngle
        if (abs(sweep) > 0.8f) {
            canvas.drawArc(arcRect, zeroAngle, sweep, false, glowPaint)
            canvas.drawArc(arcRect, zeroAngle, sweep, false, fillPaint)
        }
    }

    private fun drawTicks(canvas: Canvas) {
        val startAngle = 205.0f
        val sweepAngle = 130.0f
        for (i in 0..6) {
            val angle = Math.toRadians((startAngle + sweepAngle * i / 6f).toDouble())
            val cx = arcRect.centerX()
            val cy = arcRect.centerY()
            val outerRx = arcRect.width() / 2f
            val outerRy = arcRect.height() / 2f
            val innerRx = outerRx - 10f
            val innerRy = outerRy - 10f

            val x1 = cx + kotlin.math.cos(angle).toFloat() * innerRx
            val y1 = cy + kotlin.math.sin(angle).toFloat() * innerRy
            val x2 = cx + kotlin.math.cos(angle).toFloat() * outerRx
            val y2 = cy + kotlin.math.sin(angle).toFloat() * outerRy
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }
    }

    private fun drawNeedleTick(canvas: Canvas, angleDeg: Float, paint: Paint) {
        val angle = Math.toRadians(angleDeg.toDouble())
        val cx = arcRect.centerX()
        val cy = arcRect.centerY()
        val outerRx = arcRect.width() / 2f
        val outerRy = arcRect.height() / 2f
        val innerRx = outerRx - 16f
        val innerRy = outerRy - 16f

        val x1 = cx + kotlin.math.cos(angle).toFloat() * innerRx
        val y1 = cy + kotlin.math.sin(angle).toFloat() * innerRy
        val x2 = cx + kotlin.math.cos(angle).toFloat() * outerRx
        val y2 = cy + kotlin.math.sin(angle).toFloat() * outerRy
        canvas.drawLine(x1, y1, x2, y2, paint)
    }

    private fun normalized(v: Float): Float {
        if (v.isNaN() || v.isInfinite() || maxValue <= minValue) return 0f
        return ((v - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
    }

    private fun accentForValue(): Int {
        if (currentValue.isNaN() || currentValue.isInfinite()) return Color.rgb(70, 80, 96)

        if (!criticalValue.isNaN() && currentValue >= criticalValue) {
            return Color.rgb(255, 72, 72)
        }
        if (!warningValue.isNaN() && currentValue >= warningValue) {
            return Color.rgb(255, 170, 48)
        }

        val frac = normalized(currentValue)
        return when {
            warningValue.isNaN() && criticalValue.isNaN() && frac > 0.88f -> Color.rgb(255, 72, 72)
            warningValue.isNaN() && criticalValue.isNaN() && frac > 0.72f -> Color.rgb(255, 170, 48)
            else -> accentColor
        }
    }
}
