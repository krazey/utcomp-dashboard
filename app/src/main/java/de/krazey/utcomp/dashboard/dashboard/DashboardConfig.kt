package de.krazey.utcomp.dashboard.dashboard

import de.krazey.utcomp.dashboard.utcomp.UtcompDataSnapshot

/**
 * Dashboard configuration model.
 *
 * This mirrors the useful part of the original UTCOMP editor:
 * - pages have a row/column grid
 * - each box selects one sensor
 * - each box can scale value/icon independently
 * - each box can override background/alarm/min/max colors
 * - a page-wide style can later be applied to all boxes on that page
 *
 * The first defaults still match our current presets, but the renderer/editor
 * should move toward using these configs instead of hardcoded card lists.
 */
enum class DashboardSensor(
    val label: String,
    val unit: String,
    val defaultMin: Float,
    val defaultMax: Float,
    val iconResourceName: String,
) {
    BOOST(
        label = "Boost",
        unit = "BAR",
        defaultMin = -1.0f,
        defaultMax = 2.0f,
        iconResourceName = "ic_rcomp_boost_48dp",
    ),
    AFR(
        label = "AFR",
        unit = "",
        defaultMin = 10.0f,
        defaultMax = 22.0f,
        iconResourceName = "ic_rcomp_afr_48dp",
    ),
    OIL_TEMP(
        label = "Oil temp",
        unit = "°C",
        defaultMin = 0.0f,
        defaultMax = 140.0f,
        iconResourceName = "ic_rcomp_oiltemp_48dp",
    ),
    OIL_PRESSURE(
        label = "Oil pressure",
        unit = "BAR",
        defaultMin = 0.0f,
        defaultMax = 8.0f,
        iconResourceName = "ic_rcomp_oilpres_48dp",
    ),
    BATTERY(
        label = "Battery",
        unit = "V",
        defaultMin = 8.0f,
        defaultMax = 16.0f,
        iconResourceName = "ic_rcomp_accu_48dp",
    ),
    OUTSIDE_TEMP(
        label = "Outside",
        unit = "°C",
        defaultMin = -30.0f,
        defaultMax = 80.0f,
        iconResourceName = "ic_rcomp_outside_temp_48dp",
    ),
    INSIDE_TEMP(
        label = "Inside",
        unit = "°C",
        defaultMin = -10.0f,
        defaultMax = 60.0f,
        iconResourceName = "ic_rcomp_inside_temp_48dp",
    ),
    TIME(
        label = "Time",
        unit = "",
        defaultMin = 0.0f,
        defaultMax = 0.0f,
        iconResourceName = "",
    );

    fun readValue(snapshot: UtcompDataSnapshot): Float? =
        when (this) {
            BOOST -> snapshot.bar1
            AFR -> snapshot.afr1
            OIL_TEMP -> snapshot.temperatureNtc1
            OIL_PRESSURE -> snapshot.bar2
            BATTERY -> snapshot.adcInValCh0
            OUTSIDE_TEMP -> snapshot.temperatureDsA
            INSIDE_TEMP -> snapshot.temperatureDsB
            TIME -> null
        }
}

