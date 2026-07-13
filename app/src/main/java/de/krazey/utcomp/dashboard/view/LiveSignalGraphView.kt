package de.krazey.utcomp.dashboard.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import de.krazey.utcomp.dashboard.logging.LiveSignalBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class LiveSignalGraphView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val strokeScale = density.coerceIn(1f, 1.55f)

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(5, 7, 11)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke(1.2f)
        color = Color.rgb(48, 62, 76)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke(0.8f)
        color = Color.argb(90, 92, 105, 120)
    }
    private val rawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke(1.7f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(82, 164, 255)
    }
    private val smoothedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke(2.0f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(255, 190, 76)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(218, 224, 234)
        textSize = dp(15f)
    }
    private val secondaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(155, 166, 182)
        textSize = dp(12f)
    }
    private val rawPath = Path()
    private val smoothedPath = Path()
    private val plotRect = RectF()

    var buffer: LiveSignalBuffer? = null
    var windowMs: Long = 30_000L
    var unit: String = ""
    var decimals: Int = 2

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            dp(16f),
            dp(16f),
            backgroundPaint,
        )

        val data = buffer
        if (data == null || data.size < 2) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("Waiting for live samples…", width / 2f, height / 2f, textPaint)
            return
        }

        val left = dp(16f)
        val right = width - dp(74f)
        val top = dp(30f)
        val bottom = height - dp(34f)
        plotRect.set(left, top, right, bottom)
        canvas.drawRoundRect(plotRect, dp(8f), dp(8f), borderPaint)
        drawGrid(canvas)

        val first = data.firstVisibleIndex(windowMs)
        val last = data.size - 1
        val firstTime = data.timeAt(first)
        val lastTime = data.timeAt(last)
        val timeSpan = max(1L, lastTime - firstTime)

        var minValue = Float.POSITIVE_INFINITY
        var maxValue = Float.NEGATIVE_INFINITY
        for (index in first..last) {
            val raw = data.rawAt(index)
            val smooth = data.smoothedAt(index)
            if (raw.isFinite()) {
                minValue = min(minValue, raw)
                maxValue = max(maxValue, raw)
            }
            if (smooth.isFinite()) {
                minValue = min(minValue, smooth)
                maxValue = max(maxValue, smooth)
            }
        }
        if (!minValue.isFinite() || !maxValue.isFinite()) return
        val baseSpan = maxValue - minValue
        val pad = when {
            baseSpan > 0.0001f -> baseSpan * 0.12f
            abs(maxValue) > 0.01f -> abs(maxValue) * 0.04f
            else -> 0.1f
        }
        minValue -= pad
        maxValue += pad
        val valueSpan = max(0.0001f, maxValue - minValue)

        rawPath.reset()
        smoothedPath.reset()
        var rawStarted = false
        var smoothStarted = false
        for (index in first..last) {
            val x = plotRect.left +
                (data.timeAt(index) - firstTime).toFloat() / timeSpan * plotRect.width()
            val raw = data.rawAt(index)
            if (raw.isFinite()) {
                val y = plotRect.bottom - (raw - minValue) / valueSpan * plotRect.height()
                if (rawStarted) rawPath.lineTo(x, y) else {
                    rawPath.moveTo(x, y)
                    rawStarted = true
                }
            }
            val smooth = data.smoothedAt(index)
            if (smooth.isFinite()) {
                val y = plotRect.bottom - (smooth - minValue) / valueSpan * plotRect.height()
                if (smoothStarted) smoothedPath.lineTo(x, y) else {
                    smoothedPath.moveTo(x, y)
                    smoothStarted = true
                }
            }
        }
        if (rawStarted) canvas.drawPath(rawPath, rawPaint)
        if (smoothStarted) canvas.drawPath(smoothedPath, smoothedPaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = rawPaint.color
        canvas.drawText("RAW", plotRect.left, dp(21f), textPaint)
        textPaint.color = smoothedPaint.color
        canvas.drawText("OUTPUT", plotRect.left + dp(54f), dp(21f), textPaint)
        textPaint.color = Color.rgb(218, 224, 234)

        secondaryTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("-${windowMs / 1_000}s", plotRect.left, height - dp(9f), secondaryTextPaint)
        secondaryTextPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("now", plotRect.right, height - dp(9f), secondaryTextPaint)

        secondaryTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(format(maxValue), plotRect.right + dp(7f), plotRect.top + dp(5f), secondaryTextPaint)
        canvas.drawText(format(minValue), plotRect.right + dp(7f), plotRect.bottom, secondaryTextPaint)
    }

    private fun drawGrid(canvas: Canvas) {
        for (step in 1 until 4) {
            val x = plotRect.left + plotRect.width() * step / 4f
            canvas.drawLine(x, plotRect.top, x, plotRect.bottom, gridPaint)
        }
        for (step in 1 until 4) {
            val y = plotRect.top + plotRect.height() * step / 4f
            canvas.drawLine(plotRect.left, y, plotRect.right, y, gridPaint)
        }
    }

    private fun format(value: Float): String {
        if (!value.isFinite()) return "—"
        val text = java.lang.String.format(java.util.Locale.US, "%.${decimals.coerceIn(0, 3)}f", value)
        return if (unit.isBlank()) text else "$text $unit"
    }

    private fun stroke(dp: Float): Float = dp * strokeScale

    private fun dp(value: Float): Float = value * density
}
