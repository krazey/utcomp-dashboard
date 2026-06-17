package de.krazey.utcomp.probe.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class RalliartBoostGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var minValue: Float = -1.0f
        set(value) {
            field = value
            invalidate()
        }

    var maxValue: Float = 2.0f
        set(value) {
            field = value
            invalidate()
        }

    var currentValue: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var centerZero: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var accentColor: Int = Color.rgb(220, 64, 64)
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
        color = Color.rgb(42, 45, 54)
        strokeWidth = 11f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val negativePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 42, 48)
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        alpha = 170
    }

    private val warningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 190, 58)
        strokeWidth = 9f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        alpha = 210
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 12f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 22f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        alpha = 42
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(145, 152, 165)
        strokeWidth = 2f
    }

    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(84, 90, 104)
        strokeWidth = 1.4f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(156, 164, 178)
        textSize = 12f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(235, 58, 64)
        strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND
    }

    private val needleShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 0, 0)
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        alpha = 130
    }

    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(205, 210, 220)
        style = Paint.Style.FILL
    }

    private val hubShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 0, 0)
        style = Paint.Style.FILL
        alpha = 150
    }

    private val arcRect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = (188f * resources.displayMetrics.density).toInt().coerceAtLeast(156)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 12f
        val size = min(w - pad * 2f, h * 1.86f)
        val left = (w - size) / 2f
        val top = -6f
        arcRect.set(left, top, left + size, top + size)

        val start = 205f
        val sweep = 130f

        canvas.drawArc(arcRect, start, sweep, false, trackPaint)

        val zeroAngle = angleFor(0f, start, sweep)
        val minAngle = angleFor(minValue, start, sweep)
        val maxAngle = angleFor(maxValue, start, sweep)
        canvas.drawArc(arcRect, minAngle, zeroAngle - minAngle, false, negativePaint)

        if (!warningValue.isNaN()) {
            val warnAngle = angleFor(warningValue, start, sweep)
            canvas.drawArc(arcRect, warnAngle, maxAngle - warnAngle, false, warningPaint)
        }

        drawTicks(canvas, start, sweep)
        drawScaleLabels(canvas, start, sweep)

        val valueColor = accentForValue()
        valuePaint.color = valueColor
        glowPaint.color = valueColor

        val valueAngle = angleFor(currentValue.coerceIn(minValue, maxValue), start, sweep)
        val arcStart = if (centerZero && minValue < 0f && maxValue > 0f) zeroAngle else start
        val arcSweep = if (centerZero && minValue < 0f && maxValue > 0f) {
            valueAngle - zeroAngle
        } else {
            sweep * normalized(currentValue)
        }

        if (kotlin.math.abs(arcSweep) > 0.6f) {
            canvas.drawArc(arcRect, arcStart, arcSweep, false, glowPaint)
            canvas.drawArc(arcRect, arcStart, arcSweep, false, valuePaint)
        }

        drawNeedle(canvas, valueAngle)
    }

    private fun drawScaleLabels(canvas: Canvas, start: Float, sweep: Float) {
        val values = floatArrayOf(-1.0f, -0.5f, 0.0f, 0.5f, 1.0f, 1.5f, 2.0f)
        values.forEach { value ->
            val angle = angleFor(value, start, sweep)
            val label = when (value) {
                -1.0f -> "-1"
                -0.5f -> "-0.5"
                0.0f -> "0"
                0.5f -> "0.5"
                1.0f -> "1"
                1.5f -> "1.5"
                else -> "2.0"
            }
            drawScaleLabel(canvas, angle, label, value >= 2.0f)
        }

    }

    private fun drawScaleLabel(canvas: Canvas, angleDeg: Float, label: String, warning: Boolean) {
        val angle = Math.toRadians(angleDeg.toDouble())
        val cx = arcRect.centerX()
        val cy = arcRect.centerY()
        val rx = arcRect.width() / 2f - 44f
        val ry = arcRect.height() / 2f - 44f

        labelPaint.color = if (warning) Color.rgb(255, 190, 58) else Color.rgb(156, 164, 178)
        val x = cx + cos(angle).toFloat() * rx
        val y = cy + sin(angle).toFloat() * ry + 5f
        canvas.drawText(label, x, y, labelPaint)
    }

    private fun drawTicks(canvas: Canvas, start: Float, sweep: Float) {
        for (i in 0..30) {
            val frac = i / 30f
            val major = i % 5 == 0
            val angle = start + sweep * frac
            val paint = if (major) tickPaint else minorTickPaint
            drawRadialTick(canvas, angle, if (major) 18f else 10f, paint)
        }
    }

    private fun drawRadialTick(canvas: Canvas, angleDeg: Float, tickLength: Float, paint: Paint) {
        val angle = Math.toRadians(angleDeg.toDouble())
        val cx = arcRect.centerX()
        val cy = arcRect.centerY()
        val rx = arcRect.width() / 2f
        val ry = arcRect.height() / 2f
        val outerRx = rx + 1f
        val outerRy = ry + 1f
        val innerRx = rx - tickLength
        val innerRy = ry - tickLength

        val x1 = cx + cos(angle).toFloat() * innerRx
        val y1 = cy + sin(angle).toFloat() * innerRy
        val x2 = cx + cos(angle).toFloat() * outerRx
        val y2 = cy + sin(angle).toFloat() * outerRy
        canvas.drawLine(x1, y1, x2, y2, paint)
    }

    private fun drawNeedle(canvas: Canvas, angleDeg: Float) {
        val angle = Math.toRadians(angleDeg.toDouble())
        val cx = arcRect.centerX()
        val cy = arcRect.centerY()
        val radius = arcRect.width() / 2f
        val needleLength = radius - 24f

        val x = cx + cos(angle).toFloat() * needleLength
        val y = cy + sin(angle).toFloat() * needleLength

        canvas.drawLine(cx, cy, x, y, needleShadowPaint)
        canvas.drawLine(cx, cy, x, y, needlePaint)
        canvas.drawCircle(cx, cy, 9f, hubShadowPaint)
        canvas.drawCircle(cx, cy, 6f, hubPaint)
    }

    private fun angleFor(value: Float, start: Float, sweep: Float): Float =
        start + sweep * normalized(value)

    private fun normalized(value: Float): Float {
        if (value.isNaN() || value.isInfinite() || maxValue <= minValue) return 0f
        return ((value - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
    }

    private fun accentForValue(): Int {
        if (currentValue.isNaN() || currentValue.isInfinite()) return Color.rgb(70, 80, 96)

        if (!criticalValue.isNaN() && currentValue >= criticalValue) {
            return Color.rgb(255, 72, 72)
        }
        if (!warningValue.isNaN() && currentValue >= warningValue) {
            return Color.rgb(255, 190, 58)
        }
        return accentColor
    }
}
