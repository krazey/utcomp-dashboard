package de.krazey.utcomp.probe.dashboard

import de.krazey.utcomp.probe.utcomp.UtcompDataSnapshot

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
        iconResourceName = "ic_utcomp_battery_48dp",
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
        iconResourceName = "ic_rcomp_timer_trip_48dp",
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

    /**
     * Value/gauge scale.
     * This is not a unit conversion; it defines the visible gauge/min/max range.
     */
    val scaleMin: Float = sensor.defaultMin,
    val scaleMax: Float = sensor.defaultMax,

    /**
     * Stored as ARGB Ints. Use 0xAARRGGBB.
     */
    val backgroundColor: Int = uncheckedColor(0xFF0B0E14u),
    val foregroundColor: Int = uncheckedColor(0xFFFFFFFFu),
    val unitColor: Int = uncheckedColor(0xFFD2D8E1u),
    val alarmColor: Int = uncheckedColor(0xFFFF4848u),
    val minColor: Int = uncheckedColor(0xFF50AAFFu),
    val maxColor: Int = uncheckedColor(0xFFFF4848u),

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
    val warningLow: Float = Float.NaN,
    val criticalLow: Float = Float.NaN,
    val warningHigh: Float = defaultWarningHigh(sensor),
    val criticalHigh: Float = defaultCriticalHigh(sensor),
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
                    backgroundColor = sourceBox.backgroundColor,
                    foregroundColor = sourceBox.foregroundColor,
                    unitColor = sourceBox.unitColor,
                    alarmColor = sourceBox.alarmColor,
                    minColor = sourceBox.minColor,
                    maxColor = sourceBox.maxColor,
                    valueColor = sourceBox.valueColor,
                    warningLow = sourceBox.warningLow,
                    criticalLow = sourceBox.criticalLow,
                    warningHigh = sourceBox.warningHigh,
                    criticalHigh = sourceBox.criticalHigh,
                    warningColor = sourceBox.warningColor,
                    criticalColor = sourceBox.criticalColor,
                    warningValueColor = sourceBox.warningValueColor,
                    criticalValueColor = sourceBox.criticalValueColor,
                    alarmColorsBackground = sourceBox.alarmColorsBackground,
                    alarmColorsValue = sourceBox.alarmColorsValue,
                    showIcon = sourceBox.showIcon,
                    showUnit = sourceBox.showUnit,
                    showMinMax = sourceBox.showMinMax,
                )
            },
        )
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
