package de.krazey.utcomp.dashboard.logging

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import de.krazey.utcomp.dashboard.util.fixed
import de.krazey.utcomp.dashboard.view.CsvLogGraphMarker
import de.krazey.utcomp.dashboard.view.CsvLogGraphPoint
import de.krazey.utcomp.dashboard.view.CsvLogGraphSeries
import de.krazey.utcomp.dashboard.view.CsvLogGraphView
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Full-screen, bounded-memory viewer for high-resolution UTCOMP CSV logs. */
class CsvLogViewerActivity : Activity() {
    companion object {
        private const val EXTRA_TITLE = "csv_title"
        private const val EXTRA_FILE_PATH = "csv_file_path"
        private const val EXTRA_CONTENT_URI = "csv_content_uri"
        private const val EXTRA_MARKER_SERIES = "csv_marker_series"
        private const val EXTRA_MARKER_VALUES = "csv_marker_values"
        private const val EXTRA_MARKER_LABELS = "csv_marker_labels"
        private const val EXTRA_MARKER_COLORS = "csv_marker_colors"
        private const val STATE_SELECTED_SERIES = "csv_selected_series"
        private const val READER_BUFFER_SIZE = 64 * 1024
        private const val MIN_GRAPH_POINTS = 1_600
        private const val MAX_GRAPH_POINTS = 4_000
        private const val MAX_VISIBLE_SERIES = 12

        fun launchFile(
            activity: Activity,
            title: String,
            file: File,
            markers: List<CsvLogGraphMarker>,
        ) {
            activity.startActivity(
                baseIntent(activity, title, markers)
                    .putExtra(EXTRA_FILE_PATH, file.absolutePath),
            )
        }

        fun launchUri(
            activity: Activity,
            title: String,
            uri: Uri,
            markers: List<CsvLogGraphMarker>,
        ) {
            activity.startActivity(
                baseIntent(activity, title, markers)
                    .setData(uri)
                    .putExtra(EXTRA_CONTENT_URI, uri.toString())
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }

        private fun baseIntent(
            context: Context,
            title: String,
            markers: List<CsvLogGraphMarker>,
        ): Intent = Intent(context, CsvLogViewerActivity::class.java).apply {
            putExtra(EXTRA_TITLE, title)
            putStringArrayListExtra(
                EXTRA_MARKER_SERIES,
                ArrayList(markers.map { it.seriesLabel }),
            )
            putExtra(EXTRA_MARKER_VALUES, markers.map { it.value }.toFloatArray())
            putStringArrayListExtra(
                EXTRA_MARKER_LABELS,
                ArrayList(markers.map { it.label }),
            )
            putExtra(EXTRA_MARKER_COLORS, markers.map { it.color }.toIntArray())
        }
    }

    private val cancelled = AtomicBoolean(false)
    private val loadGeneration = AtomicInteger(0)
    private val selectedSeriesIds = linkedSetOf<String>()

    private lateinit var body: LinearLayout
    private lateinit var loadingStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var rowsButton: Button
    private var currentPreview: CsvViewerPreview? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState
            ?.getStringArrayList(STATE_SELECTED_SERIES)
            ?.let(selectedSeriesIds::addAll)
        buildUi()
        loadPreview()
        AppDiagnostics.info("CSV_VIEWER", "Opened full-screen CSV viewer")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(
            STATE_SELECTED_SERIES,
            ArrayList(selectedSeriesIds),
        )
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    override fun onDestroy() {
        cancelled.set(true)
        loadGeneration.incrementAndGet()
        AppDiagnostics.info("CSV_VIEWER", "Closed full-screen CSV viewer")
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.rgb(5, 7, 11))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(
                color = Color.rgb(12, 15, 21),
                radius = dp(14).toFloat(),
                strokeColor = Color.rgb(38, 78, 104),
                strokeWidth = dp(2),
            )
            setPadding(dp(14), dp(8), dp(8), dp(8))
        }

