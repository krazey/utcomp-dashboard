package de.krazey.utcomp.dashboard.logging

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import de.krazey.utcomp.dashboard.utcomp.UtcompDataSnapshot
import de.krazey.utcomp.dashboard.view.DarkActionDialog
import de.krazey.utcomp.dashboard.view.DarkActionItem
import de.krazey.utcomp.dashboard.view.LiveSignalGraphView
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class LiveSignalInspectorController(
    private val activity: Activity,
    private val snapshotProvider: () -> UtcompDataSnapshot,
    private val appendLog: (String) -> Unit,
) {
    private companion object {
        const val PREFS_NAME = "utcomp_live_signal"
        const val PREF_SIGNAL_ID = "signalId"
        const val PREF_ALPHA = "smoothingAlpha"
        const val PREF_FILTER_MODE = "periodicFilterMode"
        const val PREF_FILTER_FREQUENCY = "periodicFilterFrequency"
        const val PREF_WINDOW_MS = "windowMs"
        const val MIN_SAMPLE_INTERVAL_MS = 45L
        val WINDOW_OPTIONS_MS = longArrayOf(10_000L, 30_000L, 60_000L, 120_000L)
        val BORDER_COLOR: Int = Color.rgb(38, 78, 104)
        val PANEL_COLOR: Int = Color.rgb(15, 18, 24)
        val RAW_COLOR: Int = Color.rgb(82, 164, 255)
        val SMOOTHED_COLOR: Int = Color.rgb(255, 190, 76)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val samplePosted = AtomicBoolean(false)
    private val sampleLock = Any()
    private val prefs = activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
    private val buffer = LiveSignalBuffer()

    @Volatile
    private var selectedSignal = LiveSignalCatalog.find(prefs.getString(PREF_SIGNAL_ID, null))

    @Volatile
    private var active = false

    private var pendingRaw = Float.NaN
    private var pendingTimeMs = 0L
    private var lastAcceptedAtMs = 0L
    private var windowMs = normalizeWindow(prefs.getLong(PREF_WINDOW_MS, 30_000L))
    private var dialog: Dialog? = null
    private var graphView: LiveSignalGraphView? = null
    private var titleText: TextView? = null
    private var descriptionText: TextView? = null
    private var rawValueText: TextView? = null
    private var smoothedValueText: TextView? = null
    private var statsText: TextView? = null
    private var smoothingText: TextView? = null
    private var signalButton: Button? = null
    private var windowButton: Button? = null
    private var filterButton: Button? = null
    private var smoothingSeekBar: SeekBar? = null

    init {
        buffer.setSmoothingAlpha(prefs.getFloat(PREF_ALPHA, 0.35f))
        val savedMode = prefs.getString(PREF_FILTER_MODE, null)
            ?.let { name ->
                PeriodicNoiseFilterMode.entries.firstOrNull { it.name == name }
            }
            ?: PeriodicNoiseFilterMode.OFF
        buffer.setPeriodicFilter(
            mode = savedMode,
            manualFrequencyHz = prefs.getFloat(PREF_FILTER_FREQUENCY, 0.38f),
        )
    }

    fun show() {
        dialog?.takeIf { it.isShowing }?.let {
            refreshLabels()
            return
        }

        buffer.clear()
        active = true
        val created = Dialog(activity, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        created.setContentView(buildContent(created))
        created.setCancelable(true)
        created.setOnDismissListener {
            active = false
            dialog = null
            graphView = null
            titleText = null
            descriptionText = null
            rawValueText = null
            smoothedValueText = null
            statsText = null
            smoothingText = null
            signalButton = null
            windowButton = null
            filterButton = null
            smoothingSeekBar = null
            samplePosted.set(false)
            mainHandler.removeCallbacks(drainPendingSample)
            AppDiagnostics.info("LIVE", "Live signal inspector closed")
        }
        created.show()
        created.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            setBackgroundDrawableResource(android.R.color.black)
        }
        hideSystemBars(created)
        dialog = created
        refreshLabels()
        AppDiagnostics.info(
            "LIVE",
            "Live signal inspector opened signal=${selectedSignal.id} " +
                "alpha=${buffer.smoothingAlpha} filter=${buffer.periodicMode} " +
                "windowMs=$windowMs",
        )
        offerSnapshot(snapshotProvider())
    }

    fun offerSnapshot(snapshot: UtcompDataSnapshot, sourcePid: Int? = null) {
        if (!active) return
        val definition = selectedSignal
        if (sourcePid != null && sourcePid != definition.sourcePid) return
        val raw = definition.read(snapshot)
        if (!raw.isFinite()) return

        val nowMs = SystemClock.elapsedRealtime()
        synchronized(sampleLock) {
            if (lastAcceptedAtMs > 0L && nowMs - lastAcceptedAtMs < MIN_SAMPLE_INTERVAL_MS) {
                pendingRaw = raw
                pendingTimeMs = nowMs
                return
            }
            lastAcceptedAtMs = nowMs
            pendingRaw = raw
            pendingTimeMs = nowMs
        }
        if (samplePosted.compareAndSet(false, true)) {
            mainHandler.post(drainPendingSample)
        }
    }

    fun close() {
        active = false
        mainHandler.removeCallbacks(drainPendingSample)
        samplePosted.set(false)
        dialog?.dismiss()
        dialog = null
    }

    private val drainPendingSample = Runnable {
        samplePosted.set(false)
        if (!active) return@Runnable
        val raw: Float
        val timeMs: Long
        synchronized(sampleLock) {
            raw = pendingRaw
            timeMs = pendingTimeMs
        }
        if (!raw.isFinite()) return@Runnable
        buffer.add(timeMs, raw)
        refreshValues()
    }

    private fun buildContent(owner: Dialog): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            setBackgroundColor(Color.rgb(5, 7, 11))
        }

        val toolbar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(activity).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }
        titleText = titleView
        toolbar.addView(
            titleView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(dp(4f), 0, dp(8f), 0)
            },
        )
        val selectSignalButton = toolbarButton("SIGNAL") { showSignalGroups() }
        signalButton = selectSignalButton
        toolbar.addView(selectSignalButton, toolbarButtonParams(128f))
        val selectWindowButton = toolbarButton("30 s") { cycleWindow() }
        windowButton = selectWindowButton
        toolbar.addView(selectWindowButton, toolbarButtonParams(92f))
        val selectFilterButton = toolbarButton("FILTER OFF") { showPeriodicFilterMenu() }
        filterButton = selectFilterButton
        toolbar.addView(selectFilterButton, toolbarButtonParams(112f))
        toolbar.addView(toolbarButton("RESET") { resetCapture() }, toolbarButtonParams(92f))
        toolbar.addView(
            toolbarButton("✕ EXIT") { owner.dismiss() },
            toolbarButtonParams(104f, trailingMargin = 0f),
        )
        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(58f),
            ),
        )

        val descriptionView = TextView(activity).apply {
            textSize = 12f
            setTextColor(Color.rgb(165, 176, 192))
            setPadding(dp(5f), dp(1f), dp(5f), dp(5f))
            maxLines = 2
        }
        descriptionText = descriptionView
        root.addView(
            descriptionView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val valueRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val rawPanel = valuePanel(
            title = "RAW",
            titleColor = RAW_COLOR,
        )
        rawValueText = rawPanel.second
        valueRow.addView(rawPanel.first, weightedPanelParams())
        val smoothedPanel = valuePanel(
            title = "OUTPUT",
            titleColor = SMOOTHED_COLOR,
        )
        smoothedValueText = smoothedPanel.second
        valueRow.addView(smoothedPanel.first, weightedPanelParams())
        val statsView = TextView(activity).apply {
            textSize = 12.5f
            setTextColor(Color.rgb(208, 215, 226))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
            background = panelBackground()
        }
        statsText = statsView
        valueRow.addView(statsView, weightedPanelParams(1.45f))
        root.addView(
            valueRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(100f),
            ).apply { setMargins(0, dp(4f), 0, dp(6f)) },
        )

        val graph = LiveSignalGraphView(activity).apply {
            buffer = this@LiveSignalInspectorController.buffer
            windowMs = this@LiveSignalInspectorController.windowMs
        }
        graphView = graph
        root.addView(
            graph,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        val smoothingPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
            background = panelBackground()
        }
        val smoothingLabel = TextView(activity).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        smoothingText = smoothingLabel
        smoothingPanel.addView(
            smoothingLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        val smoothingSlider = SeekBar(activity).apply {
            max = 99
            progress = alphaToProgress(buffer.smoothingAlpha)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) setSmoothingAlpha(progressToAlpha(progress), persist = false)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    persistSmoothingAlpha()
                }
            })
        }
        smoothingSeekBar = smoothingSlider
        smoothingPanel.addView(
            smoothingSlider,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34f),
            ),
        )

        val presets = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        listOf(
            "OFF" to 1.00f,
            "LIGHT" to 0.60f,
            "UI 0.35" to 0.35f,
            "STRONG" to 0.15f,
        ).forEachIndexed { index, (label, alpha) ->
            presets.addView(
                smallActionButton(label) { setSmoothingAlpha(alpha, persist = true) },
                LinearLayout.LayoutParams(0, dp(42f), 1f).apply {
                    val margin = dp(3f)
                    setMargins(
                        if (index == 0) 0 else margin,
                        0,
                        if (index == 3) 0 else margin,
                        0,
                    )
                },
            )
        }
        smoothingPanel.addView(
            presets,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            smoothingPanel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, dp(7f), 0, 0) },
        )

        return root
    }

    private fun valuePanel(title: String, titleColor: Int): Pair<View, TextView> {
        val value = TextView(activity).apply {
            text = "—"
            textSize = 25f
            setTextColor(titleColor)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
        }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = panelBackground()
            addView(
                TextView(activity).apply {
                    text = title
                    textSize = 11f
                    setTextColor(titleColor)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                value,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }
        return panel to value
    }

    private fun showSignalGroups() {
        DarkActionDialog.show(
            activity = activity,
            title = "Live signal",
            subtitle = "Select one decoded UTCOMP value to inspect.",
            items = LiveSignalGroup.entries.map { group ->
                DarkActionItem(
                    title = group.label,
                    description = "${LiveSignalCatalog.byGroup(group).size} available values",
                    onClick = { showSignals(group) },
                )
            },
        )
    }

    private fun showSignals(group: LiveSignalGroup) {
        DarkActionDialog.show(
            activity = activity,
            title = group.label,
            subtitle =
                "Blue is raw. Orange is periodic-filter output followed by the selected alpha.",
            items = LiveSignalCatalog.byGroup(group).map { definition ->
                DarkActionItem(
                    title = definition.label,
                    description = definition.description,
                    accentColor = if (definition.id == selectedSignal.id) {
                        SMOOTHED_COLOR
                    } else {
                        Color.WHITE
                    },
                    onClick = { selectSignal(definition) },
                )
            },
            closeLabel = "Back",
        )
    }

    private fun selectSignal(definition: LiveSignalDefinition) {
        selectedSignal = definition
        prefs.edit().putString(PREF_SIGNAL_ID, definition.id).apply()
        synchronized(sampleLock) {
            lastAcceptedAtMs = 0L
            pendingRaw = Float.NaN
            pendingTimeMs = 0L
        }
        buffer.clear()
        refreshLabels()
        AppDiagnostics.info("LIVE", "Live signal changed to ${definition.id}")
        appendLog("Live signal inspector: ${definition.label}")
        offerSnapshot(snapshotProvider())
    }

    private fun showPeriodicFilterMenu() {
        val estimate = buffer.periodicEstimate
        val autoDescription = buildString {
            append("Detect and reject a stable 0.25-0.55 Hz component. ")
            append("Useful for the measured common analog wave, but it may also reduce ")
            append("a genuine closed-loop AFR oscillation. Inspector only.")
            if (estimate.mode == PeriodicNoiseFilterMode.AUTO &&
                estimate.frequencyHz.isFinite()
            ) {
                append(" Current estimate ")
                append(formatFrequency(estimate.frequencyHz))
                append(" at ")
                append(formatPercent(estimate.confidence))
                append(" confidence.")
            }
        }
        DarkActionDialog.show(
            activity = activity,
            title = "Periodic noise filter",
            subtitle =
                "The filter runs before exponential smoothing and never changes dashboard cards.",
            items = listOf(
                DarkActionItem(
                    title = "Off",
                    description = "Show only ordinary exponential smoothing.",
                    accentColor = if (buffer.periodicMode == PeriodicNoiseFilterMode.OFF) {
                        SMOOTHED_COLOR
                    } else {
                        Color.WHITE
                    },
                    onClick = {
                        setPeriodicFilter(PeriodicNoiseFilterMode.OFF)
                    },
                ),
                DarkActionItem(
                    title = "Automatic periodic rejection",
                    description = autoDescription,
                    accentColor = if (buffer.periodicMode == PeriodicNoiseFilterMode.AUTO) {
                        SMOOTHED_COLOR
                    } else {
                        Color.WHITE
                    },
                    onClick = {
                        setPeriodicFilter(PeriodicNoiseFilterMode.AUTO)
                    },
                ),
                DarkActionItem(
                    title = "Manual frequency",
                    description =
                        "Apply a fixed notch. Use 0.38 Hz for the supplied AFR/battery captures.",
                    accentColor = if (buffer.periodicMode == PeriodicNoiseFilterMode.MANUAL) {
                        SMOOTHED_COLOR
                    } else {
                        Color.WHITE
                    },
                    onClick = ::showManualFrequencyMenu,
                ),
            ),
        )
    }

    private fun showManualFrequencyMenu() {
        val options = floatArrayOf(0.30f, 0.35f, 0.38f, 0.40f, 0.45f, 0.50f)
        DarkActionDialog.show(
            activity = activity,
            title = "Manual periodic frequency",
            subtitle =
                "The observed common AFR/battery wave is approximately 0.36-0.40 Hz.",
            items = options.map { frequency ->
                DarkActionItem(
                    title = formatFrequency(frequency),
                    description = "Reject a narrow band around ${formatFrequency(frequency)}.",
                    accentColor = if (
                        buffer.periodicMode == PeriodicNoiseFilterMode.MANUAL &&
                        kotlin.math.abs(buffer.manualFrequencyHz - frequency) < 0.005f
                    ) {
                        SMOOTHED_COLOR
                    } else {
                        Color.WHITE
                    },
                    onClick = {
                        setPeriodicFilter(
                            mode = PeriodicNoiseFilterMode.MANUAL,
                            manualFrequencyHz = frequency,
                        )
                    },
                )
            },
            closeLabel = "Back",
        )
    }

    private fun setPeriodicFilter(
        mode: PeriodicNoiseFilterMode,
        manualFrequencyHz: Float = buffer.manualFrequencyHz,
    ) {
        buffer.setPeriodicFilter(mode, manualFrequencyHz)
        prefs.edit()
            .putString(PREF_FILTER_MODE, mode.name)
            .putFloat(PREF_FILTER_FREQUENCY, buffer.manualFrequencyHz)
            .apply()
        refreshLabels()
        AppDiagnostics.info(
            "LIVE",
            "Periodic filter mode=$mode frequency=${buffer.manualFrequencyHz} " +
                "signal=${selectedSignal.id}",
        )
        appendLog(
            when (mode) {
                PeriodicNoiseFilterMode.OFF -> "Live periodic filter disabled"
                PeriodicNoiseFilterMode.AUTO -> "Live periodic filter set to automatic"
                PeriodicNoiseFilterMode.MANUAL ->
                    "Live periodic filter set to ${formatFrequency(buffer.manualFrequencyHz)}"
            },
        )
    }

    private fun setSmoothingAlpha(alpha: Float, persist: Boolean) {
        val safeAlpha = alpha.coerceIn(0.01f, 1f)
        buffer.setSmoothingAlpha(safeAlpha)
        smoothingSeekBar?.progress = alphaToProgress(safeAlpha)
        refreshValues()
        if (persist) persistSmoothingAlpha()
    }

    private fun persistSmoothingAlpha() {
        val alpha = buffer.smoothingAlpha
        prefs.edit().putFloat(PREF_ALPHA, alpha).apply()
        AppDiagnostics.info("LIVE", "Live smoothing alpha=$alpha signal=${selectedSignal.id}")
    }

    private fun cycleWindow() {
        val currentIndex = WINDOW_OPTIONS_MS.indexOf(windowMs).takeIf { it >= 0 } ?: 1
        windowMs = WINDOW_OPTIONS_MS[(currentIndex + 1) % WINDOW_OPTIONS_MS.size]
        prefs.edit().putLong(PREF_WINDOW_MS, windowMs).apply()
        graphView?.windowMs = windowMs
        refreshLabels()
        refreshValues()
    }

    private fun resetCapture() {
        buffer.clear()
        synchronized(sampleLock) {
            lastAcceptedAtMs = 0L
            pendingRaw = Float.NaN
            pendingTimeMs = 0L
        }
        refreshValues()
        AppDiagnostics.info("LIVE", "Live capture reset signal=${selectedSignal.id}")
        offerSnapshot(snapshotProvider())
    }

    private fun refreshLabels() {
        val definition = selectedSignal
        titleText?.text = "Live signal • ${definition.label}"
        descriptionText?.text =
            "${definition.description}  Raw and output traces share the same scale."
        signalButton?.text = "SIGNAL"
        windowButton?.text = "${windowMs / 1_000} s"
        filterButton?.text = filterButtonText()
        graphView?.apply {
            unit = definition.unit
            decimals = definition.decimals
            windowMs = this@LiveSignalInspectorController.windowMs
        }
        refreshSmoothingLabel()
        refreshValues()
    }

    private fun refreshValues() {
        val definition = selectedSignal
        val stats = buffer.stats(windowMs)
        rawValueText?.text = format(stats.rawCurrent, definition)
        smoothedValueText?.text = format(stats.smoothedCurrent, definition)
        statsText?.text = if (stats.count == 0) {
            "Waiting for data…"
        } else {
            buildString {
                append("MIN  ${formatCompact(stats.rawMin, definition)}")
                append("   MAX  ${formatCompact(stats.rawMax, definition)}\n")
                append("RAW P-P  ${formatCompact(stats.peakToPeak, definition)}")
                append("   σ  ${formatCompact(stats.rawStdDev, definition)}\n")
                append("OUT P-P  ${formatCompact(stats.outputPeakToPeak, definition)}")
                append("   σ  ${formatCompact(stats.outputStdDev, definition)}\n")
                append("${formatRate(stats.sampleRateHz)}")
                append("   N=${stats.count}")
            }
        }
        filterButton?.text = filterButtonText()
        refreshSmoothingLabel()
        graphView?.invalidate()
    }

    private fun refreshSmoothingLabel() {
        val alpha = buffer.smoothingAlpha
        val label = when {
            alpha >= 0.999f -> "off"
            alpha >= 0.55f -> "light"
            alpha >= 0.25f -> "medium"
            else -> "strong"
        }
        val periodic = periodicStatusText()
        smoothingText?.text =
            "Output: $periodic → EMA α=${formatAlpha(alpha)} ($label)  •  " +
                "inspector only"
    }

    private fun filterButtonText(): String {
        val estimate = buffer.periodicEstimate
        return when (buffer.periodicMode) {
            PeriodicNoiseFilterMode.OFF -> "FILTER OFF"
            PeriodicNoiseFilterMode.AUTO -> {
                if (estimate.active && estimate.frequencyHz.isFinite()) {
                    "AUTO ${String.format(Locale.US, "%.2f", estimate.frequencyHz)}"
                } else {
                    "AUTO WAIT"
                }
            }
            PeriodicNoiseFilterMode.MANUAL -> {
                val frequency = estimate.frequencyHz.takeIf { it.isFinite() }
                    ?: buffer.manualFrequencyHz
                String.format(Locale.US, "%.2f Hz", frequency)
            }
        }
    }

    private fun periodicStatusText(): String {
        val estimate = buffer.periodicEstimate
        return when (buffer.periodicMode) {
            PeriodicNoiseFilterMode.OFF -> "periodic filter off"
            PeriodicNoiseFilterMode.MANUAL -> {
                val frequency = estimate.frequencyHz.takeIf { it.isFinite() }
                    ?: buffer.manualFrequencyHz
                "manual notch ${formatFrequency(frequency)}"
            }
            PeriodicNoiseFilterMode.AUTO -> {
                if (estimate.active && estimate.frequencyHz.isFinite()) {
                    "auto notch ${formatFrequency(estimate.frequencyHz)} " +
                        "(${formatPercent(estimate.confidence)} confidence)"
                } else {
                    "auto notch learning/bypassed"
                }
            }
        }
    }

    private fun formatFrequency(value: Float): String =
        if (value.isFinite()) String.format(Locale.US, "%.2f Hz", value) else "— Hz"

    private fun formatPercent(value: Float): String =
        if (value.isFinite()) {
            String.format(Locale.US, "%.0f%%", value.coerceIn(0f, 1f) * 100f)
        } else {
            "—"
        }

    private fun format(value: Float, definition: LiveSignalDefinition): String {
        if (!value.isFinite()) return "—"
        val number = String.format(Locale.US, "%.${definition.decimals.coerceIn(0, 3)}f", value)
        return if (definition.unit.isBlank()) number else "$number ${definition.unit}"
    }

    private fun formatCompact(value: Float, definition: LiveSignalDefinition): String {
        if (!value.isFinite()) return "—"
        return String.format(Locale.US, "%.${definition.decimals.coerceIn(0, 3)}f", value)
    }

    private fun formatRate(value: Float): String =
        if (value.isFinite()) String.format(Locale.US, "%.1f Hz", value) else "— Hz"

    private fun formatAlpha(value: Float): String = String.format(Locale.US, "%.2f", value)

    private fun toolbarButton(label: String, onClick: () -> Unit): Button =
        Button(activity).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = buttonBackground()
            setOnClickListener { onClick() }
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        }

    private fun smallActionButton(label: String, onClick: () -> Unit): Button =
        Button(activity).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = buttonBackground()
            setOnClickListener { onClick() }
            setPadding(dp(5f), 0, dp(5f), 0)
        }

    private fun toolbarButtonParams(widthDp: Float, trailingMargin: Float = 5f) =
        LinearLayout.LayoutParams(dp(widthDp), dp(50f)).apply {
            setMargins(0, 0, dp(trailingMargin), 0)
        }

    private fun weightedPanelParams(weight: Float = 1f) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
            val margin = dp(3f)
            setMargins(margin, 0, margin, 0)
        }

    private fun panelBackground() = GradientDrawable().apply {
        setColor(PANEL_COLOR)
        cornerRadius = dp(13f).toFloat()
        setStroke(dp(1.5f), BORDER_COLOR)
    }

    private fun buttonBackground() = GradientDrawable().apply {
        setColor(Color.BLACK)
        cornerRadius = dp(13f).toFloat()
        setStroke(dp(2f), BORDER_COLOR)
    }

    private fun hideSystemBars(owner: Dialog) {
        val decor = owner.window?.decorView ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            decor.windowInsetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private fun alphaToProgress(alpha: Float): Int =
        ((alpha.coerceIn(0.01f, 1f) - 0.01f) / 0.99f * 99f).toInt().coerceIn(0, 99)

    private fun progressToAlpha(progress: Int): Float =
        0.01f + progress.coerceIn(0, 99) / 99f * 0.99f

    private fun normalizeWindow(value: Long): Long =
        WINDOW_OPTIONS_MS.minByOrNull { kotlin.math.abs(it - value) } ?: 30_000L

    private fun dp(value: Float): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