data class DashboardBoxConfig(
    val sensor: DashboardSensor,
    val row: Int,
    val column: Int,
    val rowSpan: Int = 1,
    val columnSpan: Int = 1,

    /**
     * UI scaling. 1.0 is default.
     * These are deliberately separate, like the original UTCOMP editor.
     */
    val valueScale: Float = 1.0f,
    val iconScale: Float = 1.0f,
    val iconValueGapScale: Float = 1.0f,
    val minMaxScale: Float = 1.0f,

    /**
     * Value/gauge scale.
     * This is not a unit conversion; it defines the visible gauge/min/max range.
     */
    val scaleMin: Float = sensor.defaultMin,
    val scaleMax: Float = sensor.defaultMax,

    /**
     * Display formatting.
     *
     * decimalPlaces controls the printed value.
     * splitValueDigits makes the main digits larger and the decimal part smaller,
     * similar to the OEM UTCOMP simple dashboard.
     *
     * smoothingAlpha:
     * - 1.00 = off
     * - lower values = more smoothing
     */
    val decimalPlaces: Int = defaultDecimalPlaces(sensor),
    val splitValueDigits: Boolean = true,
    val smoothingAlpha: Float = defaultSmoothingAlpha(sensor),

    /**
     * Stored as ARGB Ints. Use 0xAARRGGBB.
     */
    val backgroundColor: Int = uncheckedColor(0xFF0B0E14u),
    val foregroundColor: Int = uncheckedColor(0xFFFFFFFFu),
    val unitColor: Int = uncheckedColor(0xFFD2D8E1u),
    val alarmColor: Int = uncheckedColor(0xFFFF4848u),
    val minColor: Int = uncheckedColor(0xFF50AAFFu),
    val maxColor: Int = uncheckedColor(0xFFFF4848u),
    val borderColor: Int = uncheckedColor(0xFFFF6E24u),

    /**
     * Normal value color is separate from background and alarm colors.
     */
    val valueColor: Int = foregroundColor,

    /**
     * First real alarm model.
     *
     * NaN means that side is disabled. This supports both high alarms
     * (oil temp too hot) and low alarms (oil pressure too low) later.
     */
    val warningLow: Float = defaultWarningLow(sensor),
    val criticalLow: Float = defaultCriticalLow(sensor),
    val warningHigh: Float = defaultWarningHigh(sensor),
    val criticalHigh: Float = defaultCriticalHigh(sensor),
    /**
     * UTCOMP-style oil pressure guard:
     * oil pressure low alarm is armed only when boost reaches the arm threshold.
     */
    val oilPressureBoostAlarm: Boolean = sensor == DashboardSensor.OIL_PRESSURE,
    val oilPressureBoostArmBar: Float = defaultOilPressureBoostArmBar(sensor),
    val oilPressureWarningBar: Float = defaultOilPressureWarningBar(sensor),
    val oilPressureCriticalBar: Float = defaultOilPressureCriticalBar(sensor),

    val warningColor: Int = uncheckedColor(0xFFFFAA30u),
    val criticalColor: Int = uncheckedColor(0xFFFF4848u),
    /**
     * Separate value colors for alarm states.
     *
     * Background alarm colors and value alarm colors must not be forced to be
     * the same color, otherwise the value can disappear on warning/critical.
     */
    val warningValueColor: Int = uncheckedColor(0xFF000000u),
    val criticalValueColor: Int = uncheckedColor(0xFFFFFFFFu),
    val alarmColorsBackground: Boolean = true,
    val alarmColorsValue: Boolean = true,

    val showIcon: Boolean = true,
    val showUnit: Boolean = true,
    val showMinMax: Boolean = true,
)