        toolbar.addView(
            TextView(this).apply {
                text = intent.getStringExtra(EXTRA_TITLE) ?: "CSV log"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                maxLines = 2
            },
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginEnd = dp(8) },
        )

        rowsButton = toolbarButton("ROWS") {
            currentPreview?.let(::showSeriesSelection)
        }
        toolbar.addView(
            rowsButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(56),
            ).apply { marginEnd = dp(8) },
        )

        toolbar.addView(
            toolbarButton("✕ EXIT") { finish() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(56),
            ),
        )

        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) },
        )

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(5, 7, 11))
        }
        root.addView(
            body,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        setContentView(root)
    }

    private fun toolbarButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 15f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            minHeight = dp(56)
            minWidth = dp(108)
            background = roundedBackground(
                color = Color.BLACK,
                radius = dp(13).toFloat(),
                strokeColor = Color.rgb(38, 78, 104),
                strokeWidth = dp(2),
            )
            setOnClickListener { onClick() }
        }

    private fun showLoading() {
        body.removeAllViews()
        body.gravity = Gravity.CENTER
        progressBar = ProgressBar(this).apply { isIndeterminate = true }
        loadingStatus = TextView(this).apply {
            text = "Opening CSV log…"
            textSize = 16f
            setTextColor(Color.rgb(218, 224, 236))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(8))
        }
        body.addView(progressBar)
        body.addView(loadingStatus)
    }

    private fun loadPreview() {
        showLoading()
        rowsButton.isEnabled = false
        val generation = loadGeneration.incrementAndGet()
        val requestedSeries = selectedSeriesIds.toSet()
        val maxGraphPoints = (
            resources.displayMetrics.widthPixels * 2
            ).coerceIn(MIN_GRAPH_POINTS, MAX_GRAPH_POINTS)

        thread(name = "utcomp-csv-viewer", isDaemon = true) {
            val startedMs = android.os.SystemClock.elapsedRealtime()
            val result = runCatching {
                openReader().use { reader ->
                    CsvLogPreviewReader.read(
                        reader = reader,
                        maxGraphPoints = maxGraphPoints,
                        selectedSeriesIds = requestedSeries,
                        isCancelled = {
                            cancelled.get() || generation != loadGeneration.get()
                        },
                        onProgress = { rows ->
                            updateLoadingProgress(rows, startedMs, generation)
                        },
                    )
                }
            }

            runOnUiThread {
                if (
                    cancelled.get() ||
                    generation != loadGeneration.get() ||
                    isFinishing ||
                    isDestroyed
                ) {
                    return@runOnUiThread
                }
                result
                    .onSuccess(::showPreview)
                    .onFailure { error ->
                        if (error !is CancellationException) showLoadError(error)
                    }
            }
        }
    }

    private fun openReader(): BufferedReader {
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        if (!filePath.isNullOrBlank()) {
            return File(filePath).bufferedReader(
                charset = Charsets.UTF_8,
                bufferSize = READER_BUFFER_SIZE,
            )
        }

        val sourceUri = intent.data
            ?: intent.getStringExtra(EXTRA_CONTENT_URI)?.let(Uri::parse)
            ?: error("No CSV source was supplied")
        val stream = contentResolver.openInputStream(sourceUri)
            ?: error("Could not open selected CSV")
        return BufferedReader(
            InputStreamReader(stream, Charsets.UTF_8),
            READER_BUFFER_SIZE,
        )
    }

    private fun updateLoadingProgress(
        rows: Long,
        startedMs: Long,
        generation: Int,
    ) {
        val elapsedMs = (
            android.os.SystemClock.elapsedRealtime() - startedMs
            ).coerceAtLeast(1L)
        val rowsPerSecond = rows * 1000L / elapsedMs
        runOnUiThread {
            if (
                !cancelled.get() &&
                generation == loadGeneration.get() &&
                ::loadingStatus.isInitialized
            ) {
                loadingStatus.text = String.format(
                    Locale.US,
                    "Reading %,d rows…  %,d rows/s",
                    rows,
                    rowsPerSecond,
                )
            }
        }
    }

    private fun showPreview(preview: CsvViewerPreview) {
        currentPreview = preview
        selectedSeriesIds.clear()
        selectedSeriesIds.addAll(preview.series.map { it.id })
        rowsButton.text = "ROWS ${preview.series.size}"
        rowsButton.isEnabled = preview.availableSeries.isNotEmpty()

        body.removeAllViews()
        body.gravity = Gravity.NO_GRAVITY

        val meta = TextView(this).apply {
            text = buildString {
                append(String.format(Locale.US, "%,d rows", preview.totalRows))
                append(" · graph=${preview.graphRows.size}")
                append(" · ${preview.durationText}")
                if (preview.sampleRateHz.isFinite()) {
                    append(" · ${preview.sampleRateHz.fixed(1)} Hz")
                }
                append('\n')
                append("${preview.firstTime.takeLast(12)} → ")
                append(preview.lastTime.takeLast(12))
                append(" · ")
                append(preview.sourceColumns)
            }
            textSize = 12f
            setTextColor(Color.rgb(174, 184, 200))
            setPadding(dp(4), 0, dp(4), dp(7))
        }
        body.addView(
            meta,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val summaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        summaryRow.addView(summaryCard("AFR", preview.stats.afr, 2, ""), weighted())
        summaryRow.addView(summaryCard("Boost", preview.stats.boost, 2, "bar"), weighted())
        summaryRow.addView(summaryCard("Oil P", preview.stats.oilPressure, 2, "bar"), weighted())
        summaryRow.addView(summaryCard("Oil T", preview.stats.oilTemp, 1, "°C"), weighted())
        body.addView(
            summaryRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) },
        )

        val graph = CsvLogGraphView(this).apply {
            setData(
                newRows = preview.graphRows.map { row ->
                    CsvLogGraphPoint(
                        time = row.time.takeLast(12),
                        values = row.values,
                    )
                },
                newSeries = preview.series.map { item ->
                    CsvLogGraphSeries(
                        id = item.id,
                        label = item.label,
                        unit = item.unit,
                        decimals = item.decimals,
                        color = item.color,
                    )
                },
            )
            setMarkers(intentMarkers())
        }
        val graphScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            setBackgroundColor(Color.rgb(5, 7, 11))
            addView(
                graph,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        body.addView(
            graphScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        AppDiagnostics.info(
            "CSV_VIEWER",
            "Loaded rows=${preview.totalRows} graph=${preview.graphRows.size} " +
                "series=${preview.series.joinToString { it.id }} " +
                "duration=${preview.durationText}",
        )
    }

    private fun showSeriesSelection(preview: CsvViewerPreview) {
        if (preview.availableSeries.isEmpty()) return

        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBackground(
                color = Color.rgb(15, 18, 24),
                radius = dp(18).toFloat(),
                strokeColor = Color.rgb(38, 78, 104),
                strokeWidth = dp(2),
            )
        }
        panel.addView(
            TextView(this).apply {
                text = "CSV data rows"
                textSize = 20f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            },
        )
        panel.addView(
            TextView(this).apply {
                text = "Choose up to $MAX_VISIBLE_SERIES rows. The viewer reloads the file using only the selected columns."
                textSize = 12.5f
                setTextColor(Color.rgb(170, 180, 194))
                setPadding(0, dp(3), 0, dp(10))
            },
        )

        val checks = linkedMapOf<String, CheckBox>()
        val selected = selectedSeriesIds.toMutableSet()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        preview.availableSeries.forEach { item ->
            val check = CheckBox(this).apply {
                text = buildString {
                    append(item.label)
                    if (item.unit.isNotBlank()) append("  [${item.unit}]")
                    append("\n${item.sourceColumn}")
                }
                textSize = 15f
                setTextColor(Color.WHITE)
                buttonTintList = ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf(),
                    ),
                    intArrayOf(
                        Color.rgb(82, 164, 255),
                        Color.rgb(112, 120, 134),
                    ),
                )
                isChecked = item.id in selected
                minHeight = dp(54)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = roundedBackground(
                    color = Color.BLACK,
                    radius = dp(10).toFloat(),
                    strokeColor = Color.rgb(38, 78, 104),
                    strokeWidth = dp(1),
                )
                setOnCheckedChangeListener { button, checked ->
                    if (checked && selected.size >= MAX_VISIBLE_SERIES) {
                        button.isChecked = false
                        Toast.makeText(
                            this@CsvLogViewerActivity,
                            "Maximum $MAX_VISIBLE_SERIES data rows",
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else if (checked) {
                        selected += item.id
                    } else {
                        selected -= item.id
                    }
                }
            }
            checks[item.id] = check
            list.addView(
                check,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(5) },
            )
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(
                list,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        actions.addView(
            dialogButton("DEFAULT") {
                val defaults = CsvViewerSeriesCatalog.defaultSeriesIds
                selected.clear()
                preview.availableSeries.forEach { item ->
                    val checked = item.id in defaults
                    checks[item.id]?.isChecked = checked
                    if (checked) selected += item.id
                }
            },
            weightedAction(),
        )
        actions.addView(
            dialogButton("CANCEL") { dialog.dismiss() },
            weightedAction(),
        )
        actions.addView(
            dialogButton("APPLY") {
                if (selected.isEmpty()) {
                    Toast.makeText(this, "Select at least one data row", Toast.LENGTH_SHORT).show()
                } else {
                    selectedSeriesIds.clear()
                    preview.availableSeries
                        .map { it.id }
                        .filterTo(selectedSeriesIds) { it in selected }
                    dialog.dismiss()
                    loadPreview()
                }
            },
            weightedAction(),
        )
        panel.addView(actions)

        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.72f }
        }
        dialog.show()

        val metrics = resources.displayMetrics
        dialog.window?.setLayout(
            (metrics.widthPixels * 0.92f).toInt(),
            (metrics.heightPixels * 0.90f).toInt(),
        )
    }

    private fun dialogButton(label: String, onClick: () -> Unit): Button =
        toolbarButton(label, onClick).apply {
            minWidth = 0
            textSize = 14f
        }

    private fun weightedAction(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(56), 1f).apply {
            val margin = dp(3)
            setMargins(margin, 0, margin, 0)
        }

    private fun showLoadError(error: Throwable) {
        body.removeAllViews()
        body.gravity = Gravity.CENTER
        rowsButton.isEnabled = currentPreview != null
        body.addView(
            TextView(this).apply {
                text = "Could not open CSV log\n\n${error.message ?: error.javaClass.simpleName}"
                textSize = 17f
                setTextColor(Color.rgb(255, 150, 150))
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(20), dp(20), dp(20))
            },
        )
        AppDiagnostics.error("CSV_VIEWER", "Load failed", error)
    }

    private fun intentMarkers(): List<CsvLogGraphMarker> {
        val series = intent.getStringArrayListExtra(EXTRA_MARKER_SERIES).orEmpty()
        val values = intent.getFloatArrayExtra(EXTRA_MARKER_VALUES) ?: floatArrayOf()
        val labels = intent.getStringArrayListExtra(EXTRA_MARKER_LABELS).orEmpty()
        val colors = intent.getIntArrayExtra(EXTRA_MARKER_COLORS) ?: intArrayOf()
        val count = minOf(series.size, values.size, labels.size, colors.size)
        return List(count) { index ->
            CsvLogGraphMarker(
                seriesLabel = series[index],
                value = values[index],
                label = labels[index],
                color = colors[index],
            )
        }
    }

    private fun summaryCard(
        label: String,
        range: CsvViewerRange,
        decimals: Int,
        suffix: String,
    ): TextView = TextView(this).apply {
        text = buildString {
            append(label)
            append("  ")
            append(range.min.fixed(decimals))
            append(" → ")
            append(range.max.fixed(decimals))
            if (suffix.isNotBlank()) append(" $suffix")
        }
        textSize = 12f
        setTextColor(Color.rgb(234, 236, 242))
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(5), dp(8), dp(5), dp(8))
        background = roundedBackground(
            color = Color.rgb(15, 18, 24),
            radius = dp(10).toFloat(),
            strokeColor = Color.rgb(38, 78, 104),
            strokeWidth = dp(1),
        )
    }

    private fun weighted(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f,
        ).apply {
            val margin = dp(3)
            setMargins(margin, 0, margin, 0)
        }

    private fun enableImmersiveMode() {
        val decorView = window.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            decorView.windowInsetsController?.let { controller ->
                controller.hide(
                    WindowInsets.Type.statusBars() or
                        WindowInsets.Type.navigationBars(),
                )
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun roundedBackground(
        color: Int,
        radius: Float,
        strokeColor: Int,
        strokeWidth: Int,
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        setStroke(strokeWidth, strokeColor)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
