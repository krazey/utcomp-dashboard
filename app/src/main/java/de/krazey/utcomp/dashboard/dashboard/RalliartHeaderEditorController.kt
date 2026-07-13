package de.krazey.utcomp.dashboard.dashboard

import android.app.AlertDialog
import android.content.Context
import kotlin.math.abs

internal class RalliartHeaderEditorController(
    private val context: Context,
    private val currentPageConfig: () -> DashboardPageConfig,
    private val updateCurrentPage: ((DashboardPageConfig) -> DashboardPageConfig) -> Unit,
) {
    fun showEditor() {
        val config = currentPageConfig()
        showEditorMenu(
            context = context,
            title = "Ralliart • Top bar",
            sections = listOf(
                EditorMenuSection(
                    "Display",
                    listOf(
                        EditorMenuRow(
                            "Text size",
                            scaleText(config.ralliartHeaderTextScale),
                        ) {
                            showTextScalePicker(config.ralliartHeaderTextScale)
                        },
                        EditorMenuRow(
                            "Visible fields",
                            visibleFieldsSummary(config),
                        ) {
                            showFieldPicker(config)
                        },
                    ),
                ),
                EditorMenuSection(
                    "Actions",
                    listOf(
                        EditorMenuRow("Reset top bar", "Restore size and all fields") {
                            updateCurrentPage { page ->
                                page.copy(
                                    ralliartHeaderTextScale = 1.0f,
                                    ralliartHeaderShowOutside = true,
                                    ralliartHeaderShowInside = true,
                                    ralliartHeaderShowBattery = true,
                                    ralliartHeaderShowClock = true,
                                )
                            }
                        },
                    ),
                ),
            ),
        )
    }

    private fun showTextScalePicker(current: Float) {
        val selected = HEADER_TEXT_SCALES.indices.minByOrNull { index ->
            abs(HEADER_TEXT_SCALES[index] - current)
        } ?: DEFAULT_SCALE_INDEX
        AlertDialog.Builder(context)
            .setTitle("Ralliart top-bar text size")
            .setSingleChoiceItems(HEADER_TEXT_SCALE_LABELS, selected) { dialog, which ->
                updateCurrentPage { page ->
                    page.copy(ralliartHeaderTextScale = HEADER_TEXT_SCALES[which])
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFieldPicker(config: DashboardPageConfig) {
        val checked = booleanArrayOf(
            config.ralliartHeaderShowOutside,
            config.ralliartHeaderShowInside,
            config.ralliartHeaderShowBattery,
            config.ralliartHeaderShowClock,
        )
        AlertDialog.Builder(context)
            .setTitle("Ralliart top-bar fields")
            .setMultiChoiceItems(HEADER_FIELD_LABELS, checked) { _, which, enabled ->
                checked[which] = enabled
            }
            .setPositiveButton("Apply") { _, _ ->
                updateCurrentPage { page ->
                    page.copy(
                        ralliartHeaderShowOutside = checked[0],
                        ralliartHeaderShowInside = checked[1],
                        ralliartHeaderShowBattery = checked[2],
                        ralliartHeaderShowClock = checked[3],
                    )
                }
            }
            .setNeutralButton("All") { _, _ ->
                updateCurrentPage { page ->
                    page.copy(
                        ralliartHeaderShowOutside = true,
                        ralliartHeaderShowInside = true,
                        ralliartHeaderShowBattery = true,
                        ralliartHeaderShowClock = true,
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun visibleFieldsSummary(config: DashboardPageConfig): String {
        val fields = buildList {
            if (config.ralliartHeaderShowOutside) add("Outside")
            if (config.ralliartHeaderShowInside) add("Inside")
            if (config.ralliartHeaderShowBattery) add("Battery")
            if (config.ralliartHeaderShowClock) add("Time")
        }
        return fields.joinToString().ifEmpty { "None" }
    }

    private fun scaleText(scale: Float): String = "${(scale * 100f).toInt()}%"

    private companion object {
        val HEADER_TEXT_SCALES = floatArrayOf(
            0.5f,
            0.65f,
            0.8f,
            1.0f,
            1.15f,
            1.3f,
            1.5f,
            1.75f,
            2.0f,
        )
        val HEADER_TEXT_SCALE_LABELS = arrayOf(
            "50%",
            "65%",
            "80%",
            "100%",
            "115%",
            "130%",
            "150%",
            "175%",
            "200%",
        )
        val HEADER_FIELD_LABELS = arrayOf(
            "Outside temperature",
            "Inside temperature",
            "Battery voltage",
            "Time",
        )
        const val DEFAULT_SCALE_INDEX = 3
    }
}
