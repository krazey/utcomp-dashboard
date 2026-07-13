package de.krazey.utcomp.dashboard.logging

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import de.krazey.utcomp.dashboard.dashboard.DashboardBoxConfig
import de.krazey.utcomp.dashboard.dashboard.DashboardPageConfig
import de.krazey.utcomp.dashboard.dashboard.DashboardSensor
import de.krazey.utcomp.dashboard.util.fixed
import de.krazey.utcomp.dashboard.view.CsvLogGraphMarker
import de.krazey.utcomp.dashboard.view.DarkActionDialog
import de.krazey.utcomp.dashboard.view.DarkActionItem
import de.krazey.utcomp.dashboard.view.CsvLogGraphPoint
import de.krazey.utcomp.dashboard.view.CsvLogGraphView
import java.io.BufferedReader
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
        private const val CSV_VIEW_GRAPH_MAX_POINTS = 1600
    }

    fun showMenu() {
        val savedFolder = savedTreeUri()
        val items = mutableListOf<DarkActionItem>()

        items += DarkActionItem(
            title = "View latest internal log",
            description = "Open the newest CSV stored in the app's private storage.",
            onClick = {
                viewLatestCsvLog("internal", File(activity.filesDir, "utcomp-logs"))
            },
        )
        items += DarkActionItem(
            title = "View latest external log",
            description = "Open the newest CSV stored in the app-specific external folder.",
            onClick = {
                val externalRoot = activity.getExternalFilesDir("utcomp-logs")
                    ?: activity.filesDir
                viewLatestCsvLog("app external", File(externalRoot, "csv"))
            },
        )
        items += DarkActionItem(
            title = "Choose CSV file",
            description = "Open any compatible CSV log with the graph viewer.",
            onClick = ::pickCsvLogToView,
        )

        if (logger.isRunning) {
            items += DarkActionItem(
                title = "Stop CSV logging",
                description = "Finish and close the active high-resolution log.",
                accentColor = Color.rgb(255, 150, 150),
                onClick = logger::stop,
            )
        } else {
            items += DarkActionItem(
                title = "Start logging: internal storage",
                description = "Write to protected app storage.",
                onClick = ::startCsvLoggingInternal,
            )
            items += DarkActionItem(
                title = "Start logging: app external storage",
                description = "Write to the app-specific external storage folder.",
                onClick = ::startCsvLoggingAppExternal,
            )
            if (savedFolder != null) {
                items += DarkActionItem(
                    title = "Start logging: saved folder",
                    description = "Use the previously selected SD card or document folder.",
                    onClick = { startCsvLoggingTree(savedFolder) },
                )
            }
            items += DarkActionItem(
                title = "Choose folder and start logging",
                description = "Select an SD card or document folder and remember it.",
                onClick = ::pickCsvLoggingFolder,
            )
        }

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
        val flags = resultData.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

        runCatching {
            activity.contentResolver.takePersistableUriPermission(uri, flags)
        }.onFailure { error ->
            appendLog(
                "Could not persist CSV logging folder permission: " +
                    error.message,
            )
        }

        onTreeUriChanged(uri)
        startCsvLoggingTree(uri)
        return true
    }

    private fun viewLatestCsvLog(label: String, dir: File) {
        val file = dir.listFiles { candidate ->
            candidate.isFile && candidate.name.endsWith(".csv", ignoreCase = true)
        }?.maxByOrNull { candidate -> candidate.lastModified() }

        if (file == null) {
            appendLog("No CSV log found in $label: ${dir.absolutePath}")
            return
        }

        appendLog("Loading CSV viewer from $label: ${file.name}")
        loadCsvLogPreview(title = "$label: ${file.name}") {
            file.bufferedReader()
        }
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
        appendLog("Loading CSV viewer from picked file: $uri")
        loadCsvLogPreview(title = title) {
            activity.contentResolver.openInputStream(uri)?.bufferedReader()
                ?: error("Could not open selected CSV")
        }
    }

    private fun loadCsvLogPreview(
        title: String,
        openReader: () -> BufferedReader,
    ) {
        thread(name = "utcomp-csv-viewer") {
            val result = runCatching {
                openReader().use { reader ->
                    CsvLogPreviewReader.read(reader, CSV_VIEW_GRAPH_MAX_POINTS)
                }
            }

            activity.runOnUiThread {
                result
                    .onSuccess { preview -> showCsvLogPreviewDialog(title, preview) }
                    .onFailure { error ->
                        appendLog("CSV viewer failed: ${error.message}")
                    }
            }
        }
    }

    private fun showCsvLogPreviewDialog(
        title: String,
        preview: CsvViewerPreview,
    ) {
        val padding = dp(12)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            minimumHeight =
                (activity.resources.displayMetrics.heightPixels * 0.86f).toInt()
            background = GradientDrawable().apply {
                setColor(Color.rgb(8, 10, 14))
                cornerRadius = 0f
            }
        }

        val titleText = TextView(activity).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(238, 240, 246))
            isSingleLine = false
        }
        root.addView(
            titleText,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

        val metaText = TextView(activity).apply {
            text = buildString {
                append("rows=${preview.totalRows}")
                append(" · graph=${preview.graphRows.size}")
                append(" · ${preview.durationText}")
                if (preview.sampleRateHz.isFinite()) {
                    append(" · ${preview.sampleRateHz.fixed(1)} Hz")
                }
                append('\n')
                append("${preview.firstTime.takeLast(12)} → ")
                append(preview.lastTime.takeLast(12))
                append('\n')
                append(preview.sourceColumns)
            }
            textSize = 11f
            setTextColor(Color.rgb(170, 178, 190))
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(
            metaText,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

        val summaryRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        summaryRow.addView(
            summaryCard("AFR", preview.stats.afr, 2, ""),
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ),
        )
        summaryRow.addView(
            summaryCard("Boost", preview.stats.boost, 2, "bar"),
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ),
        )
        summaryRow.addView(
            summaryCard("Oil P", preview.stats.oilPressure, 2, "bar"),
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ),
        )
        summaryRow.addView(
            summaryCard("Oil T", preview.stats.oilTemp, 1, "°C"),
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ),
        )
        root.addView(
            summaryRow,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

        val markers = graphWarningMarkers()
        val markerText = TextView(activity).apply {
            text = if (markers.isEmpty()) {
                "No active warning/critical markers configured for these " +
                    "graph channels."
            } else {
                "Markers: " + markers.joinToString(" · ") { marker ->
                    "${marker.seriesLabel} ${marker.label}"
                }
            }
            textSize = 10.5f
            setTextColor(Color.rgb(170, 178, 190))
            setPadding(0, dp(7), 0, 0)
        }
        root.addView(
            markerText,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

        val graph = CsvLogGraphView(activity).apply {
            setRows(preview.graphRows.map { row ->
                CsvLogGraphPoint(
                    time = row.time.takeLast(12),
                    afr = row.afr,
                    boost = row.boost,
                    oilPressure = row.oilPressure,
                    oilTemp = row.oilTemp,
                )
            })
            setMarkers(markers)
        }
        root.addView(
            graph,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(560),
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            },
        )

        val outerScroll = ScrollView(activity).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(8, 10, 14))
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("CSV viewer")
            .setView(outerScroll)
            .setPositiveButton("Close", null)
            .show()

        dialog.window?.decorView?.setPadding(0, 0, 0, 0)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
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

    private fun summaryCard(
        label: String,
        range: CsvViewerRange,
        decimals: Int,
        suffix: String,
    ): TextView = TextView(activity).apply {
        text = buildString {
            appendLine(label)
            append(range.min.fixed(decimals))
            append(" → ")
            append(range.max.fixed(decimals))
            if (suffix.isNotBlank()) append(" $suffix")
        }
        textSize = 10.5f
        setTextColor(Color.rgb(234, 236, 242))
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(5), dp(7), dp(5), dp(7))
        background = GradientDrawable().apply {
            setColor(Color.rgb(18, 21, 28))
            cornerRadius = dp(9).toFloat()
            setStroke(1, Color.rgb(50, 56, 68))
        }
    }

    private fun dp(value: Int): Int =
        (value.toFloat() * activity.resources.displayMetrics.density + 0.5f)
            .toInt()

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
