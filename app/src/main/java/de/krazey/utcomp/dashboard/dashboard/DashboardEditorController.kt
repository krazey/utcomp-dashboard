package de.krazey.utcomp.dashboard.dashboard

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.widget.EditText
import de.krazey.utcomp.dashboard.util.fixed
import de.krazey.utcomp.dashboard.utcomp.pretty
import java.util.Locale
import kotlin.math.abs

internal class DashboardEditorController(
    private val context: Context,
    private val currentPageConfig: () -> DashboardPageConfig,
    private val updateCurrentPage: ((DashboardPageConfig) -> DashboardPageConfig) -> Unit,
    private val updateBoxConfig: (
        boxIndex: Int,
        transform: (DashboardBoxConfig) -> DashboardBoxConfig,
    ) -> Unit,
    private val onSmoothingChanged: (boxIndex: Int, box: DashboardBoxConfig) -> Unit,
) {
    private data class NamedColor(
        val name: String,
        val color: Int,
    )

    fun showBoxEditor(boxIndex: Int) {
        val pageConfig = currentPageConfig()
        val box = pageConfig.boxes.getOrNull(boxIndex) ?: return

        val oilBoostText = if (box.sensor == DashboardSensor.OIL_PRESSURE) {
            oilPressureBoostAlarmSummary(box)
        } else {
            "only for Oil pressure"
        }

        val actions = arrayOf(
            "Sensor: ${box.sensor.label}",
            "Value size: ${formatScale(box.valueScale)}",
            "Icon size: ${formatScale(box.iconScale)}",
            "Icon/value gap: ${formatScale(box.iconValueGapScale)}",
            "Decimals: ${decimalsText(box.decimalPlaces)}",
            "Split decimals: ${enabledText(box.splitValueDigits)}",
            "Smoothing: ${smoothingText(box.smoothingAlpha)}",
            "Normal value color: ${colorName(box.valueColor)}",
            "Background color: ${colorName(box.backgroundColor)}",
            "Warning low: ${formatThreshold(box.warningLow, box.sensor.unit)}",
            "Critical low: ${formatThreshold(box.criticalLow, box.sensor.unit)}",
            "Warning high: ${formatThreshold(box.warningHigh, box.sensor.unit)}",
            "Critical high: ${formatThreshold(box.criticalHigh, box.sensor.unit)}",
            "Oil pressure boost arm: $oilBoostText",
            "Warning background: ${colorName(box.warningColor)}",
            "Warning value color: ${colorName(box.warningValueColor)}",
            "Critical background: ${colorName(box.criticalColor)}",
            "Critical value color: ${colorName(box.criticalValueColor)}",
            "Alarm background: ${enabledText(box.alarmColorsBackground)}",
            "Alarm value color: ${enabledText(box.alarmColorsValue)}",
            "Icon: ${shownText(box.showIcon)}",
            "Unit: ${shownText(box.showUnit)}",
            "Min/max: ${shownText(box.showMinMax)}",
            "Apply visual style to all boxes on page",
            "Set default alarms for ${box.sensor.label}",
            "Disable alarms for ${box.sensor.label}",
            "Reset this box style",
        )

        AlertDialog.Builder(context)
            .setTitle("Edit ${pageConfig.title}: ${box.sensor.label}")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showSensorPicker(boxIndex)
                    1 -> showScalePicker("Value size", box.valueScale) { selected ->
                        updateBoxConfig(boxIndex) { it.copy(valueScale = selected) }
                    }
                    2 -> showScalePicker("Icon size", box.iconScale) { selected ->
                        updateBoxConfig(boxIndex) { it.copy(iconScale = selected) }
                    }
                    3 -> showScalePicker("Icon/value gap", box.iconValueGapScale) { selected ->
                        updateBoxConfig(boxIndex) { it.copy(iconValueGapScale = selected) }
                    }
                    4 -> showDecimalsPicker(boxIndex, box)
                    5 -> updateBoxConfig(boxIndex) {
                        it.copy(splitValueDigits = !it.splitValueDigits)
                    }
                    6 -> showSmoothingPicker(boxIndex, box)
                    7 -> showColorPicker(
                        title = "Normal value color",
                        current = box.valueColor,
                        palette = VALUE_COLORS,
                    ) { selected ->
                        updateBoxConfig(boxIndex) {
                            it.copy(valueColor = selected, foregroundColor = selected)
                        }
                    }
                    8 -> showColorPicker(
                        title = "Background color",
                        current = box.backgroundColor,
                        palette = BACKGROUND_COLORS,
                    ) { selected ->
                        updateBoxConfig(boxIndex) { it.copy(backgroundColor = selected) }
                    }
                    9 -> showThresholdEditor(
                        title = "Warning low",
                        boxIndex = boxIndex,
                        box = box,
                        current = box.warningLow,
                    ) { newValue -> copy(warningLow = newValue) }
                    10 -> showThresholdEditor(
                        title = "Critical low",
                        boxIndex = boxIndex,
                        box = box,
                        current = box.criticalLow,
                    ) { newValue -> copy(criticalLow = newValue) }
                    11 -> showThresholdEditor(
                        title = "Warning high",
                        boxIndex = boxIndex,
                        box = box,
                        current = box.warningHigh,
                    ) { newValue -> copy(warningHigh = newValue) }
                    12 -> showThresholdEditor(
                        title = "Critical high",
                        boxIndex = boxIndex,
                        box = box,
                        current = box.criticalHigh,
                    ) { newValue -> copy(criticalHigh = newValue) }
                    13 -> if (box.sensor == DashboardSensor.OIL_PRESSURE) {
                        showOilPressureBoostAlarmEditor(boxIndex, box)
                    } else {
                        showSensorPicker(boxIndex)
                    }
                    14 -> showColorPicker(
                        title = "Warning background",
                        current = box.warningColor,
                        palette = WARNING_COLORS,
                    ) { selected ->
                        updateBoxConfig(boxIndex) { it.copy(warningColor = selected) }
                    }
                    15 -> showColorPicker(
                        title = "Warning value color",
                        current = box.warningValueColor,
                        palette = ALARM_VALUE_COLORS,
                    ) { selected ->
                        updateBoxConfig(boxIndex) { it.copy(warningValueColor = selected) }
                    }
                    16 -> showColorPicker(
                        title = "Critical background",
                        current = box.criticalColor,
                        palette = CRITICAL_COLORS,
                    ) { selected ->
                        updateBoxConfig(boxIndex) {
                            it.copy(
                                criticalColor = selected,
                                alarmColor = selected,
                                maxColor = selected,
                            )
                        }
                    }
                    17 -> showColorPicker(
                        title = "Critical value color",
                        current = box.criticalValueColor,
                        palette = ALARM_VALUE_COLORS,
                    ) { selected ->
                        updateBoxConfig(boxIndex) { it.copy(criticalValueColor = selected) }
                    }
                    18 -> updateBoxConfig(boxIndex) {
                        it.copy(alarmColorsBackground = !it.alarmColorsBackground)
                    }
                    19 -> updateBoxConfig(boxIndex) {
                        it.copy(alarmColorsValue = !it.alarmColorsValue)
                    }
                    20 -> updateBoxConfig(boxIndex) { it.copy(showIcon = !it.showIcon) }
                    21 -> updateBoxConfig(boxIndex) { it.copy(showUnit = !it.showUnit) }
                    22 -> updateBoxConfig(boxIndex) { it.copy(showMinMax = !it.showMinMax) }
                    23 -> confirmApplyStyleToPage(boxIndex)
                    24 -> updateBoxConfig(boxIndex) { withDefaultAlarmThresholds(it) }
                    25 -> updateBoxConfig(boxIndex) { clearAlarmThresholds(it) }
                    26 -> updateBoxConfig(boxIndex) { resetBoxStyle(it) }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showDecimalsPicker(boxIndex: Int, box: DashboardBoxConfig) {
        val labels = Array(3) { decimals ->
            val marker = if (box.decimalPlaces.coerceIn(0, 2) == decimals) " ✓" else ""
            "${decimalsText(decimals)}$marker"
        }

        AlertDialog.Builder(context)
            .setTitle("Decimal digits")
            .setItems(labels) { _, which ->
                updateBoxConfig(boxIndex) { it.copy(decimalPlaces = which) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSmoothingPicker(boxIndex: Int, box: DashboardBoxConfig) {
        val labels = Array(SMOOTHING_VALUES.size) { index ->
            val marker = if (abs(SMOOTHING_VALUES[index] - box.smoothingAlpha) < 0.01f) {
                " ✓"
            } else {
                ""
            }
            "${SMOOTHING_NAMES[index]}$marker"
        }

        AlertDialog.Builder(context)
            .setTitle("Smoothing")
            .setItems(labels) { _, which ->
                onSmoothingChanged(boxIndex, box)
                updateBoxConfig(boxIndex) {
                    it.copy(smoothingAlpha = SMOOTHING_VALUES[which])
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showScalePicker(
        title: String,
        current: Float,
        onSelected: (Float) -> Unit,
    ) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            hint = "100"
            setText(trimFloat(current * 100f))
            selectAll()
        }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage("Enter percent. Examples: 80, 100, 125, 150.\nAllowed range: 25–400%.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                input.text.toString()
                    .trim()
                    .replace(",", ".")
                    .toFloatOrNull()
                    ?.let { percent -> onSelected((percent / 100f).coerceIn(0.25f, 4.0f)) }
            }
            .setNeutralButton("Default") { _, _ -> onSelected(1.0f) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFloatSettingEditor(
        title: String,
        current: Float,
        unit: String,
        onSelected: (Float) -> Unit,
    ) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine(true)
            hint = unit
            if (!current.isNaN()) {
                setText(trimFloat(current))
                selectAll()
            }
        }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage("Current: ${formatThreshold(current, unit)}")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                input.text.toString()
                    .trim()
                    .replace(",", ".")
                    .toFloatOrNull()
                    ?.let(onSelected)
            }
            .setNeutralButton("Off") { _, _ -> onSelected(Float.NaN) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showOilPressureBoostAlarmEditor(
        boxIndex: Int,
        box: DashboardBoxConfig,
    ) {
        val actions = arrayOf(
            "Boost-armed low alarm: ${enabledText(box.oilPressureBoostAlarm)}",
            "Arm low alarm at boost: ${formatThreshold(box.oilPressureBoostArmBar, "BAR")}",
            "Warning low pressure: ${formatThreshold(box.warningLow, "BAR")}",
            "Critical low pressure: ${formatThreshold(box.criticalLow, "BAR")}",
            "Reset UTCOMP-like oil pressure defaults",
        )

        AlertDialog.Builder(context)
            .setTitle("Oil pressure alarm")
            .setMessage(
                oilPressureBoostAlarmSummary(box) +
                    "\n\nWarning/critical pressure here edits the normal Low alarm thresholds."
            )
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> updateBoxConfig(boxIndex) {
                        it.copy(oilPressureBoostAlarm = !it.oilPressureBoostAlarm)
                    }
                    1 -> showFloatSettingEditor(
                        "Arm low oil pressure alarm at boost",
                        box.oilPressureBoostArmBar,
                        "BAR",
                    ) { value ->
                        updateBoxConfig(boxIndex) { it.copy(oilPressureBoostArmBar = value) }
                    }
                    2 -> showFloatSettingEditor(
                        "Warning low oil pressure",
                        box.warningLow,
                        "BAR",
                    ) { value ->
                        updateBoxConfig(boxIndex) { it.copy(warningLow = value) }
                    }
                    3 -> showFloatSettingEditor(
                        "Critical low oil pressure",
                        box.criticalLow,
                        "BAR",
                    ) { value ->
                        updateBoxConfig(boxIndex) { it.copy(criticalLow = value) }
                    }
                    4 -> updateBoxConfig(boxIndex) {
                        it.copy(
                            oilPressureBoostAlarm = true,
                            oilPressureBoostArmBar = DEFAULT_OIL_PRESSURE_BOOST_ARM_BAR,
                            warningLow = DEFAULT_OIL_PRESSURE_WARNING_BAR,
                            criticalLow = DEFAULT_OIL_PRESSURE_CRITICAL_BAR,
                            warningHigh = Float.NaN,
                            criticalHigh = Float.NaN,
                        )
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showThresholdEditor(
        title: String,
        boxIndex: Int,
        box: DashboardBoxConfig,
        current: Float,
        update: DashboardBoxConfig.(Float) -> DashboardBoxConfig,
    ) {
        showFloatSettingEditor(title, current, box.sensor.unit) { newValue ->
            updateBoxConfig(boxIndex) { it.update(newValue) }
        }
    }

    private fun showColorPicker(
        title: String,
        current: Int,
        palette: List<NamedColor>,
        onSelected: (Int) -> Unit,
    ) {
        val labels = Array(palette.size) { index ->
            val namedColor = palette[index]
            val marker = if (namedColor.color == current) " ✓" else ""
            "${namedColor.name}$marker"
        }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(labels) { _, which -> onSelected(palette[which].color) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmApplyStyleToPage(boxIndex: Int) {
        val pageConfig = currentPageConfig()
        val box = pageConfig.boxes.getOrNull(boxIndex) ?: return

        AlertDialog.Builder(context)
            .setTitle("Apply style to page?")
            .setMessage(
                "Apply ${box.sensor.label} visual style to all boxes on " +
                    "${pageConfig.title}?\n\nAlarm thresholds and alarm behavior are preserved."
            )
            .setPositiveButton("Apply") { _, _ ->
                updateCurrentPage { it.withBoxDefaultsAppliedToPage(box) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSensorPicker(boxIndex: Int) {
        val sensors = DashboardSensor.entries
        val labels = Array(sensors.size) { sensors[it].label }

        AlertDialog.Builder(context)
            .setTitle("Select sensor")
            .setItems(labels) { _, which ->
                val selected = sensors[which]
                updateBoxConfig(boxIndex) {
                    withDefaultAlarmThresholds(
                        it.copy(
                            sensor = selected,
                            scaleMin = selected.defaultMin,
                            scaleMax = selected.defaultMax,
                        ),
                        selected,
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetBoxStyle(box: DashboardBoxConfig): DashboardBoxConfig =
        withDefaultAlarmThresholds(
            box.copy(
                valueScale = 1.0f,
                iconScale = 1.0f,
                decimalPlaces = defaultDisplayDecimals(box.sensor),
                splitValueDigits = true,
                smoothingAlpha = defaultDisplaySmoothing(box.sensor),
                backgroundColor = Color.rgb(11, 14, 20),
                foregroundColor = Color.WHITE,
                valueColor = Color.WHITE,
                unitColor = Color.rgb(210, 216, 225),
                alarmColor = Color.rgb(255, 72, 72),
                minColor = Color.rgb(80, 170, 255),
                maxColor = Color.rgb(255, 72, 72),
                warningColor = Color.rgb(255, 170, 48),
                criticalColor = Color.rgb(255, 72, 72),
                warningValueColor = Color.BLACK,
                criticalValueColor = Color.WHITE,
                alarmColorsBackground = true,
                alarmColorsValue = true,
                showIcon = true,
                showUnit = true,
                showMinMax = true,
            ),
        )

    private fun clearAlarmThresholds(box: DashboardBoxConfig): DashboardBoxConfig =
        box.copy(
            warningLow = Float.NaN,
            criticalLow = Float.NaN,
            warningHigh = Float.NaN,
            criticalHigh = Float.NaN,
        )

    private fun withDefaultAlarmThresholds(
        box: DashboardBoxConfig,
        sensor: DashboardSensor = box.sensor,
    ): DashboardBoxConfig =
        box.copy(
            warningHigh = if (sensor == DashboardSensor.OIL_TEMP) 120.0f else Float.NaN,
            criticalHigh = if (sensor == DashboardSensor.OIL_TEMP) 130.0f else Float.NaN,
            warningLow = if (sensor == DashboardSensor.OIL_PRESSURE) {
                DEFAULT_OIL_PRESSURE_WARNING_BAR
            } else {
                Float.NaN
            },
            criticalLow = if (sensor == DashboardSensor.OIL_PRESSURE) {
                DEFAULT_OIL_PRESSURE_CRITICAL_BAR
            } else {
                Float.NaN
            },
            oilPressureBoostAlarm = sensor == DashboardSensor.OIL_PRESSURE,
            oilPressureBoostArmBar = if (sensor == DashboardSensor.OIL_PRESSURE) {
                DEFAULT_OIL_PRESSURE_BOOST_ARM_BAR
            } else {
                Float.NaN
            },
            oilPressureWarningBar = if (sensor == DashboardSensor.OIL_PRESSURE) {
                DEFAULT_OIL_PRESSURE_WARNING_BAR
            } else {
                Float.NaN
            },
            oilPressureCriticalBar = if (sensor == DashboardSensor.OIL_PRESSURE) {
                DEFAULT_OIL_PRESSURE_CRITICAL_BAR
            } else {
                Float.NaN
            },
        )

    private fun oilPressureBoostAlarmSummary(box: DashboardBoxConfig): String {
        if (box.sensor != DashboardSensor.OIL_PRESSURE) return "not applicable"
        if (!box.oilPressureBoostAlarm) return "disabled"

        return "armed >= ${trimFloat(box.oilPressureBoostArmBar)} BAR boost, " +
            "warn low <= ${trimFloat(box.warningLow)} BAR, " +
            "crit low <= ${trimFloat(box.criticalLow)} BAR"
    }

    private fun defaultDisplayDecimals(sensor: DashboardSensor): Int =
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

    private fun defaultDisplaySmoothing(sensor: DashboardSensor): Float =
        when (sensor) {
            DashboardSensor.AFR,
            DashboardSensor.BOOST,
            DashboardSensor.OIL_PRESSURE -> 0.35f
            else -> 1.0f
        }

    private fun decimalsText(decimals: Int): String =
        when (decimals.coerceIn(0, 2)) {
            0 -> "0 decimals"
            1 -> "1 decimal"
            else -> "2 decimals"
        }

    private fun smoothingText(alpha: Float): String =
        when {
            alpha >= 0.999f -> "off"
            alpha >= 0.45f -> "light"
            alpha >= 0.20f -> "medium"
            else -> "heavy"
        }

    private fun enabledText(enabled: Boolean): String =
        if (enabled) "enabled" else "disabled"

    private fun shownText(shown: Boolean): String =
        if (shown) "shown" else "hidden"

    private fun formatScale(scale: Float): String =
        "${(scale * 100f).toInt()}%"

    private fun formatThreshold(value: Float, unit: String): String =
        if (value.isNaN()) {
            "off"
        } else if (value.isInfinite()) {
            "—"
        } else {
            value.pretty() + unit
        }

    private fun trimFloat(value: Float): String =
        if (value % 1.0f == 0.0f) value.toInt().toString() else value.fixed(1)

    private fun colorName(color: Int): String =
        ALL_NAMED_COLORS.firstOrNull { it.color == color }?.name
            ?: "#%06X".format(Locale.US, 0xFFFFFF and color)

    private companion object {
        const val DEFAULT_OIL_PRESSURE_BOOST_ARM_BAR = 0.30f
        const val DEFAULT_OIL_PRESSURE_WARNING_BAR = 4.50f
        const val DEFAULT_OIL_PRESSURE_CRITICAL_BAR = 4.00f

        val SMOOTHING_VALUES = floatArrayOf(1.0f, 0.5f, 0.25f, 0.10f)
        val SMOOTHING_NAMES = arrayOf("Off", "Light", "Medium", "Heavy")

        val VALUE_COLORS = listOf(
            NamedColor("White", Color.WHITE),
            NamedColor("Black", Color.BLACK),
            NamedColor("Ice blue", Color.rgb(120, 210, 255)),
            NamedColor("Warm yellow", Color.rgb(255, 220, 110)),
            NamedColor("Green", Color.rgb(80, 220, 120)),
            NamedColor("Soft red", Color.rgb(255, 120, 120)),
        )
        val BACKGROUND_COLORS = listOf(
            NamedColor("Dark blue", Color.rgb(11, 14, 20)),
            NamedColor("Blue gray", Color.rgb(16, 20, 30)),
            NamedColor("Black", Color.BLACK),
            NamedColor("Dark purple", Color.rgb(18, 10, 22)),
            NamedColor("Dark teal", Color.rgb(8, 24, 28)),
            NamedColor("Dark amber", Color.rgb(28, 18, 8)),
        )
        val WARNING_COLORS = listOf(
            NamedColor("Orange", Color.rgb(255, 170, 48)),
            NamedColor("Yellow", Color.rgb(255, 220, 70)),
            NamedColor("Blue", Color.rgb(120, 210, 255)),
            NamedColor("Purple", Color.rgb(180, 110, 255)),
        )
        val ALARM_VALUE_COLORS = listOf(
            NamedColor("White", Color.WHITE),
            NamedColor("Black", Color.BLACK),
            NamedColor("Warm yellow", Color.rgb(255, 220, 110)),
            NamedColor("Ice blue", Color.rgb(120, 210, 255)),
            NamedColor("Green", Color.rgb(80, 220, 120)),
            NamedColor("Soft red", Color.rgb(255, 120, 120)),
        )
        val CRITICAL_COLORS = listOf(
            NamedColor("Red", Color.rgb(255, 72, 72)),
            NamedColor("Pink", Color.rgb(255, 0, 120)),
            NamedColor("Orange red", Color.rgb(255, 120, 0)),
            NamedColor("Magenta", Color.rgb(220, 40, 255)),
        )
        val ALL_NAMED_COLORS =
            (VALUE_COLORS + ALARM_VALUE_COLORS + BACKGROUND_COLORS + WARNING_COLORS + CRITICAL_COLORS)
                .distinctBy { it.color }
    }
}
