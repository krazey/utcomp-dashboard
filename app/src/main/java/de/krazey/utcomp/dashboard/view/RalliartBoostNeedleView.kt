package de.krazey.utcomp.dashboard.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class RalliartBoostNeedleView @JvmOverloads constructor(
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

    var warningValue: Float = 2.0f
        set(value) {
            field = value
            invalidate()
        }

    var showDebugGuides: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 38, 48)
        strokeWidth = 5.0f
        strokeCap = Paint.Cap.ROUND
    }

    private val needleShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 0, 0)
        alpha = 160
        strokeWidth = 10.0f
        strokeCap = Paint.Cap.ROUND
    }

    private val needleHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 255, 225, 225)
        strokeWidth = 1.5f
        strokeCap = Paint.Cap.ROUND
    }

    private val debugCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val debugMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 255, 255)
        style = Paint.Style.FILL
    }

    private val debugTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 255, 255)
        textSize = 18f
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val value = currentValue.coerceIn(minValue, maxValue)
        val fraction = if (maxValue > minValue) {
            ((value - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
        } else {
            0f
        }

        val angle = Math.toRadians((90.0 + 270.0 * fraction))
        val cx = width * 0.50f
        val cy = height * 0.50f
        val outerRadius = min(width, height) * 0.43f
        val innerRadius = min(width, height) * 0.24f

        if (showDebugGuides) {
            canvas.drawCircle(cx, cy, outerRadius, debugCirclePaint)
            canvas.drawCircle(cx, cy, innerRadius, debugCirclePaint)

            listOf(-1.0f, 0.0f, 1.0f, 2.0f).forEach { markerValue ->
                val markerFraction = if (maxValue > minValue) {
                    ((markerValue - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val markerAngle = Math.toRadians(90.0 + 270.0 * markerFraction)
                val mx = cx + cos(markerAngle).toFloat() * outerRadius
                val my = cy + sin(markerAngle).toFloat() * outerRadius
                canvas.drawCircle(mx, my, 5f, debugMarkerPaint)
                canvas.drawText(
                    if (markerValue % 1f == 0f) markerValue.toInt().toString() else markerValue.toString(),
                    cx + cos(markerAngle).toFloat() * (outerRadius + 24f),
                    my + 8f,
                    debugTextPaint,
                )
            }
        }

        val x1 = cx + cos(angle).toFloat() * innerRadius
        val y1 = cy + sin(angle).toFloat() * innerRadius
        val x2 = cx + cos(angle).toFloat() * outerRadius
        val y2 = cy + sin(angle).toFloat() * outerRadius

        val color = if (!warningValue.isNaN() && value >= warningValue) {
            Color.rgb(255, 198, 64)
        } else {
            Color.rgb(232, 38, 48)
        }
        needlePaint.color = color

        canvas.drawLine(x1, y1, x2, y2, needleShadowPaint)
        canvas.drawLine(x1, y1, x2, y2, needlePaint)

        val hx2 = cx + cos(angle).toFloat() * (outerRadius * 0.88f)
        val hy2 = cy + sin(angle).toFloat() * (outerRadius * 0.88f)
        canvas.drawLine(x1, y1, hx2, hy2, needleHighlightPaint)
    }
}
