package de.krazey.utcomp.dashboard.logging

import java.io.BufferedReader
import java.util.Locale
import java.util.concurrent.CancellationException

internal data class CsvViewerSeries(
    val id: String,
    val label: String,
    val unit: String,
    val decimals: Int,
    val color: Int,
    val sourceColumn: String,
)

internal data class CsvViewerRow(
    val time: String,
    val wallTimeMs: Long?,
    val elapsedRealtimeMs: Long?,
    val values: FloatArray,
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
    val totalRows: Long,
    val graphRows: List<CsvViewerRow>,
    val availableSeries: List<CsvViewerSeries>,
    val series: List<CsvViewerSeries>,
    val sourceColumns: String,
    val firstTime: String,
    val lastTime: String,
    val durationText: String,
    val sampleRateHz: Float,
    val stats: CsvViewerStats,
)

internal object CsvViewerSeriesCatalog {
    private data class Definition(
        val id: String,
        val label: String,
        val unit: String,
        val decimals: Int,
        val color: Int,
        val aliases: List<String>,
        val defaultVisible: Boolean = false,
    )

    private const val GREEN = 0xFF6CD284.toInt()
    private const val BLUE = 0xFF52A4FF.toInt()
    private const val ORANGE = 0xFFFFBE4C.toInt()
    private const val RED = 0xFFFF7070.toInt()
    private const val CYAN = 0xFF58D6DD.toInt()
    private const val PURPLE = 0xFFB985FF.toInt()
    private const val YELLOW = 0xFFFFDF61.toInt()
    private const val PINK = 0xFFFF83C8.toInt()
    private const val LIME = 0xFFA5E65C.toInt()
    private const val WHITE = 0xFFE6EAF2.toInt()

    private val palette = intArrayOf(
        GREEN, BLUE, ORANGE, RED, CYAN, PURPLE, YELLOW, PINK, LIME, WHITE,
    )

    private fun definition(
        id: String,
        label: String,
        vararg aliases: String,
        unit: String = "",
        decimals: Int = 2,
        colorIndex: Int,
        defaultVisible: Boolean = false,
    ): Definition = Definition(
        id = id,
        label = label,
        unit = unit,
        decimals = decimals,
        color = palette[colorIndex % palette.size],
        aliases = aliases.toList(),
        defaultVisible = defaultVisible,
    )

