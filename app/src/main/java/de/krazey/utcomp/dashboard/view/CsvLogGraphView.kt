package de.krazey.utcomp.dashboard.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import de.krazey.utcomp.dashboard.util.fixed
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A graph lane available in the CSV viewer. */
data class CsvLogGraphSeries(
    val id: String,
    val label: String,
    val unit: String,
    val decimals: Int,
    val color: Int,
)

data class CsvLogGraphPoint(
    val time: String,
    val values: FloatArray,
)

data class CsvLogGraphMarker(
    val seriesLabel: String,
    val value: Float,
    val label: String,
    val color: Int,
)

class CsvLogGraphView(context: Context) : View(context) {
    companion object {
        private const val DEFAULT_SCROLL_SPAN = 0.42f
    }

    private val rows = ArrayList<CsvLogGraphPoint>()
    private val series = ArrayList<CsvLogGraphSeries>()
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

    private val density = resources.displayMetrics.density
    private val strokeScale = density.coerceIn(1f, 1.55f)
    private val touchSlopPx = dp(12f)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(5, 7, 11)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke(1.2f)
        color = Color.rgb(54, 60, 72)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke(0.8f)
        color = Color.argb(95, 92, 100, 118)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(218, 222, 230)
        textSize = dp(24f)
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 158, 174)
        textSize = dp(19f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke(2.1f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke(1.35f)
        alpha = 190
    }
    private val markerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(17f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke(1.6f)
        color = Color.argb(220, 238, 242, 255)
    }
    private val cursorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(210, 10, 12, 18)
    }
    private val cursorTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(238, 242, 255)
        textSize = dp(18f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val plotRect = RectF()
    private val path = Path()

    fun setData(
        newRows: List<CsvLogGraphPoint>,
        newSeries: List<CsvLogGraphSeries>,
    ) {
        rows.clear()
        rows.addAll(newRows)
        series.clear()
        series.addAll(newSeries)
        viewportStart = 0f
        viewportEnd = 1f
        selectedIndex = if (rows.isEmpty()) -1 else rows.lastIndex
        minimumHeight = desiredGraphHeight()
        requestLayout()
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
                    if (!isTimeZoomed()) enterScrollMode(downX)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }

                if (graphDragActive) {
                    val dx = event.x - lastTouchX
                    if (abs(dx) > 0.5f) panByPixels(dx)
                    updateCursorFromX(event.x)
                    lastTouchX = event.x
                    invalidate()
                }

                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) pinchActive = false
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)

                if (!verticalScrollActive && !pinchMoved && !graphDragActive) {
                    updateCursorFromX(event.x)
                    performClick()

                    val isDoubleTap =
                        event.eventTime - lastTapUpMs <= 320L &&
                            abs(event.x - lastTapX) <= dp(48f)
                    if (isDoubleTap) resetZoom()

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
            dp(18f),
            dp(18f),
            bgPaint,
        )

        if (rows.isEmpty() || series.isEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("No CSV data rows selected", width / 2f, height / 2f, textPaint)
            return
        }

        val leftPad = plotLeft()
        val rightPad = dp(86f)
        val topPad = dp(46f)
        val bottomPad = dp(48f)
        val gap = dp(10f)
        val usableHeight = height - topPad - bottomPad
        val laneHeight = max(
            dp(72f),
            (usableHeight - gap * (series.size - 1)) / series.size.toFloat(),
        )
        val plotLeft = leftPad
        val plotRight = width - rightPad
        val plotWidth = max(1f, plotRight - plotLeft)

        val (startIndex, endIndex) = visibleIndexRange()
        val visibleCount = endIndex - startIndex + 1

        drawHeader(canvas, plotLeft)

        series.forEachIndexed { seriesIndex, item ->
            val top = topPad + seriesIndex * (laneHeight + gap)
            val bottom = top + laneHeight
            plotRect.set(plotLeft, top, plotRight, bottom)

            canvas.drawRoundRect(plotRect, dp(9f), dp(9f), borderPaint)
            drawGrid(canvas, plotRect)

            var rawMinValue = Float.NaN
            var rawMaxValue = Float.NaN
            for (rowIndex in startIndex..endIndex) {
                val value = valueAt(rows[rowIndex], seriesIndex)
                if (!value.isFinite()) continue
                rawMinValue = if (rawMinValue.isNaN()) value else min(rawMinValue, value)
                rawMaxValue = if (rawMaxValue.isNaN()) value else max(rawMaxValue, value)
            }
            val (minValue, maxValue) = paddedValueRange(rawMinValue, rawMaxValue)
            val span = if (minValue.isFinite() && maxValue.isFinite()) {
                max(0.0001f, maxValue - minValue)
            } else {
                Float.NaN
            }

            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.color = item.color
            textPaint.textSize = dp(22f)
            canvas.drawText(item.label, plotLeft - dp(10f), top + dp(25f), textPaint)

            smallTextPaint.textAlign = Paint.Align.LEFT
            smallTextPaint.textSize = dp(18f)
            smallTextPaint.color = Color.rgb(150, 158, 174)
            val rangeText = if (minValue.isFinite() && maxValue.isFinite()) {
                "${fmt(minValue, item)} → ${fmt(maxValue, item)}"
            } else {
                "--"
            }
            canvas.drawText(rangeText, plotLeft + dp(8f), top + dp(22f), smallTextPaint)

            if (span.isFinite()) drawMarkers(canvas, item, plotRect, minValue, span)

            if (visibleCount >= 2 && span.isFinite()) {
                path.reset()
                var started = false
                for (rowIndex in startIndex..endIndex) {
                    val value = valueAt(rows[rowIndex], seriesIndex)
                    if (!value.isFinite()) {
                        started = false
                        continue
                    }

                    val visibleIndex = rowIndex - startIndex
                    val x = plotLeft +
                        plotWidth * visibleIndex.toFloat() /
                        (visibleCount - 1).toFloat()
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
                    seriesIndex = seriesIndex,
                    rect = plotRect,
                    startIndex = startIndex,
                    endIndex = endIndex,
                    minValue = minValue,
                    span = span,
                )
            }
        }

        val chartBottom = topPad + series.size * laneHeight + (series.size - 1) * gap
        smallTextPaint.color = Color.rgb(150, 158, 174)
        smallTextPaint.textSize = dp(18f)
        smallTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(rows[startIndex].time, plotLeft, chartBottom + dp(34f), smallTextPaint)
        smallTextPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(rows[endIndex].time, plotRight, chartBottom + dp(34f), smallTextPaint)

        drawCursorLine(
            canvas = canvas,
            startIndex = startIndex,
            endIndex = endIndex,
            plotLeft = plotLeft,
            plotRight = plotRight,
            top = topPad,
            bottom = chartBottom,
        )
    }

    private fun drawHeader(
        canvas: Canvas,
        plotLeft: Float,
    ) {
        val xZoom = 1f / (viewportEnd - viewportStart).coerceAtLeast(0.0001f)

        smallTextPaint.textSize = dp(17f)
        smallTextPaint.textAlign = Paint.Align.LEFT
        smallTextPaint.color = Color.rgb(176, 184, 198)
        canvas.drawText(
            "drag left/right · scroll vertically · pinch zoom · double-tap fit · x${xZoom.fixed(1)}",
            plotLeft,
            dp(24f),
            smallTextPaint,
        )

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
        canvas.drawRoundRect(
            x - dp(56f),
            bottom + dp(5f),
            x + dp(56f),
            bottom + dp(31f),
            dp(8f),
            dp(8f),
            cursorFillPaint,
        )
        cursorTextPaint.textAlign = Paint.Align.CENTER
        cursorTextPaint.textSize = dp(17f)
        cursorTextPaint.color = Color.rgb(238, 242, 255)
        canvas.drawText(rows[selected].time.takeLast(12), x, bottom + dp(25f), cursorTextPaint)
    }

    private fun drawCursorValueIfNeeded(
        canvas: Canvas,
        series: CsvLogGraphSeries,
        seriesIndex: Int,
        rect: RectF,
        startIndex: Int,
        endIndex: Int,
        minValue: Float,
        span: Float,
    ) {
        val selected = selectedIndex
        if (selected !in startIndex..endIndex || endIndex <= startIndex) return

        val value = valueAt(rows[selected], seriesIndex)
        if (!value.isFinite()) return

        val x = rect.left +
            rect.width() * (selected - startIndex).toFloat() /
            (endIndex - startIndex).toFloat()
        val normalized = ((value - minValue) / span).coerceIn(0f, 1f)
        val y = rect.bottom - normalized * rect.height()

        pointPaint.color = series.color
        canvas.drawCircle(x, y, stroke(3.8f), pointPaint)

        val label = fmt(value, series)
        cursorTextPaint.textSize = dp(17f)
        cursorTextPaint.color = series.color
        cursorTextPaint.textAlign = if (x < rect.centerX()) Paint.Align.LEFT else Paint.Align.RIGHT
        val labelX = if (x < rect.centerX()) x + dp(8f) else x - dp(8f)
        val labelY = (y - dp(8f)).coerceIn(
            rect.top + dp(18f),
            rect.bottom - dp(4f),
        )
        canvas.drawText(label, labelX, labelY, cursorTextPaint)
        cursorTextPaint.color = Color.rgb(238, 242, 255)
    }

    private fun drawMarkers(
        canvas: Canvas,
        series: CsvLogGraphSeries,
        rect: RectF,
        minValue: Float,
        span: Float,
    ) {
        var visibleMarkerIndex = 0
        for (marker in markers) {
            if (marker.seriesLabel != series.label || !marker.value.isFinite()) continue

            val normalized = (marker.value - minValue) / span
            if (normalized < 0f || normalized > 1f) continue

            val y = rect.bottom - normalized * rect.height()
            markerPaint.color = marker.color
            markerPaint.alpha = 185
            canvas.drawLine(rect.left, y, rect.right, y, markerPaint)

            markerTextPaint.color = marker.color
            markerTextPaint.textAlign = Paint.Align.RIGHT
            val labelY = (y - dp(4f) - visibleMarkerIndex * dp(2f))
                .coerceIn(rect.top + dp(16f), rect.bottom - dp(4f))
            canvas.drawText(marker.label, rect.right - dp(4f), labelY, markerTextPaint)
            visibleMarkerIndex++
        }
    }

    private fun drawGrid(canvas: Canvas, rect: RectF) {
        canvas.drawLine(rect.left, rect.centerY(), rect.right, rect.centerY(), gridPaint)
        for (index in 1 until 4) {
            val x = rect.left + rect.width() * index / 4f
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

    private fun enterScrollMode(focusX: Float) {
        if (rows.size < 3 || isTimeZoomed()) return
        val focusFrac = xToPlotFraction(focusX)
        val span = DEFAULT_SCROLL_SPAN.coerceAtLeast(minViewportSpan())
        val focusValue = focusFrac
        viewportStart = (focusValue - span * focusFrac).coerceIn(0f, 1f - span)
        viewportEnd = viewportStart + span
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
        val plotWidth = max(1f, width - plotLeft() - dp(86f))
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
        var start = (viewportStart.coerceIn(0f, 1f) * last)
            .roundToInt()
            .coerceIn(0, last)
        var end = (viewportEnd.coerceIn(0f, 1f) * last)
            .roundToInt()
            .coerceIn(0, last)

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
        val right = width - dp(86f)
        return ((x - left) / max(1f, right - left)).coerceIn(0f, 1f)
    }

    private fun isTimeZoomed(): Boolean = viewportEnd - viewportStart < 0.995f

    private fun minViewportSpan(): Float {
        if (rows.size <= 1) return 1f
        return min(1f, max(0.018f, 18f / rows.lastIndex.toFloat()))
    }

    private fun desiredGraphHeight(): Int {
        val laneCount = series.size.coerceAtLeast(1)
        return (dp(46f) + dp(48f) + laneCount * dp(96f) +
            (laneCount - 1).coerceAtLeast(0) * dp(10f)).roundToInt()
    }

    private fun valueAt(row: CsvLogGraphPoint, seriesIndex: Int): Float =
        row.values.getOrElse(seriesIndex) { Float.NaN }

    private fun plotLeft(): Float = dp(92f)

    private fun dp(value: Float): Float = value * density

    private fun stroke(value: Float): Float = value * strokeScale

    private fun fmt(value: Float, series: CsvLogGraphSeries): String {
        if (!value.isFinite()) return "--"
        val number = value.fixed(series.decimals)
        return if (series.unit.isBlank()) number else "$number ${series.unit}"
    }
}
