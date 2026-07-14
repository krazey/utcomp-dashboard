package de.krazey.utcomp.dashboard.logging

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import de.krazey.utcomp.dashboard.dashboard.DashboardBoxConfig
import de.krazey.utcomp.dashboard.dashboard.DashboardPageConfig
import de.krazey.utcomp.dashboard.dashboard.DashboardSensor
import de.krazey.utcomp.dashboard.util.fixed
import de.krazey.utcomp.dashboard.view.CsvLogGraphMarker
import de.krazey.utcomp.dashboard.view.DarkActionDialog
import de.krazey.utcomp.dashboard.view.DarkActionItem
import java.io.File
import kotlin.concurrent.thread

internal class CsvLogController(
    private val activity: Activity,
    private val logger: UtcompCsvLogger,
    private val appendLog: (String) -> Unit,
    private val savedTreeUri: () -> Uri?,
    private val onTreeUriChanged: (Uri) -> Unit,
    private val currentPageConfig: () -> DashboardPageConfig,
    private val dashboardPages: () -> List<DashboardPageConfig>,
) {
    private companion object {
        private const val DATA_LOG_TREE_REQUEST = 42_100
        private const val DATA_LOG_VIEW_FILE_REQUEST = 42_101
        private const val PREFS_NAME = "utcomp_csv_logging"
        private const val PREF_QUICK_TARGET = "quick_target"
    }

    private enum class QuickTarget(
        val storedValue: String,
        val label: String,
        val description: String,
    ) {
        INTERNAL(
            storedValue = "internal",
            label = "Internal app storage",
            description = "Protected app storage; quickest and always available.",
        ),
        APP_EXTERNAL(
            storedValue = "app_external",
            label = "App external storage",
            description = "App-specific external storage under Android/data.",
        ),
        SAVED_FOLDER(
            storedValue = "saved_folder",
            label = "Saved folder",
            description = "Previously selected SD card or document folder.",
        ),
    }

    private val prefs = activity.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    val isLogging: Boolean
        get() = logger.isRunning

    fun quickActionLabel(): String =
        if (logger.isRunning) "■ STOP" else "● LOG"

    fun quickActionDescription(): String =
        if (logger.isRunning) {
            "Stop active high-resolution CSV logging"
        } else {
            "Start high-resolution CSV logging in ${quickTarget().label}"
        }

    fun toggleQuickLogging() {
        if (logger.isRunning) {
            stopLoggingAsync()
        } else {
            startQuickLogging()
        }
    }

    fun showMenu() {
        val target = quickTarget()
        val items = mutableListOf<DarkActionItem>()

        if (logger.isRunning) {
            items += DarkActionItem(
                title = "Stop CSV logging",
                description = "Finish and close the active high-resolution log.",
                accentColor = Color.rgb(255, 150, 150),
                onClick = ::stopLoggingAsync,
            )
        } else {
            items += DarkActionItem(
                title = "Start logging now",
                description = "Quick destination: ${target.label}.",
                accentColor = Color.rgb(150, 230, 175),
                onClick = ::startQuickLogging,
            )
        }

        items += DarkActionItem(
            title = "Quick-log destination",
            description = "Current: ${target.label}. ${target.description}",
            onClick = ::showQuickTargetMenu,
        )
        items += DarkActionItem(
            title = "View latest internal log",
            description = "Open the newest private CSV in the full-screen viewer.",
            onClick = {
                viewLatestCsvLog("internal", File(activity.filesDir, "utcomp-logs"))
            },
        )
        items += DarkActionItem(
            title = "View latest external log",
            description = "Open the newest app-external CSV in the full-screen viewer.",
            onClick = {
                val externalRoot = activity.getExternalFilesDir("utcomp-logs")
                    ?: activity.filesDir
                viewLatestCsvLog("app external", File(externalRoot, "csv"))
            },
        )
        items += DarkActionItem(
            title = "Choose CSV file",
            description = "Open any compatible CSV using bounded-memory streaming.",
            onClick = ::pickCsvLogToView,
        )

        DarkActionDialog.show(
            activity = activity,
            title = "High-resolution data logging",
            subtitle = logger.statusText(),
            items = items,
        )
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == DATA_LOG_VIEW_FILE_REQUEST) {
            if (resultCode != Activity.RESULT_OK) return true
            val uri = data?.data ?: run {
                appendLog("CSV viewer file selection returned no URI")
                return true
            }
            viewPickedCsvLog(uri)
            return true
        }

        if (requestCode != DATA_LOG_TREE_REQUEST) return false
        if (resultCode != Activity.RESULT_OK) return true

        val resultData = data ?: run {
            appendLog("CSV logging folder selection returned no data")
            return true
        }
        val uri = resultData.data ?: run {
            appendLog("CSV logging folder selection returned no URI")
            return true
        }
        runCatching {
            persistGrantedTreePermission(uri, resultData.flags)
        }.onFailure { error ->
            appendLog(
                "Could not persist CSV logging folder permission: " +
                    error.message,
            )
        }

        onTreeUriChanged(uri)
        setQuickTarget(QuickTarget.SAVED_FOLDER)
        appendLog("CSV quick-log destination set to saved folder: $uri")
        return true
    }

    private fun persistGrantedTreePermission(uri: Uri, resultFlags: Int) {
        val readGranted = resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        val writeGranted = resultFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
        val resolver = activity.contentResolver

        when {
            readGranted && writeGranted -> resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            readGranted -> resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            writeGranted -> resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            else -> error("Folder picker granted no read or write permission")
        }
    }

    private fun showQuickTargetMenu() {
        val current = quickTarget()
        val items = mutableListOf<DarkActionItem>()

        fun targetItem(target: QuickTarget) = DarkActionItem(
            title = if (target == current) "✓ ${target.label}" else target.label,
            description = target.description,
            accentColor = if (target == current) {
                Color.rgb(150, 230, 175)
            } else {
                Color.WHITE
            },
            onClick = {
                setQuickTarget(target)
                appendLog("CSV quick-log destination set to ${target.label}")
            },
        )

        items += targetItem(QuickTarget.INTERNAL)
        items += targetItem(QuickTarget.APP_EXTERNAL)
        if (savedTreeUri() != null) {
            items += targetItem(QuickTarget.SAVED_FOLDER)
        }
        items += DarkActionItem(
            title = "Choose folder",
            description = "Select and remember a folder, then use it for quick logging.",
            onClick = ::pickCsvLoggingFolder,
        )

        DarkActionDialog.show(
            activity = activity,
            title = "Quick-log destination",
            subtitle = "The top-bar LOG button uses this destination.",
            items = items,
        )
    }

    private fun quickTarget(): QuickTarget {
        val stored = prefs.getString(
            PREF_QUICK_TARGET,
            QuickTarget.APP_EXTERNAL.storedValue,
        )
        val target = QuickTarget.entries.firstOrNull { it.storedValue == stored }
            ?: QuickTarget.APP_EXTERNAL
        return if (target == QuickTarget.SAVED_FOLDER && savedTreeUri() == null) {
            QuickTarget.APP_EXTERNAL
        } else {
            target
        }
    }

    private fun setQuickTarget(target: QuickTarget) {
        prefs.edit().putString(PREF_QUICK_TARGET, target.storedValue).apply()
    }

    private fun stopLoggingAsync() {
        thread(name = "utcomp-csv-stop", isDaemon = true) {
            logger.stop()
        }
    }

    private fun startQuickLogging() {
        when (quickTarget()) {
            QuickTarget.INTERNAL -> startCsvLoggingInternal()
            QuickTarget.APP_EXTERNAL -> startCsvLoggingAppExternal()
            QuickTarget.SAVED_FOLDER -> {
                val uri = savedTreeUri()
                if (uri == null) {
                    appendLog("Saved CSV folder unavailable; using app external storage")
                    setQuickTarget(QuickTarget.APP_EXTERNAL)
                    startCsvLoggingAppExternal()
                } else {
                    startCsvLoggingTree(uri)
                }
            }
        }
    }

    private fun viewLatestCsvLog(label: String, dir: File) {
        val file = dir.listFiles { candidate ->
            candidate.isFile && candidate.name.endsWith(".csv", ignoreCase = true)
        }?.maxByOrNull { candidate -> candidate.lastModified() }

        if (file == null) {
            appendLog("No CSV log found in $label: ${dir.absolutePath}")
            return
        }

        appendLog("Opening full-screen CSV viewer from $label: ${file.name}")
        CsvLogViewerActivity.launchFile(
            activity = activity,
            title = "$label: ${file.name}",
            file = file,
            markers = graphWarningMarkers(),
        )
    }

    private fun pickCsvLogToView() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "text/csv",
                    "text/comma-separated-values",
                    "application/csv",
                    "text/plain",
                ),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivityForResult(intent, DATA_LOG_VIEW_FILE_REQUEST)
    }

    private fun viewPickedCsvLog(uri: Uri) {
        val title = "picked: ${uri.lastPathSegment ?: uri}"
        appendLog("Opening full-screen CSV viewer from picked file: $uri")
        CsvLogViewerActivity.launchUri(
            activity = activity,
            title = title,
            uri = uri,
            markers = graphWarningMarkers(),
        )
    }

    private fun graphWarningMarkers(): List<CsvLogGraphMarker> {
        val currentBoxes = currentPageConfig().boxes
        val allBoxes = currentBoxes + dashboardPages().flatMap { it.boxes }

        fun boxFor(sensor: DashboardSensor): DashboardBoxConfig? =
            currentBoxes.firstOrNull { it.sensor == sensor }
                ?: allBoxes.firstOrNull { it.sensor == sensor }

        fun Float.markerText(decimals: Int): String = fixed(decimals)

        fun addMarkers(
            target: MutableList<CsvLogGraphMarker>,
            seriesLabel: String,
            box: DashboardBoxConfig?,
            decimals: Int,
            lowSuffix: String = "",
        ) {
            if (box == null) return

            if (box.warningLow.isFinite()) {
                target += CsvLogGraphMarker(
                    seriesLabel = seriesLabel,
                    value = box.warningLow,
                    label = "W≤${box.warningLow.markerText(decimals)}$lowSuffix",
                    color = box.warningColor,
                )
            }
            if (box.criticalLow.isFinite()) {
                target += CsvLogGraphMarker(
                    seriesLabel = seriesLabel,
                    value = box.criticalLow,
                    label = "C≤${box.criticalLow.markerText(decimals)}$lowSuffix",
                    color = box.criticalColor,
                )
            }
            if (box.warningHigh.isFinite()) {
                target += CsvLogGraphMarker(
                    seriesLabel = seriesLabel,
                    value = box.warningHigh,
                    label = "W≥${box.warningHigh.markerText(decimals)}",
                    color = box.warningColor,
                )
            }
            if (box.criticalHigh.isFinite()) {
                target += CsvLogGraphMarker(
                    seriesLabel = seriesLabel,
                    value = box.criticalHigh,
                    label = "C≥${box.criticalHigh.markerText(decimals)}",
                    color = box.criticalColor,
                )
            }
        }

        val markers = mutableListOf<CsvLogGraphMarker>()
        addMarkers(markers, "AFR", boxFor(DashboardSensor.AFR), decimals = 2)
        addMarkers(
            markers,
            "Boost",
            boxFor(DashboardSensor.BOOST),
            decimals = 2,
        )

        val oilPressureBox = boxFor(DashboardSensor.OIL_PRESSURE)
        val oilPressureSuffix = if (
            oilPressureBox != null &&
            oilPressureBox.oilPressureBoostAlarm &&
            oilPressureBox.oilPressureBoostArmBar.isFinite()
        ) {
            "@${oilPressureBox.oilPressureBoostArmBar.markerText(2)}b"
        } else {
            ""
        }
        addMarkers(
            markers,
            "Oil P",
            oilPressureBox,
            decimals = 2,
            lowSuffix = oilPressureSuffix,
        )
        addMarkers(
            markers,
            "Oil T",
            boxFor(DashboardSensor.OIL_TEMP),
            decimals = 1,
        )
        return markers
    }

    private fun startCsvLoggingInternal() {
        runCatching {
            logger.startInternal()
        }.onFailure { error ->
            appendLog("CSV logging start failed: ${error.message}")
        }
    }

    private fun startCsvLoggingAppExternal() {
        runCatching {
            logger.startAppExternal()
        }.onFailure { error ->
            appendLog("CSV logging start failed: ${error.message}")
        }
    }

    private fun startCsvLoggingTree(uri: Uri) {
        runCatching {
            logger.startTree(uri)
        }.onFailure { error ->
            appendLog("CSV logging start failed: ${error.message}")
        }
    }

    private fun pickCsvLoggingFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        activity.startActivityForResult(intent, DATA_LOG_TREE_REQUEST)
    }
}