data class DashboardPageConfig(
    val id: String,
    val title: String,
    val rows: Int,
    val columns: Int,
    val boxes: List<DashboardBoxConfig>,
) {
    fun withBoxDefaultsAppliedToPage(sourceBox: DashboardBoxConfig): DashboardPageConfig =
        copy(
            boxes = boxes.map {
                it.copy(
                    valueScale = sourceBox.valueScale,
                    iconScale = sourceBox.iconScale,
                    iconValueGapScale = sourceBox.iconValueGapScale,
                    minMaxScale = sourceBox.minMaxScale,
                    scaleMin = sourceBox.scaleMin,
                    scaleMax = sourceBox.scaleMax,
                    decimalPlaces = sourceBox.decimalPlaces,
                    splitValueDigits = sourceBox.splitValueDigits,
                    smoothingAlpha = sourceBox.smoothingAlpha,
                    backgroundColor = sourceBox.backgroundColor,
                    foregroundColor = sourceBox.foregroundColor,
                    valueColor = sourceBox.valueColor,
                    unitColor = sourceBox.unitColor,
                    minColor = sourceBox.minColor,
                    maxColor = sourceBox.maxColor,
                    borderColor = sourceBox.borderColor,
                    showIcon = sourceBox.showIcon,
                    showUnit = sourceBox.showUnit,
                    showMinMax = sourceBox.showMinMax,
                )
            },
        )

    fun normalized(): DashboardPageConfig {
        val safeRows = rows.coerceIn(1, MAX_DASHBOARD_GRID_SIZE)
        val safeColumns = columns.coerceIn(1, MAX_DASHBOARD_GRID_SIZE)
        val accepted = ArrayList<DashboardBoxConfig>(boxes.size)
        val occupied = HashSet<Int>()

        boxes.forEach { box ->
            val row = box.row.coerceIn(0, safeRows - 1)
            val column = box.column.coerceIn(0, safeColumns - 1)
            val rowSpan = box.rowSpan.coerceIn(1, safeRows - row)
            val columnSpan = box.columnSpan.coerceIn(1, safeColumns - column)
            val cells = cellsFor(row, column, rowSpan, columnSpan, safeColumns)
            if (cells.none(occupied::contains)) {
                accepted += box.copy(
                    row = row,
                    column = column,
                    rowSpan = rowSpan,
                    columnSpan = columnSpan,
                )
                occupied += cells
            }
        }

        return copy(rows = safeRows, columns = safeColumns, boxes = accepted)
    }

    fun occupiedCells(excludingBoxIndex: Int = -1): Set<Int> {
        val normalized = normalized()
        return buildSet {
            normalized.boxes.forEachIndexed { index, box ->
                if (index != excludingBoxIndex) {
                    addAll(
                        cellsFor(
                            box.row,
                            box.column,
                            box.rowSpan,
                            box.columnSpan,
                            normalized.columns,
                        ),
                    )
                }
            }
        }
    }

    fun mergeCandidates(boxIndex: Int): List<Int> {
        val normalized = normalized()
        val source = normalized.boxes.getOrNull(boxIndex) ?: return emptyList()

        return normalized.boxes.indices.filter { candidateIndex ->
            if (candidateIndex == boxIndex) return@filter false
            normalized.canMerge(source, normalized.boxes[candidateIndex], boxIndex, candidateIndex)
        }
    }

    fun mergeTargetCells(boxIndex: Int): Set<Int> {
        val normalized = normalized()
        val source = normalized.boxes.getOrNull(boxIndex) ?: return emptySet()

        return buildSet {
            normalized.mergeCandidates(boxIndex).forEach { candidateIndex ->
                addAll(normalized.boxes[candidateIndex].cells(normalized.columns))
            }

            for (row in 0 until normalized.rows) {
                for (column in 0 until normalized.columns) {
                    val expansion = source.expansionToward(row, column) ?: continue
                    if (normalized.canAbsorbExpansion(boxIndex, expansion)) {
                        add(row * normalized.columns + column)
                    }
                }
            }
        }
    }

    fun mergeBoxIntoCell(boxIndex: Int, row: Int, column: Int): DashboardPageConfig {
        val normalized = normalized()
        val source = normalized.boxes.getOrNull(boxIndex) ?: return normalized
        if (row !in 0 until normalized.rows || column !in 0 until normalized.columns) {
            return normalized
        }

        val candidateIndex = normalized.boxes.indices.firstOrNull { index ->
            index != boxIndex && normalized.boxes[index].containsCell(row, column)
        } ?: -1
        if (candidateIndex in normalized.mergeCandidates(boxIndex)) {
            return normalized.mergeBoxes(boxIndex, candidateIndex)
        }

        val expansion = source.expansionToward(row, column) ?: return normalized
        if (!normalized.canAbsorbExpansion(boxIndex, expansion)) return normalized

        return normalized.copy(
            boxes = buildList {
                normalized.boxes.forEachIndexed { index, box ->
                    when {
                        index == boxIndex -> add(
                            source.copy(
                                row = expansion.top,
                                column = expansion.left,
                                rowSpan = expansion.bottom - expansion.top,
                                columnSpan = expansion.right - expansion.left,
                            ),
                        )
                        box.overlaps(
                            expansion.top,
                            expansion.left,
                            expansion.bottom,
                            expansion.right,
                        ) -> Unit
                        else -> add(box)
                    }
                }
            },
        )
    }

    fun mergeBoxes(boxIndex: Int, candidateIndex: Int): DashboardPageConfig {
        val normalized = normalized()
        if (candidateIndex !in normalized.mergeCandidates(boxIndex)) return normalized

        val source = normalized.boxes[boxIndex]
        val candidate = normalized.boxes[candidateIndex]
        val top = minOf(source.row, candidate.row)
        val left = minOf(source.column, candidate.column)
        val bottom = maxOf(
            source.row + source.rowSpan,
            candidate.row + candidate.rowSpan,
        )
        val right = maxOf(
            source.column + source.columnSpan,
            candidate.column + candidate.columnSpan,
        )
        val merged = source.copy(
            row = top,
            column = left,
            rowSpan = bottom - top,
            columnSpan = right - left,
        )

        return normalized.copy(
            boxes = normalized.boxes.mapIndexedNotNull { index, box ->
                when (index) {
                    boxIndex -> merged
                    candidateIndex -> null
                    else -> box
                }
            },
        )
    }

    fun splitBox(boxIndex: Int): DashboardPageConfig {
        val normalized = normalized()
        val source = normalized.boxes.getOrNull(boxIndex) ?: return normalized
        if (source.rowSpan == 1 && source.columnSpan == 1) return normalized

        val replacements = buildList {
            for (row in source.row until source.row + source.rowSpan) {
                for (column in source.column until source.column + source.columnSpan) {
                    add(
                        source.copy(
                            row = row,
                            column = column,
                            rowSpan = 1,
                            columnSpan = 1,
                        ),
                    )
                }
            }
        }

        return normalized.copy(
            boxes = buildList {
                normalized.boxes.forEachIndexed { index, box ->
                    if (index == boxIndex) addAll(replacements) else add(box)
                }
            },
        )
    }

    fun addBoxAt(row: Int, column: Int): DashboardPageConfig {
        val normalized = normalized()
        if (row !in 0 until normalized.rows || column !in 0 until normalized.columns) {
            return normalized
        }
        val cell = row * normalized.columns + column
        if (cell in normalized.occupiedCells()) return normalized

        val usedSensors = normalized.boxes.mapTo(HashSet()) { it.sensor }
        val sensor = DashboardSensor.entries.firstOrNull { it !in usedSensors }
            ?: DashboardSensor.entries[(row * normalized.columns + column) % DashboardSensor.entries.size]
        return normalized.copy(
            boxes = normalized.boxes + DashboardBoxConfig(sensor, row, column),
        )
    }

    fun removeBox(boxIndex: Int): DashboardPageConfig {
        val normalized = normalized()
        if (boxIndex !in normalized.boxes.indices) return normalized
        return normalized.copy(
            boxes = normalized.boxes.filterIndexed { index, _ -> index != boxIndex },
        )
    }

    fun rebuiltGrid(newRows: Int, newColumns: Int): DashboardPageConfig {
        val safeRows = newRows.coerceIn(1, MAX_DASHBOARD_GRID_SIZE)
        val safeColumns = newColumns.coerceIn(1, MAX_DASHBOARD_GRID_SIZE)
        val capacity = safeRows * safeColumns
        val sourceBoxes = if (boxes.isEmpty()) {
            listOf(DashboardBoxConfig(DashboardSensor.BOOST, 0, 0))
        } else {
            boxes.sortedWith(compareBy(DashboardBoxConfig::row, DashboardBoxConfig::column))
        }
        val rebuilt = ArrayList<DashboardBoxConfig>(capacity)
        repeat(capacity) { index ->
            val source = sourceBoxes[index % sourceBoxes.size]
            rebuilt += source.copy(
                row = index / safeColumns,
                column = index % safeColumns,
                rowSpan = 1,
                columnSpan = 1,
                sensor = if (index < sourceBoxes.size) {
                    source.sensor
                } else {
                    DashboardSensor.entries[index % DashboardSensor.entries.size]
                },
            )
        }
        return copy(rows = safeRows, columns = safeColumns, boxes = rebuilt)
    }
}

