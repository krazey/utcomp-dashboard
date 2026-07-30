package de.krazey.utcomp.dashboard.settings

import org.json.JSONObject

internal data class ImportedDashboardSettings(
    val snapshot: DashboardSettingsSnapshot,
    val exportedAtUtc: String,
)

internal object DashboardSettingsImport {
    fun decode(raw: String): ImportedDashboardSettings {
        val root = JSONObject(raw)
        require(root.getString("format") == DashboardSettingsExport.FORMAT) {
            "Not a UTCOMP Dashboard settings file"
        }
        require(root.getInt("version") == DashboardSettingsExport.VERSION) {
            "Unsupported settings version ${root.optInt("version", -1)}"
        }

        val dashboard = root.getJSONObject("dashboard")
        val periodicCalibration = if (
            root.isNull("periodicNoiseCalibration")
        ) {
            null
        } else {
            root.getJSONObject("periodicNoiseCalibration").toString()
        }
        return ImportedDashboardSettings(
            snapshot = DashboardSettingsSnapshot(
                appVersion = root.getString("appVersion"),
                dashboardStyle = dashboard.getString("style"),
                selectedSimplePageId = dashboard.getString("selectedSimplePageId"),
                simplePagesJson = dashboard.getJSONArray("simplePages").toString(),
                ralliartPageJson = dashboard.getJSONArray("ralliartPages").toString(),
                protocolLogEnabled = dashboard.getBoolean("protocolLogEnabled"),
                liveDataSettingsJson = root.getJSONObject("liveData").toString(),
                periodicNoiseCalibrationJson = periodicCalibration,
                csvQuickTarget = root.getString("csvQuickTarget"),
            ),
            exportedAtUtc = root.getString("exportedAtUtc"),
        )
    }
}
