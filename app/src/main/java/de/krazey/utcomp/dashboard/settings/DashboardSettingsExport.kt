package de.krazey.utcomp.dashboard.settings

internal data class DashboardSettingsSnapshot(
    val appVersion: String,
    val dashboardStyle: String,
    val selectedSimplePageId: String,
    val simplePagesJson: String,
    val ralliartPageJson: String,
    val protocolLogEnabled: Boolean,
    val liveDataSettingsJson: String,
    val periodicNoiseCalibrationJson: String?,
    val csvQuickTarget: String,
)

/**
 * Versioned, portable backup of app-owned settings.
 *
 * Controller calibration is deliberately excluded because the Reveltronics
 * desktop application already owns that backup and restore workflow.
 */
internal object DashboardSettingsExport {
    const val FORMAT = "utcomp-dashboard-settings"
    const val VERSION = 1

    fun encode(
        snapshot: DashboardSettingsSnapshot,
        exportedAtUtc: String,
    ): String = buildString {
        appendLine("{")
        appendLine("  \"format\": ${FORMAT.jsonString()},")
        appendLine("  \"version\": $VERSION,")
        appendLine("  \"appVersion\": ${snapshot.appVersion.jsonString()},")
        appendLine("  \"exportedAtUtc\": ${exportedAtUtc.jsonString()},")
        appendLine("  \"dashboard\": {")
        appendLine("    \"style\": ${snapshot.dashboardStyle.jsonString()},")
        appendLine(
            "    \"selectedSimplePageId\": " +
                snapshot.selectedSimplePageId.jsonString() +
                ",",
        )
        appendLine("    \"simplePages\": ${snapshot.simplePagesJson},")
        appendLine("    \"ralliartPages\": ${snapshot.ralliartPageJson},")
        appendLine("    \"protocolLogEnabled\": ${snapshot.protocolLogEnabled}")
        appendLine("  },")
        appendLine("  \"liveData\": ${snapshot.liveDataSettingsJson},")
        appendLine(
            "  \"periodicNoiseCalibration\": " +
                (snapshot.periodicNoiseCalibrationJson ?: "null") +
                ",",
        )
        appendLine("  \"csvQuickTarget\": ${snapshot.csvQuickTarget.jsonString()}")
        appendLine("}")
    }

    private fun String.jsonString(): String = buildString {
        append('"')
        this@jsonString.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u%04x".format(character.code))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }
}
