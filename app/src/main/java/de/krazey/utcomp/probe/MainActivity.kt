package de.krazey.utcomp.probe

import android.widget.EditText
import android.text.InputType
import android.app.AlertDialog
import de.krazey.utcomp.probe.dashboard.DefaultDashboardPages
import de.krazey.utcomp.probe.dashboard.DashboardSensor
import de.krazey.utcomp.probe.dashboard.DashboardPageConfig
import de.krazey.utcomp.probe.dashboard.DashboardBoxConfig
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import de.krazey.utcomp.probe.protocol.TransmitterConstants
import de.krazey.utcomp.probe.protocol.TransmitterPacket
import de.krazey.utcomp.probe.simulation.SimulationEngine
import de.krazey.utcomp.probe.transport.UtcompUsbTransport
import de.krazey.utcomp.probe.utcomp.UtcompDataSnapshot
import de.krazey.utcomp.probe.utcomp.UtcompDecoder
import de.krazey.utcomp.probe.utcomp.UtcompDeviceConfig
import de.krazey.utcomp.probe.utcomp.pretty
import de.krazey.utcomp.probe.view.PerformanceGaugeView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.LinkedHashMap

class MainActivity : Activity() {
    private companion object {
        const val TAG = "UTCOMPProbe"
    }

    private enum class DataMode { USB, SIM }
    private enum class SimTestMode {
        OFF,
        NORMAL,
        WARNING,
        CRITICAL,
    }

    private enum class UiMode { FANCY, SIMPLE, DEBUG }
    private enum class Page(val config: DashboardPageConfig) {
        RACE_2X2(DefaultDashboardPages.race2x2),
        STRIP_1X4(DefaultDashboardPages.strip1x4),
        FULL_2X4(DefaultDashboardPages.full2x4),
        ;

        val rows: Int get() = config.rows
        val columns: Int get() = config.columns
        override fun toString(): String = config.title
    }
    private enum class MinMaxMode { ON_TAP, ALWAYS }

    private lateinit var usb: UtcompUsbTransport
    private lateinit var statusText: TextView
    private lateinit var controlsPanel: LinearLayout
    private lateinit var dashboardRoot: LinearLayout
    private lateinit var logTitleText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val handler = Handler(Looper.getMainLooper())
    private var dataMode = DataMode.USB
    private var simTestMode = SimTestMode.OFF
    private val simTickerHandler = Handler(Looper.getMainLooper())
    private val simTickerRunnable = object : Runnable {
        override fun run() {
            if (simTestMode == SimTestMode.OFF && dataMode != DataMode.SIM) {
                return
            }

            renderDashboard()
            simTickerHandler.postDelayed(this, 350L)
        }
    }

    private var simModeButton: Button? = null
    private var uiMode = UiMode.FANCY
    private var page = Page.RACE_2X2
    private var connected = false
    private var autoRefresh = false
    private var simTick = 0L
    private var controlsVisible = false
    private var editMode = false
    private var editModeButton: Button? = null
    private var dashboardPages = DefaultDashboardPages.all
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var showSourceLine = true
    private val minMaxBySensor = LinkedHashMap<String, MinMax>()
    private var minMaxDisplayMode = MinMaxMode.ON_TAP
    private var activeMinMaxCard: String? = null
    private var minMaxHideRunnable: Runnable? = null

    private data class MinMax(
        var min: Float = Float.NaN,
        var max: Float = Float.NaN,
    )