    private val definitions = listOf(
        definition("afr1", "AFR", "afr1", "adc_in_ch1", decimals = 2, colorIndex = 0, defaultVisible = true),
        definition("boost", "Boost", "bar1", "adc_in_ch3", unit = "bar", decimals = 2, colorIndex = 1, defaultVisible = true),
        definition("oil_pressure", "Oil P", "bar2", "adc_in_ch4", unit = "bar", decimals = 2, colorIndex = 2, defaultVisible = true),
        definition("oil_temp", "Oil T", "temperature_ntc1_c", "temperature_oil_c", unit = "°C", decimals = 1, colorIndex = 3, defaultVisible = true),
        definition("rpm", "RPM", "rpm", unit = "rpm", decimals = 0, colorIndex = 4),
        definition("speed", "Speed", "vss_speed_200ms", "vss_speed_1s", unit = "km/h", decimals = 1, colorIndex = 5),
        definition("gear", "Gear", "gear_no", decimals = 0, colorIndex = 6),
        definition("afr2", "AFR 2", "afr2", decimals = 2, colorIndex = 7),
        definition("bar3", "Bar 3", "bar3", unit = "bar", decimals = 2, colorIndex = 8),
        definition("outside_temp", "Outside T", "temperature_outside_c", unit = "°C", decimals = 1, colorIndex = 4),
        definition("inside_temp", "Inside T", "temperature_inside_c", unit = "°C", decimals = 1, colorIndex = 5),
        definition("engine_temp", "Engine T", "temperature_engine_c", unit = "°C", decimals = 1, colorIndex = 6),
        definition("oil_temp_direct", "Oil T direct", "temperature_oil_c", unit = "°C", decimals = 1, colorIndex = 7),
        definition("ntc2", "NTC 2", "temperature_ntc2_c", unit = "°C", decimals = 1, colorIndex = 8),
        definition("ntc3", "NTC 3", "temperature_ntc3_c", unit = "°C", decimals = 1, colorIndex = 9),
        definition("ds_a", "DS A", "temperature_ds_a_c", unit = "°C", decimals = 1, colorIndex = 0),
        definition("ds_b", "DS B", "temperature_ds_b_c", unit = "°C", decimals = 1, colorIndex = 1),
        definition("ds_c", "DS C", "temperature_ds_c_c", unit = "°C", decimals = 1, colorIndex = 2),
        definition("ds_d", "DS D", "temperature_ds_d_c", unit = "°C", decimals = 1, colorIndex = 3),
        definition("egt1", "EGT 1", "egt1", unit = "°C", decimals = 1, colorIndex = 4),
        definition("egt2", "EGT 2", "egt2", unit = "°C", decimals = 1, colorIndex = 5),
        definition("egt3", "EGT 3", "egt3", unit = "°C", decimals = 1, colorIndex = 6),
        definition("egt4", "EGT 4", "egt4", unit = "°C", decimals = 1, colorIndex = 7),
        definition("egt5", "EGT 5", "egt5", unit = "°C", decimals = 1, colorIndex = 8),
        definition("egt6", "EGT 6", "egt6", unit = "°C", decimals = 1, colorIndex = 9),
        definition("consumption_current", "Consumption", "consumption_cur", decimals = 2, colorIndex = 0),
        definition("consumption_average", "Consumption avg", "consumption_avg", decimals = 2, colorIndex = 1),
        definition("fuel_pb", "Fuel PB", "fuel_left_pb", decimals = 2, colorIndex = 2),
        definition("fuel_lpg", "Fuel LPG", "fuel_left_lpg", decimals = 2, colorIndex = 3),
        definition("injection", "Injection", "injection_time_1s", decimals = 2, colorIndex = 4),
        definition("trip_distance", "Trip distance", "trip_dist", decimals = 2, colorIndex = 5),
        definition("trip_consumption", "Trip consumption", "trip_cons", decimals = 2, colorIndex = 6),
        definition("trip_average_speed", "Trip avg speed", "trip_vavg", unit = "km/h", decimals = 1, colorIndex = 7),
        definition("vmax", "Vmax", "vmax", unit = "km/h", decimals = 1, colorIndex = 8),
        definition("adc0", "ADC 0", "adc_in_ch0", decimals = 3, colorIndex = 0),
        definition("adc1", "ADC 1", "adc_in_ch1", decimals = 3, colorIndex = 1),
        definition("adc2", "ADC 2", "adc_in_ch2", decimals = 3, colorIndex = 2),
        definition("adc3", "ADC 3", "adc_in_ch3", decimals = 3, colorIndex = 3),
        definition("adc4", "ADC 4", "adc_in_ch4", decimals = 3, colorIndex = 4),
        definition("adc5", "ADC 5", "adc_in_ch5", decimals = 3, colorIndex = 5),
        definition("adc6", "ADC 6", "adc_in_ch6", decimals = 3, colorIndex = 6),
        definition("adc7", "ADC 7", "adc_in_ch7", decimals = 3, colorIndex = 7),
        definition("vref", "Vref", "vref", decimals = 3, colorIndex = 8),
    )

    val defaultSeriesIds: Set<String> = definitions
        .filter { it.defaultVisible }
        .mapTo(linkedSetOf()) { it.id }

    private data class ResolvedSeries(
        val series: CsvViewerSeries,
        val columnIndex: Int,
        val defaultVisible: Boolean,
    )

