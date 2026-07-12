package de.krazey.utcomp.dashboard.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class RalliartAfrDebugBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var minValue: Float = 10.0f
        set(value) {
            if (field.toBits() == value.toBits()) return
            field = value
            invalidate()
        }

    var maxValue: Float = 20.0f
        set(value) {
            if (field.toBits() == value.toBits()) return
            field = value
            invalidate()
        }

    var showDebugGuides: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 255, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 255, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 255, 255)
        textSize = 16f
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showDebugGuides) return

        val left = 0f
        val right = width.toFloat()
        val midY = height * 0.45f
        canvas.drawRect(left, midY - 8f, right, midY + 8f, linePaint)

        val values = listOf(10f, 12f, 14f, 16f, 18f, 20f)
        values.forEach { value ->
            val fraction = if (maxValue > minValue) {
                ((value - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
            } else {
                0f
            }
            val x = left + (right - left) * fraction
            canvas.drawLine(x, midY - 14f, x, midY + 14f, tickPaint)
            canvas.drawText(
                if (value % 1f == 0f) value.toInt().toString() else value.toString(),
                x,
                height.toFloat() - 4f,
                textPaint,
            )
        }
    }
}