const val MAX_DASHBOARD_GRID_SIZE = 4


private data class CellBounds(
    val top: Int,
    val left: Int,
    val bottom: Int,
    val right: Int,
)

private fun DashboardBoxConfig.expansionToward(row: Int, column: Int): CellBounds? =
    when {
        column == this.column - 1 && row in this.row until this.row + rowSpan -> CellBounds(
            top = this.row,
            left = column,
            bottom = this.row + rowSpan,
            right = this.column + columnSpan,
        )
        column == this.column + columnSpan && row in this.row until this.row + rowSpan -> CellBounds(
            top = this.row,
            left = this.column,
            bottom = this.row + rowSpan,
            right = column + 1,
        )
        row == this.row - 1 && column in this.column until this.column + columnSpan -> CellBounds(
            top = row,
            left = this.column,
            bottom = this.row + rowSpan,
            right = this.column + columnSpan,
        )
        row == this.row + rowSpan && column in this.column until this.column + columnSpan -> CellBounds(
            top = this.row,
            left = this.column,
            bottom = row + 1,
            right = this.column + columnSpan,
        )
        else -> null
    }

private fun DashboardPageConfig.canAbsorbExpansion(
    sourceIndex: Int,
    bounds: CellBounds,
): Boolean {
    if (
        bounds.top < 0 || bounds.left < 0 ||
        bounds.bottom > rows || bounds.right > columns
    ) {
        return false
    }

    return boxes.indices.none { index ->
        if (index == sourceIndex) return@none false
        val box = boxes[index]
        box.overlaps(bounds.top, bounds.left, bounds.bottom, bounds.right) &&
            !box.isInside(bounds)
    }
}