    private fun resolve(headers: List<String>): List<ResolvedSeries> {
        val lowerHeaders = headers.map { it.lowercase(Locale.US) }
        return definitions.mapNotNull { definition ->
            val columnIndex = definition.aliases.firstNotNullOfOrNull { alias ->
                lowerHeaders.indexOf(alias.lowercase(Locale.US)).takeIf { it >= 0 }
            } ?: return@mapNotNull null
            ResolvedSeries(
                series = CsvViewerSeries(
                    id = definition.id,
                    label = definition.label,
                    unit = definition.unit,
                    decimals = definition.decimals,
                    color = definition.color,
                    sourceColumn = headers[columnIndex],
                ),
                columnIndex = columnIndex,
                defaultVisible = definition.defaultVisible,
            )
        }
    }

    internal fun resolveAvailable(headers: List<String>): List<CsvViewerSeries> =
        resolve(headers).map { it.series }

    internal fun resolveColumns(headers: List<String>): Map<String, Int> =
        resolve(headers).associate { it.series.id to it.columnIndex }

    internal fun defaultAvailableIds(headers: List<String>): LinkedHashSet<String> =
        resolve(headers)
            .filter { it.defaultVisible }
            .mapTo(linkedSetOf()) { it.series.id }
}

internal object CsvLogPreviewReader {
    private const val PROGRESS_ROW_INTERVAL = 16_384L

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

