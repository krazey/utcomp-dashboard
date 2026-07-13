package de.krazey.utcomp.dashboard.logging

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import de.krazey.utcomp.dashboard.util.fixed
import de.krazey.utcomp.dashboard.view.CsvLogGraphMarker
import de.krazey.utcomp.dashboard.view.CsvLogGraphPoint
import de.krazey.utcomp.dashboard.view.CsvLogGraphView
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
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
        private const val READER_BUFFER_SIZE = 64 * 1024
        private const val MIN_GRAPH_POINTS = 1_600
        private const val MAX_GRAPH_POINTS = 4_000

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
    private lateinit var body: LinearLayout
    private lateinit var loadingStatus: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        buildUi()
        loadPreview()
        AppDiagnostics.info("CSV_VIEWER", "Opened full-screen CSV viewer")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    override fun onDestroy() {
        cancelled.set(true)
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
            ).apply { marginEnd = dp(10) },
        )

        toolbar.addView(
            Button(this).apply {
                text = "✕ EXIT"
                textSize = 15f
                isAllCaps = false
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                minHeight = dp(56)
                minWidth = dp(112)
                background = roundedBackground(
                    color = Color.BLACK,
                    radius = dp(13).toFloat(),
                    strokeColor = Color.rgb(38, 78, 104),
                    strokeWidth = dp(2),
                )
                setOnClickListener { finish() }
            },
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

    private fun loadPreview() {
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
                        isCancelled = cancelled::get,
                        onProgress = { rows -> updateLoadingProgress(rows, startedMs) },
                    )
                }
            }

            runOnUiThread {
                if (cancelled.get() || isFinishing || isDestroyed) return@runOnUiThread
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

    private fun updateLoadingProgress(rows: Long, startedMs: Long) {
        val elapsedMs = (
            android.os.SystemClock.elapsedRealtime() - startedMs
            ).coerceAtLeast(1L)
        val rowsPerSecond = rows * 1000L / elapsedMs
        runOnUiThread {
            if (!cancelled.get() && ::loadingStatus.isInitialized) {
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
            setRows(preview.graphRows.map { row ->
                CsvLogGraphPoint(
                    time = row.time.takeLast(12),
                    afr = row.afr,
                    boost = row.boost,
                    oilPressure = row.oilPressure,
                    oilTemp = row.oilTemp,
                )
            })
            setMarkers(intentMarkers())
        }
        body.addView(
            graph,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        AppDiagnostics.info(
            "CSV_VIEWER",
            "Loaded rows=${preview.totalRows} graph=${preview.graphRows.size} " +
                "duration=${preview.durationText}",
        )
    }

    private fun showLoadError(error: Throwable) {
        body.removeAllViews()
        body.gravity = Gravity.CENTER
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