private fun DashboardBoxConfig.isInside(bounds: CellBounds): Boolean =
    row >= bounds.top &&
        column >= bounds.left &&
        row + rowSpan <= bounds.bottom &&
        column + columnSpan <= bounds.right

private fun DashboardPageConfig.canMerge(
    source: DashboardBoxConfig,
    candidate: DashboardBoxConfig,
    sourceIndex: Int,
    candidateIndex: Int,
): Boolean {
    val top = minOf(source.row, candidate.row)
    val left = minOf(source.column, candidate.column)
    val bottom = maxOf(source.row + source.rowSpan, candidate.row + candidate.rowSpan)
    val right = maxOf(source.column + source.columnSpan, candidate.column + candidate.columnSpan)
    val unionArea = (bottom - top) * (right - left)
    val sourceArea = source.rowSpan * source.columnSpan
    val candidateArea = candidate.rowSpan * candidate.columnSpan
    if (unionArea != sourceArea + candidateArea) return false

    return boxes.indices.none { otherIndex ->
        otherIndex != sourceIndex &&
            otherIndex != candidateIndex &&
            boxes[otherIndex].overlaps(top, left, bottom, right)
    }
}

private fun DashboardBoxConfig.containsCell(row: Int, column: Int): Boolean =
    row in this.row until this.row + rowSpan &&
        column in this.column until this.column + columnSpan

private fun DashboardBoxConfig.cells(columns: Int): Set<Int> =
    cellsFor(row, column, rowSpan, columnSpan, columns)

private fun DashboardBoxConfig.overlaps(
    top: Int,
    left: Int,
    bottom: Int,
    right: Int,
): Boolean =
    row < bottom && row + rowSpan > top && column < right && column + columnSpan > left

private fun cellsFor(
    row: Int,
    column: Int,
    rowSpan: Int,
    columnSpan: Int,
    columns: Int,
): Set<Int> = buildSet {
    for (cellRow in row until row + rowSpan) {
        for (cellColumn in column until column + columnSpan) {
            add(cellRow * columns + cellColumn)
        }
    }
}

object DefaultDashboardPages {
    val race2x2 = DashboardPageConfig(
        id = "race_2x2",
        title = "Race 2×2",
        rows = 2,
        columns = 2,
        boxes = listOf(
            DashboardBoxConfig(DashboardSensor.BOOST, row = 0, column = 0),
            DashboardBoxConfig(DashboardSensor.AFR, row = 0, column = 1),
            DashboardBoxConfig(DashboardSensor.OIL_TEMP, row = 1, column = 0),
            DashboardBoxConfig(DashboardSensor.OIL_PRESSURE, row = 1, column = 1),
        ),
    )