    fun read(
        reader: BufferedReader,
        maxGraphPoints: Int,
        selectedSeriesIds: Set<String> = emptySet(),
        isCancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {},
    ): CsvViewerPreview {
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

        val availableSeries = CsvViewerSeriesCatalog.resolveAvailable(headers)
        val columnsBySeriesId = CsvViewerSeriesCatalog.resolveColumns(headers)
        val requestedIds = if (selectedSeriesIds.isEmpty()) {
            CsvViewerSeriesCatalog.defaultAvailableIds(headers)
        } else {
            LinkedHashSet(selectedSeriesIds)
        }
        var displayedSeries = availableSeries.filter { it.id in requestedIds }
        if (displayedSeries.isEmpty()) {
            val defaultIds = CsvViewerSeriesCatalog.defaultAvailableIds(headers)
            displayedSeries = availableSeries.filter { it.id in defaultIds }
        }
        if (displayedSeries.isEmpty()) displayedSeries = availableSeries.take(4)

        fun seriesColumn(id: String): Int = columnsBySeriesId[id] ?: -1

        val afrIndex = seriesColumn("afr1")
        val boostIndex = seriesColumn("boost")
        val oilPressureIndex = seriesColumn("oil_pressure")
        val oilTempIndex = seriesColumn("oil_temp")

        val sourceColumns = listOf(
            "time=${headers.getOrNull(timeIndex) ?: "?"}",
            "afr=${headers.getOrNull(afrIndex) ?: "?"}",
            "boost=${headers.getOrNull(boostIndex) ?: "?"}",
            "oilP=${headers.getOrNull(oilPressureIndex) ?: "?"}",
            "oilT=${headers.getOrNull(oilTempIndex) ?: "?"}",
        ).joinToString(" · ")

        val selectedIndexes = IntArray(7 + displayedSeries.size)
        selectedIndexes[0] = wallTimeIndex
        selectedIndexes[1] = elapsedTimeIndex
        selectedIndexes[2] = timeIndex
        selectedIndexes[3] = afrIndex
        selectedIndexes[4] = boostIndex
        selectedIndexes[5] = oilPressureIndex
        selectedIndexes[6] = oilTempIndex
        displayedSeries.forEachIndexed { index, series ->
            selectedIndexes[7 + index] = seriesColumn(series.id)
        }

        val fieldSelector = CsvFieldSelector(selectedIndexes)
        val graphRows = ArrayList<CsvViewerRow>(maxGraphPoints)
        var graphStride = 1L
        var total = 0L
        var firstTime = ""
        var lastTimeRaw = ""
        var firstWallTimeMs: Long? = null
        var lastWallTimeMs: Long? = null
        var firstElapsedMs: Long? = null
        var lastElapsedMs: Long? = null

        val afrRange = RangeAccumulator()
        val boostRange = RangeAccumulator()
        val oilPressureRange = RangeAccumulator()
        val oilTempRange = RangeAccumulator()

        while (true) {
            if (isCancelled()) throw CancellationException("CSV viewer closed")

            val line = reader.readLine() ?: break
            if (line.isBlank()) continue

            val fields = fieldSelector.select(line)
            total++

            val wallTimeMs = fields[0].toLongOrNullCompat()
            val elapsedMs = fields[1].toLongOrNullCompat()
            val rawTime = fields[2].orEmpty()
            val afr = fields[3].toFloatOrNan()
            val boost = fields[4].toFloatOrNan()
            val oilPressure = fields[5].toFloatOrNan()
            val oilTemp = fields[6].toFloatOrNan()

            if (firstTime.isBlank()) firstTime = compactCsvTime(rawTime)
            lastTimeRaw = rawTime
            if (firstWallTimeMs == null && wallTimeMs != null) firstWallTimeMs = wallTimeMs
            if (wallTimeMs != null) lastWallTimeMs = wallTimeMs
            if (firstElapsedMs == null && elapsedMs != null) firstElapsedMs = elapsedMs
            if (elapsedMs != null) lastElapsedMs = elapsedMs

            afrRange.add(afr)
            boostRange.add(boost)
            oilPressureRange.add(oilPressure)
            oilTempRange.add(oilTemp)

            if (total % graphStride == 0L) {
                graphRows += CsvViewerRow(
                    time = compactCsvTime(rawTime),
                    wallTimeMs = wallTimeMs,
                    elapsedRealtimeMs = elapsedMs,
                    values = FloatArray(displayedSeries.size) { index ->
                        fields[7 + index].toFloatOrNan()
                    },
                )
                if (graphRows.size > maxGraphPoints * 2) {
                    var writeIndex = 0
                    var readIndex = 0
                    while (readIndex < graphRows.size) {
                        graphRows[writeIndex++] = graphRows[readIndex]
                        readIndex += 2
                    }
                    while (graphRows.size > writeIndex) {
                        graphRows.removeAt(graphRows.lastIndex)
                    }
                    graphStride *= 2L
                }
            }

            if (total % PROGRESS_ROW_INTERVAL == 0L) onProgress(total)
        }
        onProgress(total)

        if (graphRows.size > maxGraphPoints) {
            val keepEvery = graphRows.size.toDouble() / maxGraphPoints.toDouble()
            val compacted = ArrayList<CsvViewerRow>(maxGraphPoints)
            var next = 0.0
            for (index in graphRows.indices) {
                if (index + 0.001 >= next) {
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
        val sampleRate = if (durationMs > 0L && total > 1L) {
            (total - 1L).toFloat() / (durationMs.toFloat() / 1000f)
        } else {
            Float.NaN
        }

        return CsvViewerPreview(
            totalRows = total,
            graphRows = graphRows.toList(),
            availableSeries = availableSeries,
            series = displayedSeries,
            sourceColumns = sourceColumns,
            firstTime = firstTime.ifBlank { "—" },
            lastTime = compactCsvTime(lastTimeRaw).ifBlank { "—" },
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

            fun selectedPositions(): IntArray? = positionsByColumn.getOrNull(column)

            fun storeField() {
                val positions = selectedPositions() ?: return
                val value = field.toString()
                for (position in positions) result[position] = value
            }

            while (index < line.length) {
                val char = line[index]
                when {
                    char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
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
            totalRows = 0L,
            graphRows = emptyList(),
            availableSeries = emptyList(),
            series = emptyList(),
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
