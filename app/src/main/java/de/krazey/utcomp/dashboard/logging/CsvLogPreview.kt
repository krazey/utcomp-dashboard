package de.krazey.utcomp.dashboard.logging

import java.io.BufferedReader
import java.util.Locale

internal data class CsvViewerRow(
    val time: String,
    val wallTimeMs: Long?,
    val elapsedRealtimeMs: Long?,
    val afr: Float,
    val boost: Float,
    val oilPressure: Float,
    val oilTemp: Float,
)

internal data class CsvViewerRange(
    val min: Float,
    val max: Float,
)

internal data class CsvViewerStats(
    val afr: CsvViewerRange,
    val boost: CsvViewerRange,
    val oilPressure: CsvViewerRange,
    val oilTemp: CsvViewerRange,
)

internal data class CsvViewerPreview(
    val totalRows: Int,
    val graphRows: List<CsvViewerRow>,
    val sourceColumns: String,
    val firstTime: String,
    val lastTime: String,
    val durationText: String,
    val sampleRateHz: Float,
    val stats: CsvViewerStats,
)

internal object CsvLogPreviewReader {
    private class RangeAccumulator {
        private var minValue = Float.NaN
        private var maxValue = Float.NaN

        fun add(value: Float) {
            if (!value.isFinite()) return
            minValue = if (minValue.isNaN()) value else minOf(minValue, value)
            maxValue = if (maxValue.isNaN()) value else maxOf(maxValue, value)
        }

        fun toRange(): CsvViewerRange = CsvViewerRange(minValue, maxValue)
    }

    fun read(reader: BufferedReader, maxGraphPoints: Int): CsvViewerPreview {
        require(maxGraphPoints > 0) { "maxGraphPoints must be positive" }

        val headerLine = reader.readLine() ?: return emptyPreview("empty file")
        val headers = parseCsvLine(headerLine).map { it.trim() }
        val lowerHeaders = headers.map { it.lowercase(Locale.US) }

        fun indexOfAny(vararg names: String): Int =
            names.firstNotNullOfOrNull { name ->
                lowerHeaders.indexOf(name.lowercase(Locale.US)).takeIf { it >= 0 }
            } ?: -1

        val wallTimeIndex = indexOfAny("wall_time_ms")
        val isoTimeIndex = indexOfAny("wall_time_iso")
        val elapsedTimeIndex = indexOfAny("elapsed_realtime_ms")
        val timeIndex = listOf(isoTimeIndex, wallTimeIndex, elapsedTimeIndex)
            .firstOrNull { it >= 0 } ?: -1
        val afrIndex = indexOfAny("afr1", "adc_in_ch1")
        val boostIndex = indexOfAny("bar1", "adc_in_ch3")
        val oilPressureIndex = indexOfAny("bar2", "adc_in_ch4")
        val oilTempIndex = indexOfAny("temperature_ntc1_c", "temperature_oil_c")

        val sourceColumns = listOf(
            "time=${headers.getOrNull(timeIndex) ?: "?"}",
            "afr=${headers.getOrNull(afrIndex) ?: "?"}",
            "boost=${headers.getOrNull(boostIndex) ?: "?"}",
            "oilP=${headers.getOrNull(oilPressureIndex) ?: "?"}",
            "oilT=${headers.getOrNull(oilTempIndex) ?: "?"}",
        ).joinToString(" · ")

        val selectedIndexes = intArrayOf(
            wallTimeIndex,
            elapsedTimeIndex,
            timeIndex,
            afrIndex,
            boostIndex,
            oilPressureIndex,
            oilTempIndex,
        )
        val fieldSelector = CsvFieldSelector(selectedIndexes)
        val graphRows = ArrayList<CsvViewerRow>(maxGraphPoints)
        var graphStride = 1
        var total = 0
        var firstTime = ""
        var lastTime = ""
        var firstWallTimeMs: Long? = null
        var lastWallTimeMs: Long? = null
        var firstElapsedMs: Long? = null
        var lastElapsedMs: Long? = null

        val afrRange = RangeAccumulator()
        val boostRange = RangeAccumulator()
        val oilPressureRange = RangeAccumulator()
        val oilTempRange = RangeAccumulator()

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue

            val fields = fieldSelector.select(line)
            total++

            val wallTimeMs = fields[0].toLongOrNullCompat()
            val elapsedMs = fields[1].toLongOrNullCompat()
            val displayTime = compactCsvTime(fields[2].orEmpty())
            val row = CsvViewerRow(
                time = displayTime,
                wallTimeMs = wallTimeMs,
                elapsedRealtimeMs = elapsedMs,
                afr = fields[3].toFloatOrNan(),
                boost = fields[4].toFloatOrNan(),
                oilPressure = fields[5].toFloatOrNan(),
                oilTemp = fields[6].toFloatOrNan(),
            )

            if (firstTime.isBlank()) firstTime = displayTime
            lastTime = displayTime
            if (firstWallTimeMs == null && wallTimeMs != null) firstWallTimeMs = wallTimeMs
            if (wallTimeMs != null) lastWallTimeMs = wallTimeMs
            if (firstElapsedMs == null && elapsedMs != null) firstElapsedMs = elapsedMs
            if (elapsedMs != null) lastElapsedMs = elapsedMs

            afrRange.add(row.afr)
            boostRange.add(row.boost)
            oilPressureRange.add(row.oilPressure)
            oilTempRange.add(row.oilTemp)

            if (total % graphStride == 0) {
                graphRows += row
                if (graphRows.size > maxGraphPoints * 2) {
                    val compacted = ArrayList<CsvViewerRow>(maxGraphPoints + 1)
                    for (index in graphRows.indices step 2) {
                        compacted += graphRows[index]
                    }
                    graphRows.clear()
                    graphRows.addAll(compacted)
                    graphStride *= 2
                }
            }
        }

