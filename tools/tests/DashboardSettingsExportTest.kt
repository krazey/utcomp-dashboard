package de.krazey.utcomp.dashboard.settings

fun main() {
    val exported = DashboardSettingsExport.encode(
        snapshot = DashboardSettingsSnapshot(
            appVersion = "1.1.0",
            dashboardStyle = "SIMPLE",
            selectedSimplePageId = "custom-page",
            simplePagesJson =
                """[{"id":"custom-page","title":"Track","boxes":[{"sensor":"BOOST"}]}]""",
            ralliartPageJson =
                """[{"id":"ralliart","ralliartHeaderTextScale":1.25}]""",
            protocolLogEnabled = false,
            liveDataSettingsJson =
                """{"signalId":"boost","smoothingAlpha":0.35,"windowMs":30000}""",
            periodicNoiseCalibrationJson =
                """{"version":1,"frequencyHz":0.38,"signals":[]}""",
            csvQuickTarget = "app_external",
        ),
        exportedAtUtc = "2026-07-30T12:34:56.000Z",
    )

    check(exported.contains("\"format\": \"${DashboardSettingsExport.FORMAT}\""))
    check(exported.contains("\"version\": ${DashboardSettingsExport.VERSION}"))
    check(exported.contains("\"appVersion\": \"1.1.0\""))
    check(exported.contains("\"selectedSimplePageId\": \"custom-page\""))
    check(exported.contains("\"title\":\"Track\""))
    check(exported.contains("\"ralliartHeaderTextScale\":1.25"))
    check(exported.contains("\"signalId\":\"boost\""))
    check(exported.contains("\"periodicNoiseCalibration\": {\"version\":1"))
    check(exported.contains("\"csvQuickTarget\": \"app_external\""))
    check(exported.lines().last { it.isNotBlank() } == "}")

    val withoutCalibration = DashboardSettingsExport.encode(
        snapshot = DashboardSettingsSnapshot(
            appVersion = "1.1.0",
            dashboardStyle = "FANCY",
            selectedSimplePageId = "default",
            simplePagesJson = "[]",
            ralliartPageJson = "[]",
            protocolLogEnabled = true,
            liveDataSettingsJson = "{}",
            periodicNoiseCalibrationJson = null,
            csvQuickTarget = "internal",
        ),
        exportedAtUtc = "2026-07-30T12:34:56.000Z",
    )
    check(withoutCalibration.contains("\"periodicNoiseCalibration\": null"))

    println("Dashboard settings export tests passed")
}
