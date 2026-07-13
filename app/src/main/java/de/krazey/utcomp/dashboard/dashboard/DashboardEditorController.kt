package de.krazey.utcomp.dashboard.dashboard

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.InputType
import android.widget.EditText
import de.krazey.utcomp.dashboard.util.fixed
import de.krazey.utcomp.dashboard.view.DarkActionDialog
import de.krazey.utcomp.dashboard.view.DarkActionItem
import de.krazey.utcomp.dashboard.utcomp.pretty
import java.util.Locale

internal class DashboardEditorController(
    private val context: Activity,
    private val currentPageConfig: () -> DashboardPageConfig,
    private val updateCurrentPage: ((DashboardPageConfig) -> DashboardPageConfig) -> Unit,
    private val updateBoxConfig: (
        boxIndex: Int,
        transform: (DashboardBoxConfig) -> DashboardBoxConfig,
    ) -> Unit,
    private val onSmoothingChanged: (boxIndex: Int, box: DashboardBoxConfig) -> Unit,
    private val isPeriodicNoiseCalibrationAvailable: (DashboardSensor) -> Boolean,
    private val showPeriodicNoiseCalibration: () -> Unit,
    private val isLayoutEditable: () -> Boolean,
    private val onEditPageGrid: () -> Unit,
    private val onStartMerge: (boxIndex: Int) -> Unit,
) {
    private data class NamedColor(
        val name: String,
        val color: Int,
    )

    fun showBoxEditor(boxIndex: Int, titleOverride: String? = null) {
        val pageConfig = currentPageConfig().normalized()
        val box = pageConfig.boxes.getOrNull(boxIndex) ?: return
        val layoutEditable = isLayoutEditable()
        val mergeTargetCount = if (layoutEditable) {
            pageConfig.mergeTargetCells(boxIndex).size
        } else {
            0
        }
        val oilBoostText = if (box.sensor == DashboardSensor.OIL_PRESSURE) {
            oilPressureBoostAlarmSummary(box)
        } else {
            "only for Oil pressure"
        }
        val position = "Row ${box.row + 1}, column ${box.column + 1} • " +
            spanSummary(box.rowSpan, box.columnSpan)

        showEditorMenu(
            context = context,
            title = titleOverride ?: "${pageConfig.title} • ${box.sensor.label}",
            sections = listOf(
                EditorMenuSection(
                    "Display",
                    listOf(
                        EditorMenuRow("Sensor", box.sensor.label) {
                            showSensorPicker(boxIndex)
                        },
                        EditorMenuRow("Value size", formatScale(box.valueScale)) {
                            showScalePicker("Value size", box.valueScale) { selected ->
                                updateBoxConfig(boxIndex) { it.copy(valueScale = selected) }
                            }
                        },
                        EditorMenuRow("Icon size", formatScale(box.iconScale)) {
                            showScalePicker("Icon size", box.iconScale) { selected ->
                                updateBoxConfig(boxIndex) { it.copy(iconScale = selected) }
                            }
                        },
                        EditorMenuRow("Icon/value gap", formatScale(box.iconValueGapScale)) {
                            showScalePicker("Icon/value gap", box.iconValueGapScale) { selected ->
                                updateBoxConfig(boxIndex) {
                                    it.copy(iconValueGapScale = selected)
                                }
                            }
                        },
                        EditorMenuRow("Min/max size", formatScale(box.minMaxScale)) {
                            showScalePicker("Min/max size", box.minMaxScale) { selected ->
                                updateBoxConfig(boxIndex) { it.copy(minMaxScale = selected) }
                            }
                        },
                        EditorMenuRow("Decimal digits", decimalsText(box.decimalPlaces)) {
                            showDecimalsPicker(boxIndex, box)
                        },
                        EditorMenuRow("Split decimal digits", enabledText(box.splitValueDigits)) {
                            updateBoxConfig(boxIndex) {
                                it.copy(splitValueDigits = !it.splitValueDigits)
                            }
                        },
                        EditorMenuRow("Smoothing", smoothingText(box)) {
                            showSmoothingPicker(boxIndex, box)
                        },
                        EditorMenuRow("Periodic noise", periodicNoiseText(box)) {
                            showPeriodicNoisePicker(boxIndex, box)
                        },
                    ),
                ),
                EditorMenuSection(
                    "Colors",
                    listOf(
                        EditorMenuRow("Normal value", colorName(box.valueColor)) {
                            showColorPicker("Normal value color", box.valueColor, VALUE_COLORS) {
                                selected ->
                                updateBoxConfig(boxIndex) {
                                    it.copy(valueColor = selected, foregroundColor = selected)
                                }
                            }
                        },
                        EditorMenuRow("Unit", colorName(box.unitColor)) {
                            showColorPicker("Unit color", box.unitColor, VALUE_COLORS) { selected ->
                                updateBoxConfig(boxIndex) { it.copy(unitColor = selected) }
                            }
                        },
                        EditorMenuRow("Background", colorName(box.backgroundColor)) {
                            showColorPicker(
                                "Background color",
                                box.backgroundColor,
                                BACKGROUND_COLORS,
                            ) { selected ->
                                updateBoxConfig(boxIndex) { it.copy(backgroundColor = selected) }
                            }
                        },
                        EditorMenuRow("Border", colorName(box.borderColor)) {
                            showColorPicker("Border color", box.borderColor, BORDER_COLORS) {
                                selected ->
                                updateBoxConfig(boxIndex) { it.copy(borderColor = selected) }
                            }
                        },
                        EditorMenuRow("Minimum value", colorName(box.minColor)) {
                            showColorPicker("Minimum value color", box.minColor, VALUE_COLORS) {
                                selected ->
                                updateBoxConfig(boxIndex) { it.copy(minColor = selected) }
                            }
                        },
                        EditorMenuRow("Maximum value", colorName(box.maxColor)) {
                            showColorPicker("Maximum value color", box.maxColor, VALUE_COLORS) {
                                selected ->
                                updateBoxConfig(boxIndex) { it.copy(maxColor = selected) }
                            }
                        },
                    ),
                ),
                EditorMenuSection(
                    "Alarms",
                    listOf(
                        EditorMenuRow(
                            "Warning low",
                            formatThreshold(box.warningLow, box.sensor.unit),
                        ) {
                            showThresholdEditor(
                                "Warning low",
                                boxIndex,
                                box,
                                box.warningLow,
                            ) { value -> copy(warningLow = value) }
                        },
                        EditorMenuRow(
                            "Critical low",
                            formatThreshold(box.criticalLow, box.sensor.unit),
                        ) {
                            showThresholdEditor(
                                "Critical low",
                                boxIndex,
                                box,
                                box.criticalLow,
                            ) { value -> copy(criticalLow = value) }
                        },
                        EditorMenuRow(
                            "Warning high",
                            formatThreshold(box.warningHigh, box.sensor.unit),
                        ) {
                            showThresholdEditor(
                                "Warning high",
                                boxIndex,
                                box,
                                box.warningHigh,
                            ) { value -> copy(warningHigh = value) }
                        },
                        EditorMenuRow(
                            "Critical high",
                            formatThreshold(box.criticalHigh, box.sensor.unit),
                        ) {
                            showThresholdEditor(
                                "Critical high",
                                boxIndex,
                                box,
                                box.criticalHigh,
                            ) { value -> copy(criticalHigh = value) }
                        },
                        EditorMenuRow("Oil pressure boost arm", oilBoostText) {
                            if (box.sensor == DashboardSensor.OIL_PRESSURE) {
                                showOilPressureBoostAlarmEditor(boxIndex, box)
                            } else {
                                showSensorPicker(boxIndex)
                            }
                        },
                        EditorMenuRow("Warning background", colorName(box.warningColor)) {
                            showColorPicker(
                                "Warning background",
                                box.warningColor,
                                WARNING_COLORS,
                            ) { selected ->
                                updateBoxConfig(boxIndex) { it.copy(warningColor = selected) }
                            }
                        },
                        EditorMenuRow("Warning value", colorName(box.warningValueColor)) {
                            showColorPicker(
                                "Warning value color",
                                box.warningValueColor,
                                ALARM_VALUE_COLORS,
                            ) { selected ->
                                updateBoxConfig(boxIndex) {
                                    it.copy(warningValueColor = selected)
                                }
                            }
                        },
                        EditorMenuRow("Critical background", colorName(box.criticalColor)) {
                            showColorPicker(
                                "Critical background",
                                box.criticalColor,
                                CRITICAL_COLORS,
                            ) { selected ->
                                updateBoxConfig(boxIndex) {
                                    it.copy(
                                        criticalColor = selected,
                                        alarmColor = selected,
                                        maxColor = selected,
                                    )
                                }
                            }
                        },
                        EditorMenuRow("Critical value", colorName(box.criticalValueColor)) {
                            showColorPicker(
                                "Critical value color",
                                box.criticalValueColor,
                                ALARM_VALUE_COLORS,
                            ) { selected ->
                                updateBoxConfig(boxIndex) {
                                    it.copy(criticalValueColor = selected)
                                }
                            }
                        },
                        EditorMenuRow(
                            "Color alarm background",
                            enabledText(box.alarmColorsBackground),
                        ) {
                            updateBoxConfig(boxIndex) {
                                it.copy(alarmColorsBackground = !it.alarmColorsBackground)
                            }
                        },
                        EditorMenuRow(
                            "Color alarm value",
                            enabledText(box.alarmColorsValue),
                        ) {
                            updateBoxConfig(boxIndex) {
                                it.copy(alarmColorsValue = !it.alarmColorsValue)
                            }
                        },
                    ),
                ),
                EditorMenuSection(
                    "Visibility",
                    listOf(
                        EditorMenuRow("Icon", shownText(box.showIcon)) {
                            updateBoxConfig(boxIndex) { it.copy(showIcon = !it.showIcon) }
                        },
                        EditorMenuRow("Unit", shownText(box.showUnit)) {
                            updateBoxConfig(boxIndex) { it.copy(showUnit = !it.showUnit) }
                        },
                        EditorMenuRow("Min/max", shownText(box.showMinMax)) {
                            updateBoxConfig(boxIndex) { it.copy(showMinMax = !it.showMinMax) }
                        },
                    ),
                ),
                EditorMenuSection(
                    "Layout",
                    listOf(
                        EditorMenuRow("Position and span", position, enabled = false) {},
                        EditorMenuRow(
                            "Page grid",
                            if (layoutEditable) {
                                "${pageConfig.rows} rows × ${pageConfig.columns} columns"
                            } else {
                                "Fixed for this dashboard style"
                            },
                            enabled = layoutEditable,
                        ) {
                            onEditPageGrid()
                        },
                        EditorMenuRow(
                            "Start merge",
                            when {
                                !layoutEditable -> "Only available on simple pages"
                                mergeTargetCount == 0 -> {
                                    "No rectangular adjacent cell available"
                                }
                                else -> {
                                    "Select one of $mergeTargetCount highlighted cells"
                                }
                            },
                            enabled = layoutEditable && mergeTargetCount > 0,
                        ) {
                            onStartMerge(boxIndex)
                        },
                        EditorMenuRow(
                            "Split merged box",
                            "Restore one box per occupied cell",
                            enabled = layoutEditable &&
                                (box.rowSpan > 1 || box.columnSpan > 1),
                        ) {
                            updateCurrentPage { it.splitBox(boxIndex) }
                        },
                        EditorMenuRow(
                            "Remove box",
                            "The empty cell can be added again in edit mode",
                            enabled = layoutEditable && pageConfig.boxes.size > 1,
                        ) {
                            updateCurrentPage { it.removeBox(boxIndex) }
                        },
                    ),
                ),
                EditorMenuSection(
                    "Actions",
                    listOf(
                        EditorMenuRow("Apply visual style to page") {
                            confirmApplyStyleToPage(boxIndex)
                        },
                        EditorMenuRow("Set default alarms", box.sensor.label) {
                            updateBoxConfig(boxIndex) { withDefaultAlarmThresholds(it) }
                        },
                        EditorMenuRow("Disable alarms", box.sensor.label) {
                            updateBoxConfig(boxIndex) { clearAlarmThresholds(it) }
                        },
                        EditorMenuRow("Reset this box style") {
                            updateBoxConfig(boxIndex) { resetBoxStyle(it) }
                        },
                    ),
                ),
            ),
        )
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
        val modes = listOf(
            DashboardSmoothingMode.OFF,
            DashboardSmoothingMode.LIGHT,
            DashboardSmoothingMode.MEDIUM,
            DashboardSmoothingMode.STRONG,
            DashboardSmoothingMode.CUSTOM,
        )
        DarkActionDialog.show(
            activity = context,
            title = "Smoothing",
            subtitle =
                "Time-based smoothing is applied after periodic-noise correction. " +
                    "The setting belongs only to this dashboard box.",
            items = modes.map { mode ->
                val selected = box.smoothingMode == mode
                val detail = when (mode) {
                    DashboardSmoothingMode.OFF -> "No smoothing"
                    DashboardSmoothingMode.CUSTOM ->
                        String.format(Locale.US, "%.2f-second time constant", box.smoothingTimeSeconds)
                    else -> String.format(
                        Locale.US,
                        "%.2f-second time constant",
                        mode.presetTimeSeconds,
                    )
                }
                DarkActionItem(
                    title = "${mode.label}${if (selected) "  ✓" else ""}",
                    description = detail,
                    accentColor = if (selected) Color.rgb(123, 204, 255) else Color.WHITE,
                ) {
                    if (mode == DashboardSmoothingMode.CUSTOM) {
                        showCustomSmoothingEditor(boxIndex, box)
                    } else {
                        onSmoothingChanged(boxIndex, box)
                        updateBoxConfig(boxIndex) {
                            it.copy(
                                smoothingMode = mode,
                                smoothingTimeSeconds = mode.presetTimeSeconds,
                            )
                        }
                    }
                }
            },
        )
    }

    private fun showCustomSmoothingEditor(boxIndex: Int, box: DashboardBoxConfig) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            hint = "0.20"
            setText(String.format(Locale.US, "%.2f", box.smoothingTimeSeconds.coerceAtLeast(0.03f)))
            selectAll()
        }
        AlertDialog.Builder(context)
            .setTitle("Custom smoothing")
            .setMessage("Enter the smoothing time constant in seconds (0.03–3.00).")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val seconds = input.text.toString().replace(',', '.').toFloatOrNull()
                    ?.coerceIn(0.03f, 3.0f)
                    ?: return@setPositiveButton
                onSmoothingChanged(boxIndex, box)
                updateBoxConfig(boxIndex) {
                    it.copy(
                        smoothingMode = DashboardSmoothingMode.CUSTOM,
                        smoothingTimeSeconds = seconds,
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPeriodicNoisePicker(boxIndex: Int, box: DashboardBoxConfig) {
        val signalId = box.sensor.calibrationSignalId
        val available = signalId != null && isPeriodicNoiseCalibrationAvailable(box.sensor)
        val selected = box.periodicNoiseFilter
        DarkActionDialog.show(
            activity = context,
            title = "Periodic noise filter",
            subtitle = if (available) {
                "A saved engine-off calibration is available for ${box.sensor.label}. " +
                    "Correction is applied before ordinary smoothing."
            } else {
                "The saved calibration does not contain a usable model for " +
                    "${box.sensor.label}."
            },
            items = listOf(
                DarkActionItem(
                    title = "Off${if (selected == DashboardPeriodicNoiseFilter.OFF) "  ✓" else ""}",
                    description = "Show the decoded raw value before ordinary smoothing.",
                    accentColor = if (selected == DashboardPeriodicNoiseFilter.OFF) {
                        Color.rgb(123, 204, 255)
                    } else {
                        Color.WHITE
                    },
                ) {
                    updateBoxConfig(boxIndex) {
                        it.copy(periodicNoiseFilter = DashboardPeriodicNoiseFilter.OFF)
                    }
                },
                DarkActionItem(
                    title = "Calibrated${if (selected == DashboardPeriodicNoiseFilter.CALIBRATED) "  ✓" else ""}",
                    description = if (available) {
                        "Use this signal's saved engine-off amplitude and phase model."
                    } else {
                        "Unavailable for this signal; open Live Data to inspect or recalibrate."
                    },
                    accentColor = if (selected == DashboardPeriodicNoiseFilter.CALIBRATED) {
                        Color.rgb(123, 204, 255)
                    } else if (available) {
                        Color.WHITE
                    } else {
                        Color.rgb(150, 158, 172)
                    },
                ) {
                    if (available) {
                        updateBoxConfig(boxIndex) {
                            it.copy(
                                periodicNoiseFilter = DashboardPeriodicNoiseFilter.CALIBRATED,
                            )
                        }
                    } else {
                        showPeriodicNoiseCalibration()
                    }
                },
                DarkActionItem(
                    title = "Open Live Data / calibration",
                    description = "Inspect the learned profile, test correction, or learn again.",
                    accentColor = Color.rgb(150, 210, 245),
                    onClick = showPeriodicNoiseCalibration,
                ),
            ),
        )
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
                            smoothingMode = defaultSmoothingMode(selected),
                            smoothingTimeSeconds = defaultSmoothingTimeSeconds(selected),
                            periodicNoiseFilter = DashboardPeriodicNoiseFilter.OFF,
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
                iconValueGapScale = 1.0f,
                minMaxScale = 1.0f,
                decimalPlaces = defaultDisplayDecimals(box.sensor),
                splitValueDigits = true,
                smoothingMode = defaultSmoothingMode(box.sensor),
                smoothingTimeSeconds = defaultSmoothingTimeSeconds(box.sensor),
                periodicNoiseFilter = DashboardPeriodicNoiseFilter.OFF,
                backgroundColor = Color.rgb(11, 14, 20),
                foregroundColor = Color.WHITE,
                valueColor = Color.WHITE,
                unitColor = Color.rgb(210, 216, 225),
                alarmColor = Color.rgb(255, 72, 72),
                minColor = Color.rgb(80, 170, 255),
                maxColor = Color.rgb(255, 72, 72),
                borderColor = Color.rgb(255, 110, 36),
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


    private fun spanSummary(rows: Int, columns: Int): String =
        "$rows ${if (rows == 1) "row" else "rows"} × " +
            "$columns ${if (columns == 1) "column" else "columns"}"

    private fun decimalsText(decimals: Int): String =
        when (decimals.coerceIn(0, 2)) {
            0 -> "0 decimals"
            1 -> "1 decimal"
            else -> "2 decimals"
        }

    private fun smoothingText(box: DashboardBoxConfig): String =
        when (box.smoothingMode) {
            DashboardSmoothingMode.OFF -> "off"
            DashboardSmoothingMode.CUSTOM ->
                String.format(Locale.US, "custom · %.2f s", box.smoothingTimeSeconds)
            else -> String.format(
                Locale.US,
                "%s · %.2f s",
                box.smoothingMode.label.lowercase(Locale.US),
                box.smoothingMode.presetTimeSeconds,
            )
        }

    private fun periodicNoiseText(box: DashboardBoxConfig): String =
        when {
            box.sensor.calibrationSignalId == null -> "not applicable"
            box.periodicNoiseFilter == DashboardPeriodicNoiseFilter.OFF -> "off"
            isPeriodicNoiseCalibrationAvailable(box.sensor) -> "calibrated"
            else -> "calibration missing"
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
        val BORDER_COLORS = listOf(
            NamedColor("UTCOMP orange", Color.rgb(255, 110, 36)),
            NamedColor("White", Color.WHITE),
            NamedColor("Ice blue", Color.rgb(120, 210, 255)),
            NamedColor("Green", Color.rgb(80, 220, 120)),
            NamedColor("Yellow", Color.rgb(255, 220, 70)),
            NamedColor("Red", Color.rgb(255, 72, 72)),
            NamedColor("Gray", Color.rgb(110, 120, 138)),
            NamedColor("Black", Color.BLACK),
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
            (
                VALUE_COLORS + ALARM_VALUE_COLORS + BACKGROUND_COLORS +
                    BORDER_COLORS + WARNING_COLORS + CRITICAL_COLORS
            )
                .distinctBy { it.color }
    }
}