    private val simRunnable = object : Runnable {
        override fun run() {
            if (simTestMode != SimTestMode.OFF || dataMode == DataMode.SIM) {
                SimulationEngine.update(UtcompDecoder.snapshot, simTick++)
                renderDashboard()
                handler.postDelayed(this, 500)
            }
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (dataMode == DataMode.USB && autoRefresh) {
                requestLiveData(logEach = false)
                handler.postDelayed(this, 750)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = UtcompUsbTransport(this, ::appendLog)
        buildUi()
        usb.register()

        appendLog("UTCOMP dashboard started")
        appendLog("USB target: VID=${UtcompUsbTransport.VID}, PID=${UtcompUsbTransport.PID}")

        if (intent?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            appendLog("Started from USB attach intent")
            usb.requestPermissionAndConnect()
        }

        renderDashboard()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            appendLog("USB attach intent")
            usb.requestPermissionAndConnect()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveDriverMode()
    }

    private fun enableImmersiveDriverMode() {
        window.decorView.windowInsetsController?.let { controller ->
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        autoRefresh = false
        handler.removeCallbacksAndMessages(null)
        usb.unregister()
        usb.close()
        stopSimTicker()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            setBackgroundColor(Color.rgb(10, 12, 16))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(2, 0, 2, 4)
        }

        topBar.addView(TextView(this).apply {
            text = ""
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        topBar.addView(Button(this).apply {
            text = simModeButtonText()
            textSize = 12f
            setAllCaps(false)
            setTextColor(Color.WHITE)
            background = roundedBg(Color.rgb(16, 20, 30), 18f)
            setOnClickListener { cycleSimTestMode() }
            this@MainActivity.simModeButton = this
        }, LinearLayout.LayoutParams(76, 48).apply {
            setMargins(0, 0, 6, 0)
        })

        topBar.addView(Button(this).apply {
            text = "EDIT"
            textSize = 12f
            setAllCaps(false)
            setTextColor(Color.WHITE)
            background = roundedBg(Color.rgb(16, 20, 30), 18f)
            setOnClickListener { toggleEditMode() }
            this@MainActivity.editModeButton = this
        }, LinearLayout.LayoutParams(76, 48).apply {
            setMargins(0, 0, 6, 0)
        })

        topBar.addView(Button(this).apply {
            text = "⚙"
            textSize = 18f
            setAllCaps(false)
            setOnClickListener { toggleControls() }
            setOnLongClickListener { toggleEditMode(); true }
            background = roundedBg(Color.rgb(16, 20, 30), 18f)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(56, 48))

        root.addView(topBar, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(190, 198, 210))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 6)
            visibility = View.GONE
        }
        root.addView(statusText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("USB connect") {
            setDataMode(DataMode.USB)
            usb.requestPermissionAndConnect()
        })
        row1.addView(button(simModeButtonText()) { cycleSimTestMode() })
        row1.addView(button("Auto") { toggleAutoRefresh() })
        controls.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(button("Style") { cycleUiMode() })
        row2.addView(button("Page") { cyclePage() })
        row2.addView(button("REQ live") { requestLiveData(logEach = true) })
        controls.addView(row2)

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(button("REQ settings") { requestSettingsData() })
        row3.addView(button("Firmware") { requestUsb(TransmitterConstants.UtcompPid.FIRMWARE, true) })
        row3.addView(button("Clear log") { logText.text = "" })
        controls.addView(row3)

        val row4 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row4.addView(button("Reset min/max") { resetAllMinMax() })
        row4.addView(button("Min/max") { toggleMinMaxMode() })
        row4.addView(button("Toggle subtitles") {
            showSourceLine = !showSourceLine
            appendLog("Sensor source subtitles ${if (showSourceLine) "enabled" else "hidden"}")
            renderDashboard()
        })
        controls.addView(row4)

        controlsPanel = controls.apply {
            visibility = View.GONE
            background = roundedBg(Color.rgb(15, 18, 24), 18f)
            setPadding(8, 8, 8, 8)
        }
        root.addView(controlsPanel, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val dashScroll = ScrollView(this).apply {
            isFillViewport = true
            setOnTouchListener { _, event -> handlePageSwipe(event) }
            dashboardRoot = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 10, 0, 10)
            }
            addView(dashboardRoot, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(
            dashScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                5.5f,
            ),
        )

        logTitleText = TextView(this).apply {
            text = "Protocol/debug log"
            textSize = 12f
            setTextColor(Color.rgb(170, 178, 190))
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
        }
        root.addView(logTitleText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        logText = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.rgb(185, 190, 198))
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(4, 5, 7))
            visibility = View.GONE
            addView(logText, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(
            logScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        setContentView(root)
        window.decorView.post { enableImmersiveDriverMode() }
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 11f
        setAllCaps(false)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun currentPageConfig(): DashboardPageConfig =
        dashboardPages.getOrElse(page.ordinal) { page.config }

    private fun updateCurrentPage(transform: (DashboardPageConfig) -> DashboardPageConfig) {
        dashboardPages = dashboardPages.mapIndexed { index, pageConfig ->
            if (index == page.ordinal) transform(pageConfig) else pageConfig
        }
        renderDashboard()
    }

    private fun updateBoxConfig(boxIndex: Int, transform: (DashboardBoxConfig) -> DashboardBoxConfig) {
        updateCurrentPage { pageConfig ->
            val updatedBoxes = pageConfig.boxes.mapIndexed { index, box ->
                if (index == boxIndex) transform(box) else box
            }
            pageConfig.copy(boxes = updatedBoxes)
        }
    }

    private fun startSimTicker() {
        simTickerHandler.removeCallbacks(simTickerRunnable)
        if (simTestMode != SimTestMode.OFF || dataMode == DataMode.SIM) {
            simTickerHandler.post(simTickerRunnable)
        }
    }

    private fun stopSimTicker() {
        simTickerHandler.removeCallbacks(simTickerRunnable)
    }

    private fun cycleSimTestMode() {
        simTestMode = when (simTestMode) {
            SimTestMode.OFF -> SimTestMode.NORMAL
            SimTestMode.NORMAL -> SimTestMode.WARNING
            SimTestMode.WARNING -> SimTestMode.CRITICAL
            SimTestMode.CRITICAL -> SimTestMode.OFF
        }

        dataMode = if (simTestMode == SimTestMode.OFF) DataMode.USB else DataMode.SIM
        if (simTestMode == SimTestMode.OFF) {
            stopSimTicker()
        } else {
            startSimTicker()
        }
        if (simTestMode != SimTestMode.OFF) {
            simTick++
        }
        updateSimModeButton()
        appendLog("SIM mode: ${simModeDescription()}")
        renderDashboard()
    }

    private fun updateSimModeButton() {
        simModeButton?.apply {
            text = simModeButtonText()
            setTextColor(if (simTestMode == SimTestMode.OFF) Color.WHITE else Color.BLACK)
            background = roundedBg(
                when (simTestMode) {
                    SimTestMode.OFF -> Color.rgb(16, 20, 30)
                    SimTestMode.NORMAL -> Color.rgb(120, 210, 255)
                    SimTestMode.WARNING -> Color.rgb(255, 170, 48)
                    SimTestMode.CRITICAL -> Color.rgb(255, 72, 72)
                },
                18f,
            )
        }
    }

    private fun simModeButtonText(): String =
        when (simTestMode) {
            SimTestMode.OFF -> "SIM"
            SimTestMode.NORMAL -> "SIM"
            SimTestMode.WARNING -> "WARN"
            SimTestMode.CRITICAL -> "CRIT"
        }

    private fun simModeDescription(): String =
        when (simTestMode) {
            SimTestMode.OFF -> "off"
            SimTestMode.NORMAL -> "normal"
            SimTestMode.WARNING -> "warning test"
            SimTestMode.CRITICAL -> "critical test"
        }

    private fun updateEditModeButton() {
        editModeButton?.apply {
            text = if (editMode) "DONE" else "EDIT"
            setTextColor(if (editMode) Color.BLACK else Color.WHITE)
            background = roundedBg(
                if (editMode) Color.rgb(255, 170, 48) else Color.rgb(16, 20, 30),
                18f,
            )
        }
    }

    private fun toggleEditMode() {
        editMode = !editMode
        updateEditModeButton()
        appendLog("Edit mode ${if (editMode) "enabled" else "disabled"}")
        renderDashboard()
    }

    private fun toggleControls() {
        controlsVisible = !controlsVisible
        renderDashboard()
    }

    private fun setDataMode(mode: DataMode) {
        dataMode = mode
        if (mode == DataMode.SIM) {
            autoRefresh = false
            usb.close()
            connected = false
            appendLog("SIM mode enabled")
            handler.removeCallbacks(pollRunnable)
            handler.removeCallbacks(simRunnable)
            handler.post(simRunnable)
        } else {
            handler.removeCallbacks(simRunnable)
            appendLog("USB mode enabled")
        }
        renderDashboard()
    }

    private fun toggleAutoRefresh() {
        if (dataMode != DataMode.USB) {
            appendLog("Auto refresh is USB-only")
            return
        }
        autoRefresh = !autoRefresh
        appendLog("Auto refresh ${if (autoRefresh) "enabled" else "disabled"}")
        handler.removeCallbacks(pollRunnable)
        if (autoRefresh) handler.post(pollRunnable)
        renderDashboard()
    }

    private fun cycleUiMode() {
        uiMode = when (uiMode) {
            UiMode.FANCY -> UiMode.SIMPLE
            UiMode.SIMPLE -> UiMode.DEBUG
            UiMode.DEBUG -> UiMode.FANCY
        }
        renderDashboard()
    }

    private fun cyclePage() {
        nextPage()
    }

    private fun nextPage() {
        val pages = Page.values()
        page = pages[(page.ordinal + 1) % pages.size]
        renderDashboard()
    }

    private fun previousPage() {
        val pages = Page.values()
        page = pages[(page.ordinal + pages.size - 1) % pages.size]
        renderDashboard()
    }

    private fun handlePageSwipe(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = event.x
                swipeStartY = event.y
                return false
            }

            MotionEvent.ACTION_UP -> {
                val dx = event.x - swipeStartX
                val dy = event.y - swipeStartY
                val absDx = kotlin.math.abs(dx)
                val absDy = kotlin.math.abs(dy)

                if (absDx >= 90f && absDx > absDy * 1.6f) {
                    if (dx < 0f) {
                        nextPage()
                    } else {
                        previousPage()
                    }
                    return true
                }
            }
        }

        return false
    }

        private fun requestSettingsData() {
        listOf(
            TransmitterConstants.UtcompPid.TEMPERATURES_SETTINGS,
            TransmitterConstants.UtcompPid.GPIO_SETTINGS,
            TransmitterConstants.UtcompPid.ANALOG_OSC_SETTINGS1,
            TransmitterConstants.UtcompPid.ANALOG_OSC_SETTINGS2,
            TransmitterConstants.UtcompPid.GENERAL_SETTINGS1,
        ).forEach { requestUsb(it, true) }
    }

private fun requestUsb(pid: Int, logEach: Boolean) {
        if (dataMode != DataMode.USB) {
            appendLog("Ignoring USB request in SIM mode")
            return
        }
        val p = TransmitterPacket.request(pid)
        if (logEach) appendLog("Requesting pid=0x%04X over USB".format(pid))
        usb.write(p)
    }

    private fun requestLiveData(logEach: Boolean) {
        listOf(
            TransmitterConstants.UtcompPid.GENERAL_DATA1,
            TransmitterConstants.UtcompPid.GENERAL_DATA2,
            TransmitterConstants.UtcompPid.CONSUMPTION_DATA,
            TransmitterConstants.UtcompPid.TEMPERATURES_DATA,
            TransmitterConstants.UtcompPid.VSS_DATA,
            TransmitterConstants.UtcompPid.TRIP_DATA,
        ).forEach { requestUsb(it, logEach) }
    }

    private fun appendLog(line: String) {
        Log.i(TAG, line)
        runOnUiThread {
            if (!::logText.isInitialized || !::logScroll.isInitialized) return@runOnUiThread

            when {
                line.contains("USB connected") -> {
                    connected = true
                    renderDashboard()
                }
                line.contains("USB closed") || line.contains("USB detached") -> {
                    connected = false
                    renderDashboard()
                }
                line.startsWith("DECODE ") -> renderDashboard()
            }

            val stamp = timeFmt.format(Date())
            logText.append("$stamp  $line\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun simSnapshotForMode(): UtcompDataSnapshot {
        val base = baseSimSnapshot()

        return when (simTestMode) {
            SimTestMode.WARNING -> forceSimAlarmValues(base, warning = true)
            SimTestMode.CRITICAL -> forceSimAlarmValues(base, warning = false)
            SimTestMode.NORMAL,
            SimTestMode.OFF -> base
        }
    }

    private fun baseSimSnapshot(): UtcompDataSnapshot {
        val t = simTick.toFloat()
        val wave = kotlin.math.sin(t * 0.18f)

        return UtcompDataSnapshot().copy(
            bar1 = 0.35f + wave * 0.18f,
            afr1 = 14.70f + kotlin.math.sin(t * 0.11f) * 0.45f,
            temperatureNtc1 = 84.0f + kotlin.math.sin(t * 0.05f) * 4.0f,
            bar2 = 2.8f + kotlin.math.sin(t * 0.09f) * 0.25f,
            adcInValCh0 = 12.8f + kotlin.math.sin(t * 0.07f) * 0.15f,
            temperatureDsA = 23.0f + kotlin.math.sin(t * 0.04f) * 1.5f,
            temperatureDsB = 31.0f + kotlin.math.sin(t * 0.03f) * 1.0f,
        )
    }

    private fun forceSimAlarmValues(base: UtcompDataSnapshot, warning: Boolean): UtcompDataSnapshot {
        var out = base
        currentPageConfig().boxes.forEach { box ->
            val configured = if (warning) box.warningHigh else box.criticalHigh
            val fallback = if (warning) defaultWarningHighFor(box.sensor) else defaultCriticalHighFor(box.sensor)
            val threshold = if (!configured.isNaN()) configured else fallback

            if (!threshold.isNaN()) {
                out = withSimSensorValue(out, box.sensor, threshold + simAlarmMargin(box.sensor))
            }
        }
        return out
    }

    private fun simAlarmMargin(sensor: DashboardSensor): Float =
        when (sensor) {
            DashboardSensor.BOOST,
            DashboardSensor.OIL_PRESSURE,
            DashboardSensor.AFR,
            DashboardSensor.BATTERY -> 0.2f
            DashboardSensor.OIL_TEMP,
            DashboardSensor.OUTSIDE_TEMP,
            DashboardSensor.INSIDE_TEMP -> 2.0f
            DashboardSensor.TIME -> 0.0f
        }

    private fun withSimSensorValue(snapshot: UtcompDataSnapshot, sensor: DashboardSensor, value: Float): UtcompDataSnapshot =
        when (sensor) {
            DashboardSensor.BOOST -> snapshot.copy(bar1 = value)
            DashboardSensor.AFR -> snapshot.copy(afr1 = value)
            DashboardSensor.OIL_TEMP -> snapshot.copy(temperatureNtc1 = value)
            DashboardSensor.OIL_PRESSURE -> snapshot.copy(bar2 = value)
            DashboardSensor.BATTERY -> snapshot.copy(adcInValCh0 = value)
            DashboardSensor.OUTSIDE_TEMP -> snapshot.copy(temperatureDsA = value)
            DashboardSensor.INSIDE_TEMP -> snapshot.copy(temperatureDsB = value)
            DashboardSensor.TIME -> snapshot
        }

    private fun publishSimSnapshotIfNeeded() {
        if (simTestMode == SimTestMode.OFF && dataMode != DataMode.SIM) return

        simTick++
        val sim = simSnapshotForMode()
            // No nullable UtcompDataSnapshot backing field was detected.
    }

    private fun renderDashboard() {
        if (!::dashboardRoot.isInitialized || !::statusText.isInitialized) return

        if (::controlsPanel.isInitialized) {
            controlsPanel.visibility = if (controlsVisible) View.VISIBLE else View.GONE
        }

        updateEditModeButton()
        updateSimModeButton()

        val showDebugLog = controlsVisible || uiMode == UiMode.DEBUG
        if (::logTitleText.isInitialized) logTitleText.visibility = if (showDebugLog) View.VISIBLE else View.GONE
        if (::logScroll.isInitialized) logScroll.visibility = if (showDebugLog) View.VISIBLE else View.GONE
        if (::statusText.isInitialized) statusText.visibility = if (showDebugLog) View.VISIBLE else View.GONE

        val s = UtcompDecoder.snapshot
        val simRenderSnapshot = if (simTestMode != SimTestMode.OFF || dataMode == DataMode.SIM) {
            simTick++
            simSnapshotForMode()
        } else {
            null
        }
        val renderSnapshot = simRenderSnapshot ?: s

        statusText.text = buildString {
            append("$page")
            append("  •  $uiMode")
            append("  •  $dataMode")
            append("  •  USB ${if (connected) "OK" else "—"}")
            if (autoRefresh) append("  •  AUTO")
            if (editMode) append("  •  EDIT")
            if (s.firmware != "?") append("  •  fw ${s.firmware}")
        }

        dashboardRoot.removeAllViews()

        when (uiMode) {
            UiMode.FANCY -> renderFancy(renderSnapshot)
            UiMode.SIMPLE -> renderSimple(renderSnapshot)
            UiMode.DEBUG -> renderDebug(renderSnapshot)
        }
    }

    private fun renderConfiguredPage(
        pageConfig: DashboardPageConfig,
        snapshot: UtcompDataSnapshot,
        simple: Boolean,
    ) {
        val cards = pageConfig.boxes
            .mapIndexed { index, box -> index to box }
            .sortedWith(compareBy<Pair<Int, DashboardBoxConfig>> { it.second.row }.thenBy { it.second.column })
            .map { (boxIndex, box) ->
                val card = if (simple) {
                    simpleConfigCard(box, snapshot)
                } else {
                    fancyConfigCard(box, snapshot)
                }

                if (editMode) {
                    card.alpha = 0.92f
                    card.setOnClickListener { showBoxEditor(boxIndex) }
                    card.setOnLongClickListener {
                        showBoxEditor(boxIndex)
                        true
                    }
                }

                card
            }

        addPresetGrid(pageConfig.rows, pageConfig.columns, cards)
    }

    private fun fancyConfigCard(box: DashboardBoxConfig, snapshot: UtcompDataSnapshot): LinearLayout {
        val sensor = box.sensor
        val rawValue = sensor.readValue(snapshot)
        val valueForGauge = rawValue ?: 0f
        val suffix = if (sensor.unit.isBlank()) "" else sensor.unit.lowercase(Locale.US)
        val alarmLevel = alarmLevelFor(box, rawValue)
        val card = when (sensor) {
            DashboardSensor.TIME -> compactCard(sensor.label, SimpleDateFormat("HH:mm", Locale.US).format(Date()), sourceSubtitleFor(sensor))
            DashboardSensor.BATTERY,
            DashboardSensor.OUTSIDE_TEMP,
            DashboardSensor.INSIDE_TEMP -> compactCard(sensor.label, fmt(valueForGauge, " ${sensor.unit}"), sourceSubtitleFor(sensor))
            else -> fancyCard(
                sensor.label,
                valueForGauge,
                suffix,
                box.scaleMin,
                box.scaleMax,
                sourceSubtitleFor(sensor),
            )
        }

        card.background = roundedBg(boxBackgroundColor(box, alarmLevel), 18f)
        return card
    }

    private fun simpleConfigCard(box: DashboardBoxConfig, snapshot: UtcompDataSnapshot): LinearLayout {
        val sensor = box.sensor
        val rawValue = sensor.readValue(snapshot)
        val valueText = if (sensor == DashboardSensor.TIME) {
            SimpleDateFormat("HH:mm", Locale.US).format(Date())
        } else {
            fmt(rawValue ?: 0f, "")
        }

        val minMaxKey = if (sensor == DashboardSensor.TIME || rawValue == null || !box.showMinMax) null else sensor.label
        val alarmLevel = alarmLevelFor(box, rawValue)
        val effectiveValueColor = boxValueColor(box, alarmLevel)
        return simpleGridCard(
            fallbackIcon = fallbackIconFor(sensor),
            iconResourceName = sensor.iconResourceName,
            label = sensor.label,
            value = valueText,
            unit = if (box.showUnit) sensor.unit else "",
            minMaxKey = minMaxKey,
            rawValue = rawValue,
            minMaxSuffix = if (sensor.unit.isBlank()) "" else " ${sensor.unit}",
            valueScale = box.valueScale,
            iconScale = box.iconScale,
            backgroundColor = boxBackgroundColor(box, alarmLevel),
            foregroundColor = effectiveValueColor,
            unitColor = if (alarmLevel == BoxAlarmLevel.NORMAL) box.unitColor else effectiveValueColor,
            minColor = box.minColor,
            maxColor = box.maxColor,
            showIcon = box.showIcon,
        )
    }

    private data class NamedColor(
        val name: String,
        val color: Int,
    )

    private fun enabledText(enabled: Boolean): String =
        if (enabled) "enabled" else "disabled"

    private fun shownText(shown: Boolean): String =
        if (shown) "shown" else "hidden"

    private fun formatScale(scale: Float): String =
        "${(scale * 100f).toInt()}%"

    private fun showScalePicker(
        title: String,
        current: Float,
        onSelected: (Float) -> Unit,
    ) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            hint = "100"
            setText(trimFloat(current * 100f))
            selectAll()
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Enter percent. Examples: 80, 100, 125, 150.\nAllowed range: 25–400%.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val percent = input.text.toString()
                    .trim()
                    .replace(",", ".")
                    .toFloatOrNull()

                if (percent != null) {
                    onSelected((percent / 100f).coerceIn(0.25f, 4.0f))
                }
            }
            .setNeutralButton("Default") { _, _ -> onSelected(1.0f) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showHighThresholdEditor(
        title: String,
        boxIndex: Int,
        box: DashboardBoxConfig,
        current: Float,
        update: DashboardBoxConfig.(Float) -> DashboardBoxConfig,
    ) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine(true)
            hint = box.sensor.unit
            if (!current.isNaN()) {
                setText(trimFloat(current))
                selectAll()
            }
        }

        AlertDialog.Builder(this)
            .setTitle("$title for ${box.sensor.label}")
            .setMessage("Current: ${formatThreshold(current, box.sensor.unit)}")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val parsed = input.text.toString()
                    .trim()
                    .replace(",", ".")
                    .toFloatOrNull()

                if (parsed != null) {
                    updateBoxConfig(boxIndex) { it.update(parsed) }
                }
            }
            .setNeutralButton("Off") { _, _ ->
                updateBoxConfig(boxIndex) { it.update(Float.NaN) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showColorPicker(
        title: String,
        current: Int,
        palette: List<NamedColor>,
        onSelected: (Int) -> Unit,
    ) {
        val labels = palette.map { namedColor ->
            val marker = if (namedColor.color == current) " ✓" else ""
            "${namedColor.name}$marker"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels) { _, which -> onSelected(palette[which].color) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmApplyStyleToPage(boxIndex: Int) {
        val pageConfig = currentPageConfig()
        val box = pageConfig.boxes.getOrNull(boxIndex) ?: return

        AlertDialog.Builder(this)
            .setTitle("Apply style to page?")
            .setMessage("Apply ${box.sensor.label} colors, scales, visibility and alarm style to all boxes on ${pageConfig.title}?")
            .setPositiveButton("Apply") { _, _ ->
                updateCurrentPage { it.withBoxDefaultsAppliedToPage(box) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun trimFloat(value: Float): String =
        if (value % 1.0f == 0.0f) value.toInt().toString() else "%.1f".format(Locale.US, value)

    private fun colorName(color: Int): String =
        allNamedColors().firstOrNull { it.color == color }?.name
            ?: "#%06X".format(Locale.US, 0xFFFFFF and color)

    private fun valueColorPalette(): List<NamedColor> =
        listOf(
            NamedColor("White", Color.WHITE),
            NamedColor("Black", Color.BLACK),
            NamedColor("Ice blue", Color.rgb(120, 210, 255)),
            NamedColor("Warm yellow", Color.rgb(255, 220, 110)),
            NamedColor("Green", Color.rgb(80, 220, 120)),
            NamedColor("Soft red", Color.rgb(255, 120, 120)),
        )

    private fun backgroundColorPalette(): List<NamedColor> =
        listOf(
            NamedColor("Dark blue", Color.rgb(11, 14, 20)),
            NamedColor("Blue gray", Color.rgb(16, 20, 30)),
            NamedColor("Black", Color.rgb(0, 0, 0)),
            NamedColor("Dark purple", Color.rgb(18, 10, 22)),
            NamedColor("Dark teal", Color.rgb(8, 24, 28)),
            NamedColor("Dark amber", Color.rgb(28, 18, 8)),
        )

    private fun warningColorPalette(): List<NamedColor> =
        listOf(
            NamedColor("Orange", Color.rgb(255, 170, 48)),
            NamedColor("Yellow", Color.rgb(255, 220, 70)),
            NamedColor("Blue", Color.rgb(120, 210, 255)),
            NamedColor("Purple", Color.rgb(180, 110, 255)),
        )

    private fun alarmValueColorPalette(): List<NamedColor> =
        listOf(
            NamedColor("White", Color.WHITE),
            NamedColor("Black", Color.BLACK),
            NamedColor("Warm yellow", Color.rgb(255, 220, 110)),
            NamedColor("Ice blue", Color.rgb(120, 210, 255)),
            NamedColor("Green", Color.rgb(80, 220, 120)),
            NamedColor("Soft red", Color.rgb(255, 120, 120)),
        )

    private fun criticalColorPalette(): List<NamedColor> =
        listOf(
            NamedColor("Red", Color.rgb(255, 72, 72)),
            NamedColor("Pink", Color.rgb(255, 0, 120)),
            NamedColor("Orange red", Color.rgb(255, 120, 0)),
            NamedColor("Magenta", Color.rgb(220, 40, 255)),
        )

    private fun allNamedColors(): List<NamedColor> =
        valueColorPalette() +
            alarmValueColorPalette() +
            backgroundColorPalette() +
            warningColorPalette() +
            criticalColorPalette()

    private fun showBoxEditor(boxIndex: Int) {
        val pageConfig = currentPageConfig()
        val box = pageConfig.boxes.getOrNull(boxIndex) ?: return

        val actions = arrayOf(
            "Sensor: ${box.sensor.label}",
            "Value size: ${formatScale(box.valueScale)}",
            "Icon size: ${formatScale(box.iconScale)}",
            "Normal value color: ${colorName(box.valueColor)}",
            "Background color: ${colorName(box.backgroundColor)}",
            "Warning high: ${formatThreshold(box.warningHigh, box.sensor.unit)}",
            "Critical high: ${formatThreshold(box.criticalHigh, box.sensor.unit)}",
            "Warning background: ${colorName(box.warningColor)}",
            "Warning value color: ${colorName(box.warningValueColor)}",
            "Critical background: ${colorName(box.criticalColor)}",
            "Critical value color: ${colorName(box.criticalValueColor)}",
            "Alarm background: ${enabledText(box.alarmColorsBackground)}",
            "Alarm value color: ${enabledText(box.alarmColorsValue)}",
            "Icon: ${shownText(box.showIcon)}",
            "Unit: ${shownText(box.showUnit)}",
            "Min/max: ${shownText(box.showMinMax)}",
            "Apply this style to all boxes on page",
            "Set default alarms for ${box.sensor.label}",
            "Disable alarms for ${box.sensor.label}",
            "Reset this box style",
        )

        AlertDialog.Builder(this)
            .setTitle("Edit ${pageConfig.title}: ${box.sensor.label}")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showSensorPicker(boxIndex)
                    1 -> showScalePicker(
                        title = "Value size",
                        current = box.valueScale,
                    ) { selected -> updateBoxConfig(boxIndex) { it.copy(valueScale = selected) } }
                    2 -> showScalePicker(
                        title = "Icon size",
                        current = box.iconScale,
                    ) { selected -> updateBoxConfig(boxIndex) { it.copy(iconScale = selected) } }
                    3 -> showColorPicker(
                        title = "Normal value color",
                        current = box.valueColor,
                        palette = valueColorPalette(),
                    ) { selected -> updateBoxConfig(boxIndex) { it.copy(valueColor = selected, foregroundColor = selected) } }
                    4 -> showColorPicker(
                        title = "Background color",
                        current = box.backgroundColor,
                        palette = backgroundColorPalette(),
                    ) { selected -> updateBoxConfig(boxIndex) { it.copy(backgroundColor = selected) } }
                    5 -> showHighThresholdEditor(
                        title = "Warning high",
                        boxIndex = boxIndex,
                        box = box,
                        current = box.warningHigh,
                    ) { newValue -> copy(warningHigh = newValue) }
                    6 -> showHighThresholdEditor(
                        title = "Critical high",
                        boxIndex = boxIndex,
                        box = box,
                        current = box.criticalHigh,
                    ) { newValue -> copy(criticalHigh = newValue) }
                    7 -> showColorPicker(
                        title = "Warning background",
                        current = box.warningColor,
                        palette = warningColorPalette(),
                    ) { selected -> updateBoxConfig(boxIndex) { it.copy(warningColor = selected) } }
                    8 -> showColorPicker(
                        title = "Warning value color",
                        current = box.warningValueColor,
                        palette = alarmValueColorPalette(),
                    ) { selected -> updateBoxConfig(boxIndex) { it.copy(warningValueColor = selected) } }
                    9 -> showColorPicker(
                        title = "Critical background",
                        current = box.criticalColor,
                        palette = criticalColorPalette(),
                    ) { selected ->
                        updateBoxConfig(boxIndex) {
                            it.copy(
                                criticalColor = selected,
                                alarmColor = selected,
                                maxColor = selected,
                            )
                        }
                    }
                    10 -> showColorPicker(
                        title = "Critical value color",
                        current = box.criticalValueColor,
                        palette = alarmValueColorPalette(),
                    ) { selected -> updateBoxConfig(boxIndex) { it.copy(criticalValueColor = selected) } }
                    11 -> updateBoxConfig(boxIndex) { it.copy(alarmColorsBackground = !it.alarmColorsBackground) }
                    12 -> updateBoxConfig(boxIndex) { it.copy(alarmColorsValue = !it.alarmColorsValue) }
                    13 -> updateBoxConfig(boxIndex) { it.copy(showIcon = !it.showIcon) }
                    14 -> updateBoxConfig(boxIndex) { it.copy(showUnit = !it.showUnit) }
                    15 -> updateBoxConfig(boxIndex) { it.copy(showMinMax = !it.showMinMax) }
                    16 -> confirmApplyStyleToPage(boxIndex)
                    17 -> updateBoxConfig(boxIndex) { withDefaultAlarmThresholds(it) }
                    18 -> updateBoxConfig(boxIndex) { clearAlarmThresholds(it) }
                    19 -> updateBoxConfig(boxIndex) { resetBoxStyle(it) }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSensorPicker(boxIndex: Int) {
        val sensors = DashboardSensor.values()
        AlertDialog.Builder(this)
            .setTitle("Select sensor")
            .setItems(sensors.map { it.label }.toTypedArray()) { _, which ->
                val selected = sensors[which]
                updateBoxConfig(boxIndex) {
                    withDefaultAlarmThresholds(
                        it.copy(
                            sensor = selected,
                            scaleMin = selected.defaultMin,
                            scaleMax = selected.defaultMax,
                        ),
                        selected,
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetBoxStyle(box: DashboardBoxConfig): DashboardBoxConfig =
        withDefaultAlarmThresholds(
            box.copy(
                valueScale = 1.0f,
                iconScale = 1.0f,
                backgroundColor = Color.rgb(11, 14, 20),
                foregroundColor = Color.WHITE,
                valueColor = Color.WHITE,
                unitColor = Color.rgb(210, 216, 225),
                alarmColor = Color.rgb(255, 72, 72),
                minColor = Color.rgb(80, 170, 255),
                maxColor = Color.rgb(255, 72, 72),
                warningColor = Color.rgb(255, 170, 48),
                criticalColor = Color.rgb(255, 72, 72),
                warningValueColor = Color.BLACK,
                criticalValueColor = Color.WHITE,
                alarmColorsBackground = true,
                alarmColorsValue = true,
                showIcon = true,
                showUnit = true,
                showMinMax = true,
            ),
        )

    private fun formatThreshold(value: Float, unit: String): String =
        if (value.isNaN()) "off" else fmt(value, if (unit.isBlank()) "" else " $unit")

    private fun nextValueColor(current: Int): Int {
        val colors = intArrayOf(
            Color.WHITE,
            Color.rgb(120, 210, 255),
            Color.rgb(255, 220, 110),
            Color.rgb(80, 220, 120),
            Color.rgb(255, 120, 120),
        )
        val index = colors.indexOf(current)
        return colors[(index + 1).floorMod(colors.size)]
    }

    private fun nextWarningColor(current: Int): Int {
        val colors = intArrayOf(
            Color.rgb(255, 170, 48),
            Color.rgb(255, 220, 70),
            Color.rgb(120, 210, 255),
            Color.rgb(180, 110, 255),
        )
        val index = colors.indexOf(current)
        return colors[(index + 1).floorMod(colors.size)]
    }

    private fun nextCriticalColor(current: Int): Int {
        val colors = intArrayOf(
            Color.rgb(255, 72, 72),
            Color.rgb(255, 0, 120),
            Color.rgb(255, 120, 0),
            Color.rgb(220, 40, 255),
        )
        val index = colors.indexOf(current)
        return colors[(index + 1).floorMod(colors.size)]
    }

    private fun nextBackgroundColor(current: Int): Int {
        val colors = intArrayOf(
            Color.rgb(11, 14, 20),
            Color.rgb(16, 20, 30),
            Color.rgb(0, 0, 0),
            Color.rgb(18, 10, 22),
            Color.rgb(8, 24, 28),
            Color.rgb(28, 18, 8),
        )
        val index = colors.indexOf(current)
        return colors[(index + 1).floorMod(colors.size)]
    }

    private fun nextAlarmColor(current: Int): Int {
        val colors = intArrayOf(
            Color.rgb(255, 72, 72),
            Color.rgb(255, 170, 48),
            Color.rgb(120, 210, 255),
            Color.rgb(180, 110, 255),
            Color.rgb(80, 220, 120),
        )
        val index = colors.indexOf(current)
        return colors[(index + 1).floorMod(colors.size)]
    }

    private fun Int.floorMod(mod: Int): Int =
        ((this % mod) + mod) % mod

    private enum class BoxAlarmLevel {
        NORMAL,
        WARNING,
        CRITICAL,
    }

    private fun alarmLevelFor(box: DashboardBoxConfig, rawValue: Float?): BoxAlarmLevel {
        when (simTestMode) {
            SimTestMode.WARNING -> return BoxAlarmLevel.WARNING
            SimTestMode.CRITICAL -> return BoxAlarmLevel.CRITICAL
            SimTestMode.OFF,
            SimTestMode.NORMAL -> Unit
        }

        val value = rawValue ?: return BoxAlarmLevel.NORMAL
        if (value.isNaN() || value.isInfinite()) return BoxAlarmLevel.NORMAL

        if (thresholdHitLow(value, box.criticalLow) || thresholdHitHigh(value, box.criticalHigh)) {
            return BoxAlarmLevel.CRITICAL
        }
        if (thresholdHitLow(value, box.warningLow) || thresholdHitHigh(value, box.warningHigh)) {
            return BoxAlarmLevel.WARNING
        }
        return BoxAlarmLevel.NORMAL
    }

    private fun thresholdHitHigh(value: Float, threshold: Float): Boolean =
        !threshold.isNaN() && value >= threshold

    private fun thresholdHitLow(value: Float, threshold: Float): Boolean =
        !threshold.isNaN() && value <= threshold

    private fun boxBackgroundColor(box: DashboardBoxConfig, alarmLevel: BoxAlarmLevel): Int =
        if (!box.alarmColorsBackground) {
            box.backgroundColor
        } else {
            when (alarmLevel) {
                BoxAlarmLevel.CRITICAL -> box.criticalColor
                BoxAlarmLevel.WARNING -> box.warningColor
                BoxAlarmLevel.NORMAL -> box.backgroundColor
            }
        }

    private fun boxValueColor(box: DashboardBoxConfig, alarmLevel: BoxAlarmLevel): Int =
        if (!box.alarmColorsValue) {
            box.valueColor
        } else {
            when (alarmLevel) {
                BoxAlarmLevel.CRITICAL -> box.criticalValueColor
                BoxAlarmLevel.WARNING -> box.warningValueColor
                BoxAlarmLevel.NORMAL -> box.valueColor
            }
        }

    private fun defaultWarningHighFor(sensor: DashboardSensor): Float =
        when (sensor) {
            DashboardSensor.OIL_TEMP -> 120.0f
            else -> Float.NaN
        }

    private fun defaultCriticalHighFor(sensor: DashboardSensor): Float =
        when (sensor) {
            DashboardSensor.OIL_TEMP -> 130.0f
            else -> Float.NaN
        }

    private fun thresholdStepFor(sensor: DashboardSensor): Float =
        when (sensor) {
            DashboardSensor.BOOST,
            DashboardSensor.OIL_PRESSURE -> 0.1f
            DashboardSensor.AFR -> 0.1f
            DashboardSensor.OIL_TEMP,
            DashboardSensor.OUTSIDE_TEMP,
            DashboardSensor.INSIDE_TEMP -> 5.0f
            DashboardSensor.BATTERY -> 0.1f
            DashboardSensor.TIME -> 1.0f
        }

    private fun bumpHighThreshold(current: Float, sensor: DashboardSensor, direction: Int, defaultValue: Float): Float {
        val base = if (current.isNaN()) defaultValue else current
        val start = if (base.isNaN()) 0.0f else base
        return start + thresholdStepFor(sensor) * direction
    }

    private fun clearAlarmThresholds(box: DashboardBoxConfig): DashboardBoxConfig =
        box.copy(
            warningLow = Float.NaN,
            criticalLow = Float.NaN,
            warningHigh = Float.NaN,
            criticalHigh = Float.NaN,
        )

    private fun withDefaultAlarmThresholds(box: DashboardBoxConfig, sensor: DashboardSensor = box.sensor): DashboardBoxConfig =
        box.copy(
            warningHigh = defaultWarningHighFor(sensor),
            criticalHigh = defaultCriticalHighFor(sensor),
            warningLow = Float.NaN,
            criticalLow = Float.NaN,
        )

    private fun sourceSubtitleFor(sensor: DashboardSensor): String =
        UtcompDeviceConfig.subtitleFor(sensor)

    private fun fallbackIconFor(sensor: DashboardSensor): String =
        when (sensor) {
            DashboardSensor.BOOST -> "🌀"
            DashboardSensor.AFR -> "AFR"
            DashboardSensor.OIL_TEMP -> "🛢"
            DashboardSensor.OIL_PRESSURE -> "💧"
            DashboardSensor.BATTERY -> "🔋"
            DashboardSensor.OUTSIDE_TEMP -> "☁"
            DashboardSensor.INSIDE_TEMP -> "🏠"
            DashboardSensor.TIME -> "19:47"
        }

    private fun renderFancy(s: UtcompDataSnapshot) {
        renderConfiguredPage(currentPageConfig(), s, simple = false)
    }

    private fun renderSimple(s: UtcompDataSnapshot) {
        renderConfiguredPage(currentPageConfig(), s, simple = true)
    }

    private fun renderDebug(s: UtcompDataSnapshot) {
        val tv = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(220, 225, 235))
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = buildString {
                appendLine("Debug snapshot")
                appendLine("fw=${s.firmware} utcompPro=${s.utcompPro}")
                appendLine("bar1=${s.bar1} bar2=${s.bar2} bar3=${s.bar3}")
                appendLine("afr1=${s.afr1} afr2=${s.afr2}")
                appendLine("adc0=${s.adcInValCh0} adc1=${s.adcInValCh1} adc2=${s.adcInValCh2} adc3=${s.adcInValCh3} adc4=${s.adcInValCh4}")
                appendLine("ntc1=${s.temperatureNtc1} dsA=${s.temperatureDsA} dsB=${s.temperatureDsB}")
                appendLine("rpm=${s.rpm} speed=${s.vssSpeed1s}")
                appendLine("fuelPb=${s.fuelLeftPb} fuelLpg=${s.fuelLeftLpg}")
                appendLine("tripDist=${s.tripDist} tripCost=${s.tripCost}")
            }
        }
        dashboardRoot.addView(tv, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private data class RowValue(val icon: String, val label: String, val value: String)
    private fun rowValue(icon: String, label: String, value: String) = RowValue(icon, label, value)

    private fun addPresetGrid(rows: Int, columns: Int, cards: List<LinearLayout>) {
        val safeRows = rows.coerceAtLeast(1)
        val safeColumns = columns.coerceAtLeast(1)
        val totalCells = safeRows * safeColumns
        val cells = cards.take(totalCells)

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }

        for (rowIndex in 0 until safeRows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
            }

            for (columnIndex in 0 until safeColumns) {
                val index = rowIndex * safeColumns + columnIndex
                val cell = cells.getOrNull(index) ?: emptyGridCell()

                cell.minimumWidth = 0
                cell.minimumHeight = 0
                cell.setOnTouchListener { _, event -> handlePageSwipe(event) }

                row.addView(
                    cell,
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1f,
                    ).apply {
                        setMargins(6, 6, 6, 6)
                    },
                )
            }

            outer.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }

        dashboardRoot.addView(
            outer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun emptyGridCell(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

    private fun addFancyRow(left: LinearLayout, right: LinearLayout) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, 6, 8)
        })
        row.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(6, 0, 0, 8)
        })
        dashboardRoot.addView(row, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun fancyCard(
        title: String,
        value: Float,
        unit: String,
        min: Float,
        maxValue: Float,
        subtitle: String,
    ): LinearLayout {
        val stats = trackMinMax(title, value)
        val card = baseCard().apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { showMinMaxInline(title) }
            setOnLongClickListener {
                resetMinMax(title)
                true
            }
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(Color.rgb(190, 198, 210))
            typeface = Typeface.DEFAULT_BOLD
        })

        val valueSuffix = if (unit.isEmpty()) "" else " $unit"
        val valueRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        valueRow.addView(TextView(this).apply {
            text = fmt(value, valueSuffix)
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        valueRow.addView(TextView(this).apply {
            text = if (shouldShowMinMax(title)) {
                "min ${fmt(stats.min, valueSuffix)}\nmax ${fmt(stats.max, valueSuffix)}"
            } else {
                ""
            }
            textSize = 10f
            setTextColor(Color.rgb(120, 210, 255))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        card.addView(valueRow, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        card.addView(PerformanceGaugeView(this).apply {
            minValue = min
            this.maxValue = maxValue
            currentValue = value
            centerZero = title == "Boost"
            accentColor = when (title) {
                "Boost" -> Color.rgb(0, 210, 255)
                "AFR" -> Color.rgb(140, 110, 255)
                "Oil pressure" -> Color.rgb(255, 190, 60)
                "Oil temp" -> Color.rgb(255, 90, 60)
                else -> Color.rgb(0, 210, 255)
            }
        }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        if (showSourceLine && subtitle.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = subtitle
                textSize = 11f
                setTextColor(Color.rgb(130, 140, 155))
            })
        }

        return card
    }

    private fun compactCard(title: String, value: String, subtitle: String): LinearLayout {
        val card = baseCard()
        card.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(Color.rgb(190, 198, 210))
        })
        card.addView(TextView(this).apply {
            text = value
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(Color.rgb(130, 140, 155))
        })
        return card
    }

    private fun simpleGridCard(
        fallbackIcon: String,
        iconResourceName: String,
        label: String,
        value: String,
        unit: String,
        minMaxKey: String?,
        rawValue: Float?,
        minMaxSuffix: String,
        valueScale: Float = 1.0f,
        iconScale: Float = 1.0f,
        backgroundColor: Int = Color.rgb(11, 14, 20),
        foregroundColor: Int = Color.WHITE,
        unitColor: Int = Color.rgb(210, 216, 225),
        minColor: Int = Color.rgb(80, 170, 255),
        maxColor: Int = Color.rgb(255, 72, 72),
        showIcon: Boolean = true,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = label
            background = roundedBg(backgroundColor, 0f)
            setPadding(12, 4, 10, 4)

            val safeValueScale = valueScale.coerceIn(0.5f, 2.5f)
            val safeIconScale = iconScale.coerceIn(0.5f, 2.5f)
            val stats = if (minMaxKey != null && rawValue != null) {
                trackMinMax(minMaxKey, rawValue)
            } else {
                null
            }

            if (stats != null && minMaxKey != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { showMinMaxInline(minMaxKey) }
                setOnLongClickListener {
                    resetMinMax(minMaxKey)
                    true
                }
            }

            if (showIcon) {
                val iconSize = (44f * safeIconScale).toInt().coerceIn(26, 96)
                val iconResId = resources.getIdentifier(iconResourceName, "drawable", packageName)
                if (iconResId != 0) {
                    addView(ImageView(this@MainActivity).apply {
                        setImageResource(iconResId)
                        adjustViewBounds = true
                        alpha = 0.95f
                    }, LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        setMargins(0, 0, 10, 0)
                    })
                } else {
                    addView(TextView(this@MainActivity).apply {
                        text = fallbackIcon
                        textSize = if (fallbackIcon.length > 2) 14f * safeIconScale else 22f * safeIconScale
                        gravity = Gravity.CENTER
                        setTextColor(foregroundColor)
                        typeface = Typeface.DEFAULT_BOLD
                    }, LinearLayout.LayoutParams(iconSize, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 10, 0)
                    })
                }
            }

            val valueRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            valueRow.addView(TextView(this@MainActivity).apply {
                text = value
                textSize = 29f * safeValueScale
                setTextColor(foregroundColor)
                gravity = Gravity.CENTER_VERTICAL
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            if (unit.isNotBlank()) {
                valueRow.addView(TextView(this@MainActivity).apply {
                    text = " $unit"
                    textSize = 14f * safeValueScale
                    setTextColor(unitColor)
                    gravity = Gravity.CENTER_VERTICAL
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }

            addView(valueRow, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val showStats = stats != null && minMaxKey != null && shouldShowMinMax(minMaxKey)
            val statBox = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
            }

            statBox.addView(TextView(this@MainActivity).apply {
                text = if (showStats) fmt(stats!!.max, minMaxSuffix) else ""
                textSize = 11f
                setTextColor(maxColor)
                gravity = Gravity.END
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
            }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

            statBox.addView(TextView(this@MainActivity).apply {
                text = if (showStats) fmt(stats!!.min, minMaxSuffix) else ""
                textSize = 11f
                setTextColor(minColor)
                gravity = Gravity.END
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
            }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

            addView(statBox, LinearLayout.LayoutParams(84, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

    private fun addSimpleRow(icon: String, label: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 12, 18, 12)
            background = roundedBg(Color.rgb(22, 26, 34), 18f)
        }

        row.addView(TextView(this).apply {
            text = icon
            textSize = 22f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(52, LinearLayout.LayoutParams.WRAP_CONTENT))

        row.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.rgb(210, 216, 225))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(TextView(this).apply {
            text = value
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 0, 0, 8) }
        dashboardRoot.addView(row, lp)
    }

    private fun baseCard(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 12)
            background = roundedBg(Color.rgb(16, 20, 30), 22f)
        }

    private fun roundedBg(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            setStroke(2, Color.rgb(38, 78, 104))
        }



    private fun trackMinMax(key: String, value: Float): MinMax {
        val stats = minMaxBySensor.getOrPut(key) { MinMax() }
        if (value.isNaN() || value.isInfinite()) return stats

        if (stats.min.isNaN() || value < stats.min) stats.min = value
        if (stats.max.isNaN() || value > stats.max) stats.max = value
        return stats
    }

    private fun resetAllMinMax() {
        minMaxBySensor.clear()
        appendLog("Min/max values reset")
        renderDashboard()
    }

    private fun resetMinMax(key: String) {
        minMaxBySensor.remove(key)
        appendLog("Min/max reset for $key")
        renderDashboard()
    }

    private fun toggleMinMaxMode() {
        minMaxDisplayMode = when (minMaxDisplayMode) {
            MinMaxMode.ON_TAP -> MinMaxMode.ALWAYS
            MinMaxMode.ALWAYS -> MinMaxMode.ON_TAP
        }
        activeMinMaxCard = null
        minMaxHideRunnable?.let { handler.removeCallbacks(it) }
        minMaxHideRunnable = null
        appendLog("Min/max display mode: ${if (minMaxDisplayMode == MinMaxMode.ALWAYS) "always" else "tap for 3 seconds"}")
        renderDashboard()
    }

    private fun shouldShowMinMax(key: String): Boolean =
        minMaxDisplayMode == MinMaxMode.ALWAYS || activeMinMaxCard == key

    private fun showMinMaxInline(key: String) {
        activeMinMaxCard = key
        minMaxHideRunnable?.let { handler.removeCallbacks(it) }

        if (minMaxDisplayMode == MinMaxMode.ON_TAP) {
            val hide = Runnable {
                if (activeMinMaxCard == key) {
                    activeMinMaxCard = null
                    renderDashboard()
                }
            }
            minMaxHideRunnable = hide
            handler.postDelayed(hide, 3000)
        }

        renderDashboard()
    }

    private fun fmt(v: Float, suffix: String): String =
        if (v.isNaN() || v.isInfinite()) "—" else v.pretty() + suffix
}
