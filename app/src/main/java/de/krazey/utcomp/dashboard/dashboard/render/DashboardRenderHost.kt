package de.krazey.utcomp.dashboard.dashboard.render

import android.view.View
import de.krazey.utcomp.dashboard.dashboard.DashboardBoxConfig
import de.krazey.utcomp.dashboard.dashboard.DashboardSensor
import de.krazey.utcomp.dashboard.utcomp.UtcompDataSnapshot

enum class DashboardAlarmLevel {
    NORMAL,
    WARNING,
    CRITICAL,
}

enum class DashboardMergeVisualState {
    NONE,
    SOURCE,
    TARGET,
    BLOCKED,
}

data class DashboardMinMax(
    var min: Float = Float.NaN,
    var max: Float = Float.NaN,
)

interface DashboardRenderHost {
    fun isEditMode(): Boolean

    fun displayValueForBox(
        box: DashboardBoxConfig,
        boxIndex: Int,
        rawValue: Float?,
    ): Float?

    fun formatBoxValue(value: Float?, decimals: Int): String

    fun styledValueText(valueText: String, split: Boolean): CharSequence

    fun alarmLevelFor(
        box: DashboardBoxConfig,
        rawValue: Float?,
        snapshot: UtcompDataSnapshot? = null,
    ): DashboardAlarmLevel

    fun boxBackgroundColor(
        box: DashboardBoxConfig,
        alarmLevel: DashboardAlarmLevel,
    ): Int

    fun boxValueColor(
        box: DashboardBoxConfig,
        alarmLevel: DashboardAlarmLevel,
    ): Int

    fun currentClockText(): String

    fun trackMinMax(key: String, value: Float): DashboardMinMax

    fun shouldShowMinMax(key: String): Boolean

    fun attachBoxActions(
        view: View,
        boxIndex: Int,
        minMaxKey: String?,
        editorTitle: String,
    )

    fun attachRalliartHeaderActions(view: View)

    fun mergeVisualStateForBox(boxIndex: Int): DashboardMergeVisualState

    fun mergeVisualStateForCell(row: Int, column: Int): DashboardMergeVisualState

    fun selectMergeCell(row: Int, column: Int)

    fun sourceSubtitleFor(sensor: DashboardSensor): String

    fun fallbackIconFor(sensor: DashboardSensor): String

    fun iconDrawableResourceId(resourceName: String?): Int

    fun addBoxAt(row: Int, column: Int)
}