    /**
     * User convention: 1×4 means one column with four rows.
     */
    val strip1x4 = DashboardPageConfig(
        id = "strip_1x4",
        title = "Strip 1×4",
        rows = 4,
        columns = 1,
        boxes = listOf(
            DashboardBoxConfig(DashboardSensor.BOOST, row = 0, column = 0),
            DashboardBoxConfig(DashboardSensor.AFR, row = 1, column = 0),
            DashboardBoxConfig(DashboardSensor.OIL_TEMP, row = 2, column = 0),
            DashboardBoxConfig(DashboardSensor.OIL_PRESSURE, row = 3, column = 0),
        ),
    )

    /**
     * User convention: 2×4 means two columns with four rows.
     */
    val full2x4 = DashboardPageConfig(
        id = "full_2x4",
        title = "Full 2×4",
        rows = 4,
        columns = 2,
        boxes = listOf(
            DashboardBoxConfig(DashboardSensor.BOOST, row = 0, column = 0),
            DashboardBoxConfig(DashboardSensor.AFR, row = 0, column = 1),
            DashboardBoxConfig(DashboardSensor.OIL_TEMP, row = 1, column = 0),
            DashboardBoxConfig(DashboardSensor.OIL_PRESSURE, row = 1, column = 1),
            DashboardBoxConfig(DashboardSensor.INSIDE_TEMP, row = 2, column = 0),
            DashboardBoxConfig(DashboardSensor.OUTSIDE_TEMP, row = 2, column = 1),
            DashboardBoxConfig(DashboardSensor.BATTERY, row = 3, column = 0),
            DashboardBoxConfig(DashboardSensor.TIME, row = 3, column = 1, showMinMax = false),
        ),
    )

    val all: List<DashboardPageConfig> = listOf(race2x2, strip1x4, full2x4)
}

private fun defaultWarningLow(sensor: DashboardSensor): Float =
    when (sensor) {
        DashboardSensor.OIL_PRESSURE -> 4.50f
        else -> Float.NaN
    }

private fun defaultCriticalLow(sensor: DashboardSensor): Float =
    when (sensor) {
        DashboardSensor.OIL_PRESSURE -> 4.00f
        else -> Float.NaN
    }

private fun defaultOilPressureBoostArmBar(sensor: DashboardSensor): Float =
    when (sensor) {
        DashboardSensor.OIL_PRESSURE -> 0.30f
        else -> Float.NaN
    }

private fun defaultOilPressureWarningBar(sensor: DashboardSensor): Float =
    when (sensor) {
        DashboardSensor.OIL_PRESSURE -> 4.50f
        else -> Float.NaN
    }

private fun defaultOilPressureCriticalBar(sensor: DashboardSensor): Float =
    when (sensor) {
        DashboardSensor.OIL_PRESSURE -> 4.00f
        else -> Float.NaN
    }

private fun defaultSmoothingAlpha(sensor: DashboardSensor): Float =
    when (sensor) {
        DashboardSensor.AFR,
        DashboardSensor.BOOST,
        DashboardSensor.OIL_PRESSURE -> 0.35f
        else -> 1.0f
    }

private fun defaultDecimalPlaces(sensor: DashboardSensor): Int =
    when (sensor) {
        DashboardSensor.AFR -> 2
        DashboardSensor.BOOST,
        DashboardSensor.OIL_PRESSURE,
        DashboardSensor.BATTERY -> 2
        DashboardSensor.OIL_TEMP,
        DashboardSensor.OUTSIDE_TEMP,
        DashboardSensor.INSIDE_TEMP -> 1
        DashboardSensor.TIME -> 0
    }

private fun defaultWarningHigh(sensor: DashboardSensor): Float =
    when (sensor) {
        DashboardSensor.OIL_TEMP -> 120.0f
        else -> Float.NaN
    }

private fun defaultCriticalHigh(sensor: DashboardSensor): Float =
    when (sensor) {
        DashboardSensor.OIL_TEMP -> 130.0f
        else -> Float.NaN
    }

@Suppress("NOTHING_TO_INLINE")
private inline fun uncheckedColor(value: UInt): Int = value.toInt()