        if (graphRows.size > maxGraphPoints) {
            val keepEvery = graphRows.size.toFloat() / maxGraphPoints.toFloat()
            val compacted = ArrayList<CsvViewerRow>(maxGraphPoints)
            var next = 0f
            for (index in graphRows.indices) {
                if (index + 0.001f >= next) {
                    compacted += graphRows[index]
                    next += keepEvery
                }
            }
            graphRows.clear()
            graphRows.addAll(compacted.take(maxGraphPoints))
        }

        val durationMs =
            durationMs(firstWallTimeMs, lastWallTimeMs)
                ?: durationMs(firstElapsedMs, lastElapsedMs)
                ?: 0L
        val sampleRate = if (durationMs > 0L && total > 1) {
            (total - 1).toFloat() / (durationMs.toFloat() / 1000f)
        } else {
            Float.NaN
        }

        return CsvViewerPreview(
            totalRows = total,
            graphRows = graphRows.toList(),
            sourceColumns = sourceColumns,
            firstTime = firstTime.ifBlank { "—" },
            lastTime = lastTime.ifBlank { "—" },
            durationText = formatDuration(durationMs),
            sampleRateHz = sampleRate,
            stats = CsvViewerStats(
                afr = afrRange.toRange(),
                boost = boostRange.toRange(),
                oilPressure = oilPressureRange.toRange(),
                oilTemp = oilTempRange.toRange(),
            ),
        )
    }

    internal fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0

        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    fields += current.toString()
                    current.setLength(0)
                }
                else -> current.append(char)
            }
            index++
        }
        fields += current.toString()
        return fields
    }

    /**
     * Parses only the requested columns, avoiding a List and String allocation
     * for every unused field in wide high-resolution log rows.
     */
    internal fun selectCsvFields(
        line: String,
        requestedIndexes: IntArray,
    ): Array<String?> = CsvFieldSelector(requestedIndexes).select(line)

    private class CsvFieldSelector(requestedIndexes: IntArray) {
        private val positionsByColumn: Array<IntArray?>
        private val resultSize = requestedIndexes.size

        init {
            val maxColumn = requestedIndexes.maxOrNull()?.coerceAtLeast(-1) ?: -1
            val positions = Array(maxColumn + 1) { mutableListOf<Int>() }
            requestedIndexes.forEachIndexed { resultIndex, columnIndex ->
                if (columnIndex >= 0) positions[columnIndex] += resultIndex
            }
            positionsByColumn = Array(positions.size) { column ->
                positions[column].takeIf { it.isNotEmpty() }?.toIntArray()
            }
        }

        fun select(line: String): Array<String?> {
            val result = arrayOfNulls<String>(resultSize)
            if (positionsByColumn.isEmpty()) return result

            val field = StringBuilder()
            var quoted = false
            var column = 0
            var index = 0

            fun selectedPositions(): IntArray? =
                positionsByColumn.getOrNull(column)

            fun storeField() {
                val positions = selectedPositions() ?: return
                val value = field.toString()
                for (position in positions) result[position] = value
            }

            while (index < line.length) {
                val char = line[index]
                when {
                    char == '"' && quoted &&
                        index + 1 < line.length && line[index + 1] == '"' -> {
                        if (selectedPositions() != null) field.append('"')
                        index++
                    }
                    char == '"' -> quoted = !quoted
                    char == ',' && !quoted -> {
                        storeField()
                        field.setLength(0)
                        column++
                        if (column >= positionsByColumn.size && !quoted) break
                    }
                    selectedPositions() != null -> field.append(char)
                }
                index++
            }
            storeField()
            return result
        }
    }

    private fun emptyPreview(sourceColumns: String): CsvViewerPreview =
        CsvViewerPreview(
            totalRows = 0,
            graphRows = emptyList(),
            sourceColumns = sourceColumns,
            firstTime = "—",
            lastTime = "—",
            durationText = "—",
            sampleRateHz = Float.NaN,
            stats = CsvViewerStats(
                afr = CsvViewerRange(Float.NaN, Float.NaN),
                boost = CsvViewerRange(Float.NaN, Float.NaN),
                oilPressure = CsvViewerRange(Float.NaN, Float.NaN),
                oilTemp = CsvViewerRange(Float.NaN, Float.NaN),
            ),
        )

    private fun durationMs(startMs: Long?, endMs: Long?): Long? =
        if (startMs != null && endMs != null && endMs >= startMs) {
            endMs - startMs
        } else {
            null
        }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "—"
        val totalSeconds = ms / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val hours = minutes / 60L
        val minutePart = minutes % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%dh %02dm %02ds", hours, minutePart, seconds)
        } else {
            String.format(Locale.US, "%dm %02ds", minutes, seconds)
        }
    }

    private fun compactCsvTime(raw: String): String {
        val value = raw.trim().trim('"')
        val tIndex = value.indexOf('T')
        if (tIndex >= 0 && tIndex + 1 < value.length) {
            return value.substring(tIndex + 1)
                .substringBefore('+')
                .substringBefore('Z')
        }
        return value
    }

    private fun String?.toFloatOrNan(): Float =
        this?.trim()?.trim('"')?.toFloatOrNull() ?: Float.NaN

    private fun String?.toLongOrNullCompat(): Long? =
        this?.trim()?.trim('"')?.toLongOrNull()
}
