package de.krazey.utcomp.probe.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class CsvLogGraphPoint(
    val time: String,
    val afr: Float,
    val boost: Float,
    val oilPressure: Float,
    val oilTemp: Float,
)

data class CsvLogGraphMarker(
    val seriesLabel: String,
    val value: Float,
    val label: String,
    val color: Int,
)

class CsvLogGraphView(context: Context) : View(context) {
    private data class Series(
        val label: String,
        val unit: String,
        val color: Int,
        val read: (CsvLogGraphPoint) -> Float,
    )

    private val rows = ArrayList<CsvLogGraphPoint>()
    private val markers = ArrayList<CsvLogGraphMarker>()

    private var viewportStart = 0f
    private var viewportEnd = 1f
    private var selectedIndex = -1

    private var downX = 0f
    private var downY = 0f
    private var lastTouchX = 0f
    private var lastTapUpMs = 0L
    private var lastTapX = 0f

    private var graphDragActive = false
    private var verticalScrollActive = false
    private var pinchActive = false
    private var pinchMoved = false
    private var lastPinchDx = 1f

    private val touchSlopPx = 12f * resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(5, 7, 11)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.rgb(54, 60, 72)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.argb(95, 92, 100, 118)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(218, 222, 230)
        textSize = 24f
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 158, 174)
        textSize = 19f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 190
    }
    private val markerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 17f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.argb(220, 238, 242, 255)
    }
    private val cursorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(210, 10, 12, 18)
    }
    private val cursorTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(238, 242, 255)
        textSize = 18f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val plotRect = RectF()
    private val path = Path()

    private val series = listOf(
        Series("AFR", "", Color.rgb(108, 210, 132)) { it.afr },
        Series("Boost", "bar", Color.rgb(82, 164, 255)) { it.boost },
        Series("Oil P", "bar", Color.rgb(255, 190, 76)) { it.oilPressure },
        Series("Oil T", "°C", Color.rgb(255, 112, 112)) { it.oilTemp },
    )

    fun setRows(newRows: List<CsvLogGraphPoint>) {
        rows.clear()
        rows.addAll(newRows)
        viewportStart = 0f
        viewportEnd = 1f
        selectedIndex = if (rows.isEmpty()) -1 else rows.lastIndex
        invalidate()
    }

    fun setMarkers(newMarkers: List<CsvLogGraphMarker>) {
        markers.clear()
        markers.addAll(newMarkers)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (rows.isEmpty()) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                graphDragActive = false
                verticalScrollActive = false
                pinchActive = false
                pinchMoved = false
                parent?.requestDisallowInterceptTouchEvent(false)
                updateCursorFromX(event.x)
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    beginPinch(event)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    updatePinch(event)
                    return true
                }

                if (verticalScrollActive) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }

                val totalDx = event.x - downX
                val totalDy = event.y - downY
                val absDx = abs(totalDx)
                val absDy = abs(totalDy)

                if (!graphDragActive && max(absDx, absDy) > touchSlopPx) {
                    if (absDy > absDx * 1.25f) {
                        verticalScrollActive = true
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return false
                    }

                    graphDragActive = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }

                if (graphDragActive || absDx > touchSlopPx * 0.5f) {
                    val dx = event.x - lastTouchX
                    if (isTimeZoomed() && abs(dx) > 0.5f) {
                        panByPixels(dx)
                    }
                    updateCursorFromX(event.x)
                    lastTouchX = event.x
                    invalidate()
                }

                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    pinchActive = false
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)

                if (!verticalScrollActive && !pinchMoved) {
                    updateCursorFromX(event.x)
                    performClick()

                    val isDoubleTap =
                        event.eventTime - lastTapUpMs <= 320L &&
                            abs(event.x - lastTapX) <= 48f
                    if (isDoubleTap) {
                        resetZoom()
                    }

                    lastTapUpMs = event.eventTime
                    lastTapX = event.x
                }

                graphDragActive = false
                verticalScrollActive = false
                pinchActive = false
                pinchMoved = false
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                graphDragActive = false
                verticalScrollActive = false
                pinchActive = false
                pinchMoved = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawRoundRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            18f,
            18f,
            bgPaint,
        )

        if (rows.isEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("No CSV data", width / 2f, height / 2f, textPaint)
            return
        }

        val leftPad = plotLeft()
        val rightPad = 86f
        val topPad = 46f
        val bottomPad = 48f
        val gap = 10f
        val usableHeight = height - topPad - bottomPad
        val laneHeight = (usableHeight - gap * (series.size - 1)) / series.size.toFloat()
        val plotLeft = leftPad
        val plotRight = width - rightPad
        val plotWidth = max(1f, plotRight - plotLeft)

        val (startIndex, endIndex) = visibleIndexRange()
        val visibleRows = rows.subList(startIndex, endIndex + 1)

        drawHeader(canvas, startIndex, endIndex, plotLeft, plotRight)

        series.forEachIndexed { index, item ->
            val top = topPad + index * (laneHeight + gap)
            val bottom = top + laneHeight
            plotRect.set(plotLeft, top, plotRight, bottom)

            canvas.drawRoundRect(plotRect, 9f, 9f, borderPaint)
            drawGrid(canvas, plotRect)

            val values = visibleRows.map(item.read).filter { it.isFinite() }
            val rawMinValue = values.minOrNull() ?: Float.NaN
            val rawMaxValue = values.maxOrNull() ?: Float.NaN
            val (minValue, maxValue) = paddedValueRange(rawMinValue, rawMaxValue)
            val span = if (minValue.isFinite() && maxValue.isFinite()) {
                max(0.0001f, maxValue - minValue)
            } else {
                Float.NaN
            }

            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.color = item.color
            textPaint.textSize = 22f
            canvas.drawText(item.label, plotLeft - 10f, top + 25f, textPaint)

            smallTextPaint.textAlign = Paint.Align.LEFT
            smallTextPaint.textSize = 18f
            smallTextPaint.color = Color.rgb(150, 158, 174)
            val rangeText = if (minValue.isFinite() && maxValue.isFinite()) {
                "${fmt(minValue, item)} → ${fmt(maxValue, item)}"
            } else {
                "--"
            }
            canvas.drawText(rangeText, plotLeft + 8f, top + 22f, smallTextPaint)

            if (span.isFinite()) {
                drawMarkers(canvas, item, plotRect, minValue, span)
            }

            if (visibleRows.size >= 2 && span.isFinite()) {
                path.reset()
                var started = false
                visibleRows.forEachIndexed { visibleIndex, row ->
                    val value = item.read(row)
                    if (!value.isFinite()) {
                        started = false
                        return@forEachIndexed
                    }

                    val x = plotLeft + plotWidth * visibleIndex.toFloat() / (visibleRows.size - 1).toFloat()
                    val normalized = ((value - minValue) / span).coerceIn(0f, 1f)
                    val y = bottom - normalized * laneHeight

                    if (!started) {
                        path.moveTo(x, y)
                        started = true
                    } else {
                        path.lineTo(x, y)
                    }
                }

                linePaint.color = item.color
                canvas.drawPath(path, linePaint)

                drawCursorValueIfNeeded(
                    canvas = canvas,
                    series = item,
                    rect = plotRect,
                    startIndex = startIndex,
                    endIndex = endIndex,
                    minValue = minValue,
                    span = span,
                )
            }
        }

        smallTextPaint.color = Color.rgb(150, 158, 174)
        smallTextPaint.textSize = 18f
        smallTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(rows[startIndex].time, plotLeft, height - 12f, smallTextPaint)
        smallTextPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(rows[endIndex].time, plotRight, height - 12f, smallTextPaint)

        drawCursorLine(canvas, startIndex, endIndex, plotLeft, plotRight, topPad, height - bottomPad)
    }

    private fun drawHeader(canvas: Canvas, startIndex: Int, endIndex: Int, plotLeft: Float, plotRight: Float) {
        val xZoom = 1f / (viewportEnd - viewportStart).coerceAtLeast(0.0001f)
        val selected = selectedIndex.takeIf { it in rows.indices }
        val selectedRow = selected?.let { rows[it] }

        smallTextPaint.textSize = 18f
        smallTextPaint.textAlign = Paint.Align.LEFT
        smallTextPaint.color = Color.rgb(176, 184, 198)
        canvas.drawText(
            "scroll vertically · tap/drag cursor · 2-finger X zoom · double-tap reset · x${String.format(Locale.US, "%.1f", xZoom)}",
            plotLeft,
            24f,
            smallTextPaint,
        )

        if (selectedRow != null) {
            cursorTextPaint.textAlign = Paint.Align.RIGHT
            cursorTextPaint.textSize = 18f
            canvas.drawText(
                "cursor ${selectedRow.time.takeLast(12)}  AFR ${fmt(selectedRow.afr, series[0])}  Boost ${fmt(selectedRow.boost, series[1])}  OilP ${fmt(selectedRow.oilPressure, series[2])}  OilT ${fmt(selectedRow.oilTemp, series[3])}",
                plotRight,
                24f,
                cursorTextPaint,
            )
        } else {
            smallTextPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("${rows[startIndex].time.takeLast(12)} → ${rows[endIndex].time.takeLast(12)}", plotRight, 24f, smallTextPaint)
        }
    }

    private fun drawCursorLine(
        canvas: Canvas,
        startIndex: Int,
        endIndex: Int,
        plotLeft: Float,
        plotRight: Float,
        top: Float,
        bottom: Float,
    ) {
        val selected = selectedIndex
        if (selected !in startIndex..endIndex || endIndex <= startIndex) return

        val frac = (selected - startIndex).toFloat() / (endIndex - startIndex).toFloat()
        val x = plotLeft + (plotRight - plotLeft) * frac
        canvas.drawLine(x, top, x, bottom, cursorPaint)

        cursorFillPaint.alpha = 185
        canvas.drawRoundRect(x - 56f, bottom + 5f, x + 56f, bottom + 31f, 8f, 8f, cursorFillPaint)
        cursorTextPaint.textAlign = Paint.Align.CENTER
        cursorTextPaint.textSize = 17f
        canvas.drawText(rows[selected].time.takeLast(12), x, bottom + 25f, cursorTextPaint)
    }

    private fun drawCursorValueIfNeeded(
        canvas: Canvas,
        series: Series,
        rect: RectF,
        startIndex: Int,
        endIndex: Int,
        minValue: Float,
        span: Float,
    ) {
        val selected = selectedIndex
        if (selected !in startIndex..endIndex || endIndex <= startIndex) return

        val value = series.read(rows[selected])
        if (!value.isFinite()) return

        val x = rect.left + rect.width() * (selected - startIndex).toFloat() / (endIndex - startIndex).toFloat()
        val normalized = ((value - minValue) / span).coerceIn(0f, 1f)
        val y = rect.bottom - normalized * rect.height()

        pointPaint.color = series.color
        canvas.drawCircle(x, y, 5.5f, pointPaint)

        val label = fmt(value, series)
        cursorTextPaint.textSize = 17f
        cursorTextPaint.color = series.color
        cursorTextPaint.textAlign = if (x < rect.centerX()) Paint.Align.LEFT else Paint.Align.RIGHT
        val labelX = if (x < rect.centerX()) x + 8f else x - 8f
        val labelY = (y - 8f).coerceIn(rect.top + 18f, rect.bottom - 4f)
        canvas.drawText(label, labelX, labelY, cursorTextPaint)
        cursorTextPaint.color = Color.rgb(238, 242, 255)
    }

    private fun drawMarkers(canvas: Canvas, series: Series, rect: RectF, minValue: Float, span: Float) {
        val seriesMarkers = markers.filter { it.seriesLabel == series.label && it.value.isFinite() }
        if (seriesMarkers.isEmpty()) return

        seriesMarkers.forEachIndexed { index, marker ->
            val normalized = ((marker.value - minValue) / span)
            if (normalized < 0f || normalized > 1f) return@forEachIndexed

            val y = rect.bottom - normalized * rect.height()
            markerPaint.color = marker.color
            markerPaint.alpha = 185
            canvas.drawLine(rect.left, y, rect.right, y, markerPaint)

            markerTextPaint.color = marker.color
            markerTextPaint.textAlign = Paint.Align.RIGHT
            val labelY = (y - 4f - index * 2f).coerceIn(rect.top + 16f, rect.bottom - 4f)
            canvas.drawText(marker.label, rect.right - 8f, labelY, markerTextPaint)
        }
    }

    private fun drawGrid(canvas: Canvas, rect: RectF) {
        val midY = rect.centerY()
        canvas.drawLine(rect.left, midY, rect.right, midY, gridPaint)
        for (i in 1 until 4) {
            val x = rect.left + rect.width() * i / 4f
            canvas.drawLine(x, rect.top, x, rect.bottom, gridPaint)
        }
    }

    private fun beginPinch(event: MotionEvent) {
        lastPinchDx = pinchDx(event)
        pinchActive = true
        pinchMoved = false
    }

    private fun updatePinch(event: MotionEvent) {
        if (!pinchActive) {
            beginPinch(event)
            return
        }

        val dx = pinchDx(event)
        val scaleX = dx / lastPinchDx.coerceAtLeast(1f)

        if (scaleX.isFinite() && abs(scaleX - 1f) > 0.015f) {
            zoomTime(scaleX, focusX(event))
            lastPinchDx = dx
            pinchMoved = true
            updateCursorFromX(focusX(event))
            invalidate()
        }
    }

    private fun pinchDx(event: MotionEvent): Float =
        if (event.pointerCount >= 2) {
            abs(event.getX(1) - event.getX(0)).coerceAtLeast(1f)
        } else {
            1f
        }

    private fun focusX(event: MotionEvent): Float =
        if (event.pointerCount >= 2) {
            (event.getX(0) + event.getX(1)) * 0.5f
        } else {
            event.x
        }

    private fun zoomTime(scale: Float, focusX: Float) {
        if (rows.size < 3) return

        val oldStart = viewportStart
        val oldEnd = viewportEnd
        val oldSpan = oldEnd - oldStart
        val focusFrac = xToPlotFraction(focusX)
        val newSpan = (oldSpan / scale).coerceIn(minViewportSpan(), 1f)
        val focusValue = oldStart + oldSpan * focusFrac

        viewportStart = (focusValue - newSpan * focusFrac).coerceIn(0f, 1f - newSpan)
        viewportEnd = viewportStart + newSpan
    }

    private fun updateCursorFromX(x: Float) {
        if (rows.isEmpty()) {
            selectedIndex = -1
            return
        }

        val (start, end) = visibleIndexRange()
        if (end <= start) {
            selectedIndex = start.coerceIn(0, rows.lastIndex)
            return
        }

        val frac = xToPlotFraction(x)
        selectedIndex = (start + frac * (end - start).toFloat())
            .roundToInt()
            .coerceIn(start, end)
    }

    private fun panByPixels(dx: Float) {
        val plotWidth = max(1f, width - plotLeft() - 86f)
        val span = viewportEnd - viewportStart
        val shift = -dx / plotWidth * span

        viewportStart = (viewportStart + shift).coerceIn(0f, 1f - span)
        viewportEnd = viewportStart + span
    }

    private fun resetZoom() {
        viewportStart = 0f
        viewportEnd = 1f
        selectedIndex = if (rows.isEmpty()) -1 else rows.lastIndex
    }

    private fun visibleIndexRange(): Pair<Int, Int> {
        if (rows.isEmpty()) return 0 to 0
        if (rows.size == 1) return 0 to 0

        val last = rows.lastIndex
        var start = (viewportStart.coerceIn(0f, 1f) * last).roundToInt().coerceIn(0, last)
        var end = (viewportEnd.coerceIn(0f, 1f) * last).roundToInt().coerceIn(0, last)

        if (end <= start) end = (start + 1).coerceAtMost(last)
        if (end - start < 2 && rows.size > 2) {
            end = (start + 2).coerceAtMost(last)
            start = (end - 2).coerceAtLeast(0)
        }

        return start to end
    }

    private fun paddedValueRange(minValue: Float, maxValue: Float): Pair<Float, Float> {
        if (!minValue.isFinite() || !maxValue.isFinite()) {
            return Float.NaN to Float.NaN
        }

        val center = (minValue + maxValue) * 0.5f
        val rawSpan = max(0.0001f, maxValue - minValue)
        val paddedSpan = max(rawSpan * 1.08f, minimumVisibleValueSpan(rawSpan))

        return (center - paddedSpan * 0.5f) to (center + paddedSpan * 0.5f)
    }

    private fun minimumVisibleValueSpan(rawSpan: Float): Float =
        when {
            rawSpan >= 10f -> 1f
            rawSpan >= 1f -> 0.1f
            else -> 0.01f
        }

    private fun xToPlotFraction(x: Float): Float {
        val left = plotLeft()
        val right = width - 86f
        return ((x - left) / max(1f, right - left)).coerceIn(0f, 1f)
    }

    private fun isTimeZoomed(): Boolean =
        viewportEnd - viewportStart < 0.995f

    private fun minViewportSpan(): Float {
        if (rows.size <= 1) return 1f
        return min(1f, max(0.018f, 18f / rows.lastIndex.toFloat()))
    }

    private fun plotLeft(): Float = 92f

    private fun fmt(value: Float, series: Series): String {
        if (!value.isFinite()) return "--"
        val decimals = when (series.label) {
            "Oil T" -> 1
            else -> 2
        }
        val number = String.format(Locale.US, "%.${decimals}f", value)
        return if (series.unit.isBlank()) number else "$number ${series.unit}"
    }
}
