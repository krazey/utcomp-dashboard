package de.krazey.utcomp.probe

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import org.json.JSONArray
import android.content.SharedPreferences
import android.widget.FrameLayout
import android.text.style.RelativeSizeSpan
import android.text.Spanned
import android.text.SpannableString
import android.widget.EditText
import android.text.InputType
import android.app.AlertDialog
import de.krazey.utcomp.probe.dashboard.DefaultDashboardPages
import de.krazey.utcomp.probe.dashboard.DashboardSensor
import de.krazey.utcomp.probe.dashboard.DashboardPageConfig
import de.krazey.utcomp.probe.dashboard.DashboardBoxConfig
import de.krazey.utcomp.probe.logging.UtcompCsvLogger
import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import de.krazey.utcomp.probe.view.RalliartBoostNeedleView
import de.krazey.utcomp.probe.view.RalliartAfrDebugBarView
import de.krazey.utcomp.probe.view.CsvLogGraphMarker
import de.krazey.utcomp.probe.view.CsvLogGraphPoint
import de.krazey.utcomp.probe.view.CsvLogGraphView
import de.krazey.utcomp.probe.util.fixed
import java.io.BufferedReader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private class ClickableLinearLayout(context: Context) : LinearLayout(context) {
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private companion object {
        private const val USB_FAST_POLL_MS = 50L
        private const val USB_SLOW_POLL_MS = 1000L
        private const val USB_RECONNECT_MS = 2500L
        private const val DASHBOARD_RENDER_MS = 125L
        private const val DASHBOARD_TOUCH_RENDER_GRACE_MS = 220L
        private const val DASHBOARD_TOUCH_DEFER_RETRY_MS = 80L
        private const val DATA_LOG_TREE_REQUEST = 42_100
        private const val DATA_LOG_VIEW_FILE_REQUEST = 42_101
        private const val CSV_VIEW_GRAPH_MAX_POINTS = 1600
        private const val DASHBOARD_DOUBLE_TAP_MS = 320L
        private const val DASHBOARD_SWIPE_CLICK_SUPPRESS_MS = 280L

        private const val RALLIART_CANVAS_WIDTH = 1024
        private const val RALLIART_CANVAS_HEIGHT = 600
        private val RALLIART_HEADER_STATUS_BOX = intArrayOf(670, 18, 332, 24)
        private val RALLIART_BOOST_HIT_BOX = intArrayOf(32, 70, 472, 472)
        private val RALLIART_BOOST_VALUE_BOX = intArrayOf(120, 266, 310, 92)
        private val RALLIART_BOOST_NEEDLE_BOX = intArrayOf(49, 87, 452, 452)
        private val RALLIART_BOOST_MIN_MAX_BOX = intArrayOf(334, 392, 68, 58)
        private val RALLIART_AFR_HIT_BOX = intArrayOf(538, 76, 494, 250)
        private val RALLIART_AFR_VALUE_BOX = intArrayOf(652, 138, 236, 82)
        private val RALLIART_AFR_DEBUG_GUIDE_BOX = intArrayOf(571, 240, 395, 52)
        private val RALLIART_AFR_LIVE_BAR_BOX = intArrayOf(571, 254, 395, 18)
        private val RALLIART_OIL_PRESSURE_HIT_BOX = intArrayOf(548, 325, 214, 197)
        private val RALLIART_OIL_PRESSURE_VALUE_BOX = intArrayOf(580, 392, 148, 74)
        private val RALLIART_OIL_PRESSURE_MIN_MAX_BOX = intArrayOf(700, 468, 56, 46)
        private val RALLIART_OIL_TEMP_HIT_BOX = intArrayOf(774, 325, 214, 197)
        private val RALLIART_OIL_TEMP_VALUE_BOX = intArrayOf(800, 392, 162, 76)
        private val RALLIART_OIL_TEMP_MIN_MAX_BOX = intArrayOf(926, 468, 56, 46)

        const val TAG = "UTCOMPDashboard"
    }

    private enum class DataMode { USB, SIM }
    private enum class SimTestMode {
        OFF,
        NORMAL,
        WARNING,
        CRITICAL,
    }

    private enum class UiMode { FANCY, SIMPLE, DEBUG }
    private enum class Page(
        val title: String,
        val rows: Int,
        val columns: Int,
    ) {
        RACE_2X2("Race 2×2", 2, 2),
        STRIP_1X4("Strip 1×4", 4, 1),
        FULL_2X4("Full 2×4", 4, 2),
    }

    private enum class MinMaxMode { ON_TAP, ALWAYS }

    private lateinit var usb: UtcompUsbTransport
    private lateinit var statusText: TextView
    private lateinit var controlsPanel: LinearLayout
    private lateinit var dashboardRoot: LinearLayout
    private lateinit var logTitleText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var csvLogger: UtcompCsvLogger
    private var dataLogTreeUri: Uri? = null

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.US)
    private val handler = Handler(Looper.getMainLooper())
    private var dataMode = DataMode.USB
    private var simTestMode = SimTestMode.OFF
    private val simTickerHandler = Handler(Looper.getMainLooper())
    private val autoUsbHandler = Handler(Looper.getMainLooper())
    private val dashboardRenderHandler = Handler(Looper.getMainLooper())
    private val decodedRenderPosted = AtomicBoolean(false)
    private val decodedRenderRunnable = Runnable {
        decodedRenderPosted.set(false)
        scheduleDashboardRender()
    }
    private var dashboardRenderPending = false
    private var dashboardRenderDeferredByTouch = false
    private var dashboardTouchActive = false
    private var dashboardTouchReleasedAtMs = 0L
    private val dashboardRenderRunnable = Runnable {
        dashboardRenderPending = false
        if (shouldDeferDashboardRenderForTouch()) {
            dashboardRenderDeferredByTouch = true
            scheduleDashboardRender(DASHBOARD_TOUCH_DEFER_RETRY_MS)
            return@Runnable
        }

        dashboardRenderDeferredByTouch = false
        renderDashboard()
    }

    private var lastSlowUsbRequestMs = 0L

    private val autoUsbRunnable = object : Runnable {
        override fun run() {
            if (dataMode != DataMode.USB) {
                return
            }

            if (!connected) {
                runCatching { usb.requestPermissionAndConnect(logDiscovery = false) }
                autoUsbHandler.postDelayed(this, USB_RECONNECT_MS)
                return
            }

            val nowMs = SystemClock.elapsedRealtime()
            runCatching {
                requestFastLiveData()

                if (nowMs - lastSlowUsbRequestMs >= USB_SLOW_POLL_MS) {
                    lastSlowUsbRequestMs = nowMs
                    requestSlowLiveData()
                }
            }

            autoUsbHandler.postDelayed(this, USB_FAST_POLL_MS)
        }
    }


    private val simTickerRunnable = object : Runnable {
        override fun run() {
            if (simTestMode == SimTestMode.OFF && dataMode != DataMode.SIM) {
                return
            }

            scheduleDashboardRender(delayMs = 0L)
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
    private var topBarButtonsVisible = false
    private var topBarChromeView: View? = null
    private var topBarHintText: TextView? = null
    private var lastDashboardBoxTapMs = 0L
    private var lastDashboardTapToken: String? = null
    private var suppressDashboardClickUntilMs = 0L
    private var lastBoxEditorLaunchMs = 0L

    private var topBarActionButtons: List<Button> = emptyList()
    private var gearButton: Button? = null
    private val topBarHideHandler = Handler(Looper.getMainLooper())
    private val topBarHideRunnable = Runnable {
        topBarButtonsVisible = false
        updateTopBarChromeVisibility()
    }

    private var dashboardPages = DefaultDashboardPages.all
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var showSourceLine = true
    private val minMaxBySensor = LinkedHashMap<String, MinMax>()
    private val smoothedValuesByBox = LinkedHashMap<String, Float>()
    private var minMaxDisplayMode = MinMaxMode.ON_TAP
    private var activeMinMaxCard: String? = null
    private var minMaxHideRunnable: Runnable? = null

    private data class MinMax(
        var min: Float = Float.NaN,
        var max: Float = Float.NaN,
    )

    private data class RalliartLayoutKey(
        val page: Page,
        val config: DashboardPageConfig,
    )

    private data class RalliartMinMaxViews(
        val container: LinearLayout,
        val maxText: TextView,
        val minText: TextView,
    )

    private class RalliartDashboardBinding(
        val key: RalliartLayoutKey,
        val root: FrameLayout,
        val headerText: TextView,
        val boostValueText: TextView,
        val boostNeedle: RalliartBoostNeedleView,
        val boostMinMax: RalliartMinMaxViews?,
        val afrValueText: TextView,
        val afrOverlay: FrameLayout,
        val afrTargetBand: View,
        val afrMarker: View,
        val oilPressureAlarmFill: View,
        val oilPressureValueText: TextView,
        val oilPressureMinMax: RalliartMinMaxViews?,
        val oilTempAlarmFill: View,
        val oilTempValueText: TextView,
        val oilTempMinMax: RalliartMinMaxViews?,
    ) {
        var afr = Float.NaN
        var afrTargetMin = 12.8f
        var afrTargetMax = 14.2f

        fun updateAfrOverlay() {
            val total = afrOverlay.width
            if (total <= 0) return

            val barMin = 10.0f
            val barMax = 20.0f
            val startFraction = ((afrTargetMin - barMin) / (barMax - barMin)).coerceIn(0f, 1f)
            val endFraction = ((afrTargetMax - barMin) / (barMax - barMin)).coerceIn(0f, 1f)
            val valueFraction = if (afr.isFinite()) {
                ((afr - barMin) / (barMax - barMin)).coerceIn(0f, 1f)
            } else {
                0f
            }

            val bandStart = (total * startFraction).toInt()
            val bandEnd = (total * endFraction).toInt()
            afrTargetBand.layoutParams = FrameLayout.LayoutParams(
                (bandEnd - bandStart).coerceAtLeast(6),
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply {
                leftMargin = bandStart
            }

            val markerLeft = ((total * valueFraction).toInt() - 4)
                .coerceIn(0, (total - 8).coerceAtLeast(0))
            afrMarker.layoutParams = FrameLayout.LayoutParams(
                8,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply {
                leftMargin = markerLeft
            }
        }
    }

    private var ralliartBinding: RalliartDashboardBinding? = null

    private data class SimpleLayoutKey(
        val page: Page,
        val config: DashboardPageConfig,
    )

    private data class SimpleCardBinding(
        val boxIndex: Int,
        val box: DashboardBoxConfig,
        val card: LinearLayout,
        val background: GradientDrawable,
        val valueText: TextView,
        val unitText: TextView?,
        val maxText: TextView?,
        val minText: TextView?,
        val minMaxKey: String?,
    )

    private data class SimpleDashboardBinding(
        val key: SimpleLayoutKey,
        val root: LinearLayout,
        val cards: List<SimpleCardBinding>,
    )

    private var simpleBinding: SimpleDashboardBinding? = null

    private data class CsvViewerRow(
        val time: String,
        val wallTimeMs: Long?,
        val elapsedRealtimeMs: Long?,
        val afr: Float,
        val boost: Float,
        val oilPressure: Float,
        val oilTemp: Float,
    )

    private data class CsvViewerRange(
        val min: Float,
        val max: Float,
    )

    private data class CsvViewerStats(
        val afr: CsvViewerRange,
        val boost: CsvViewerRange,
        val oilPressure: CsvViewerRange,
        val oilTemp: CsvViewerRange,
    )

    private data class CsvViewerPreview(
        val totalRows: Int,
        val graphRows: List<CsvViewerRow>,
        val sourceColumns: String,
        val firstTime: String,
        val lastTime: String,
        val durationText: String,
        val sampleRateHz: Float,
        val stats: CsvViewerStats,
    )

    private class CsvRangeAccumulator {
        private var minValue = Float.NaN
        private var maxValue = Float.NaN

        fun add(value: Float) {
            if (!value.isFinite()) return
            minValue = if (minValue.isNaN()) value else minOf(minValue, value)
            maxValue = if (maxValue.isNaN()) value else maxOf(maxValue, value)
        }

        fun toRange(): CsvViewerRange = CsvViewerRange(minValue, maxValue)
    }

    private val simRunnable = object : Runnable {
        override fun run() {
            if (simTestMode != SimTestMode.OFF || dataMode == DataMode.SIM) {
                SimulationEngine.update(UtcompDecoder.snapshot, simTick++)
                scheduleDashboardRender(delayMs = 0L)
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
        loadDashboardPrefs()
        applyFastSensorSmoothingMigrationIfNeeded()
        dataMode = DataMode.USB
        simTestMode = SimTestMode.OFF
        csvLogger = UtcompCsvLogger(this, ::appendLog)
        usb = UtcompUsbTransport(
            context = this,
            log = ::appendLog,
            onConnectionChanged = ::onUsbConnectionChanged,
            onDecodedSnapshot = ::onDecodedSnapshot,
        )
        buildUi()
        usb.register()
        startUsbAutoConnect()

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
        val decorView = window.decorView

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            decorView.windowInsetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
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


    override fun onPause() {
        saveDashboardPrefs()
        super.onPause()
    }

    override fun onDestroy() {
        dashboardRenderHandler.removeCallbacks(dashboardRenderRunnable)
        dashboardRenderHandler.removeCallbacks(decodedRenderRunnable)
        decodedRenderPosted.set(false)
        dashboardRenderPending = false
        saveDashboardPrefs()
        stopUsbAutoConnect()
        autoRefresh = false
        handler.removeCallbacksAndMessages(null)
        usb.unregister()
        usb.close()
        if (::csvLogger.isInitialized) csvLogger.close()
        stopSimTicker()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = ClickableLinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            setBackgroundColor(Color.rgb(10, 12, 16))
        }

        val topBar = ClickableLinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(2, 0, 2, 4)
        }

        topBar.addView(TextView(this).apply {
            text = topBarHintText()
            textSize = 11f
            maxLines = 2
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.rgb(165, 174, 190))
            this@MainActivity.topBarHintText = this
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, 8, 0)
        })

        topBar.addView(Button(this).apply {
            text = simModeButtonLabel()
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundedBg(Color.rgb(16, 20, 30), 18f)
            setOnClickListener { cycleSimTestMode() }
            this@MainActivity.simModeButton = this
        }, LinearLayout.LayoutParams(86, 48).apply {
            setMargins(0, 0, 6, 0)
        })

        topBar.addView(Button(this).apply {
            text = editModeButtonText()
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundedBg(Color.rgb(16, 20, 30), 18f)
            setOnClickListener { toggleEditMode() }
            this@MainActivity.editModeButton = this
        }, LinearLayout.LayoutParams(86, 48).apply {
            setMargins(0, 0, 6, 0)
        })

        topBar.addView(Button(this).apply {
            text = "⚙ MENU"
            textSize = 12f
            isAllCaps = false
            setOnClickListener { toggleControls() }
            this@MainActivity.gearButton = this
            setOnLongClickListener { toggleEditMode(); true }
            background = roundedBg(Color.rgb(16, 20, 30), 18f)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(86, 48))

        root.addView(topBar, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        topBarActionButtons = collectTopBarButtons(topBar)
        topBarChromeView = topBar
        updateTopBarButtonDescriptions()
        updateTopBarChromeVisibility()

        statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(190, 198, 210))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 6)
            visibility = View.GONE
        }
        root.addView(statusText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val controls = ClickableLinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val row1 = ClickableLinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("USB connect") {
            setUsbDataMode()
            usb.requestPermissionAndConnect()
        })
        row1.addView(button(simModeButtonText()) { cycleSimTestMode() })
        row1.addView(button("Auto") { toggleAutoRefresh() })
        controls.addView(row1)

        val row2 = ClickableLinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(button("Style") { cycleUiMode() })
        row2.addView(button("Page") { cyclePage() })
        row2.addView(button("REQ live") { requestLiveData(logEach = true) })
        controls.addView(row2)

        val row3 = ClickableLinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(button("REQ settings") { requestSettingsData() })
        row3.addView(button("Firmware") { requestUsb(TransmitterConstants.UtcompPid.FIRMWARE, true) })
        row3.addView(button("Clear log") { logText.text = "" })
        controls.addView(row3)

        val row4 = ClickableLinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row4.addView(button("Reset min/max") { resetAllMinMax() })
        row4.addView(button("Min/max") { toggleMinMaxMode() })
        row4.addView(button("Toggle subtitles") {
            showSourceLine = !showSourceLine
            appendLog("Sensor source subtitles ${if (showSourceLine) "enabled" else "hidden"}")
            renderDashboard()
        })
        controls.addView(row4)

        val row5 = ClickableLinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row5.addView(button("Data log") { showDataLoggerMenu() })
        controls.addView(row5)

        controlsPanel = controls.apply {
            visibility = View.GONE
            background = roundedBg(Color.rgb(15, 18, 24), 18f)
            setPadding(8, 8, 8, 8)
        }
        root.addView(controlsPanel, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val dashScroll = ScrollView(this).apply {
            isFillViewport = true
            setOnTouchListener { _, event ->
                handlePageSwipe(event)
            }
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
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private val dashboardPrefs: SharedPreferences
        get() = getSharedPreferences("utcomp_dashboard", MODE_PRIVATE)

    private fun applyFastSensorSmoothingMigrationIfNeeded() {
        val prefs = dashboardPrefs
        if (prefs.getBoolean("fastSensorSmoothingV1Applied", false)) return

        var touched = false
        dashboardPages = dashboardPages.map { pageConfig ->
            pageConfig.copy(
                boxes = pageConfig.boxes.map { box ->
                    if (
                        box.smoothingAlpha >= 0.999f &&
                        (box.sensor == DashboardSensor.AFR ||
                            box.sensor == DashboardSensor.BOOST ||
                            box.sensor == DashboardSensor.OIL_PRESSURE)
                    ) {
                        touched = true
                        box.copy(smoothingAlpha = 0.35f)
                    } else {
                        box
                    }
                },
            )
        }

        prefs.edit().putBoolean("fastSensorSmoothingV1Applied", true).apply()
        if (touched) saveDashboardPrefs()
    }

    private fun loadDashboardPrefs() {
        val prefs = dashboardPrefs

        prefs.getString("uiMode", null)?.let { saved ->
            enumValues<UiMode>().firstOrNull { it.name == saved }?.let { uiMode = it }
        }

        prefs.getString("page", null)?.let { saved ->
            enumValues<Page>().firstOrNull { it.name == saved }?.let { page = it }
        }

        showSourceLine = prefs.getBoolean("showSourceLine", showSourceLine)
        dataLogTreeUri = prefs.getString("dataLogTreeUri", null)?.let { Uri.parse(it) }

        dashboardPagesFromJson(prefs.getString("dashboardPagesJson", null))?.let { savedPages ->
            if (savedPages.isNotEmpty()) {
                dashboardPages = savedPages
            }
        }
    }

    private fun saveDashboardPrefs() {
        runCatching {
            dashboardPrefs.edit()
                .putString("uiMode", uiMode.name)
                .putString("page", page.name)
                .putBoolean("showSourceLine", showSourceLine)
                .putString("dataLogTreeUri", dataLogTreeUri?.toString())
                .putString("dashboardPagesJson", dashboardPagesToJson(dashboardPages))
                .apply()
        }.onFailure { error ->
            appendLog("Could not save dashboard settings: ${error.message}")
        }
    }

    private fun dashboardPagesToJson(pages: List<DashboardPageConfig>): String =
        JSONArray().apply {
            pages.forEach { pageConfig ->
                put(pageConfigToJson(pageConfig))
            }
        }.toString()

    private fun pageConfigToJson(pageConfig: DashboardPageConfig): JSONObject =
        JSONObject().apply {
            put("id", pageConfig.id)
            put("title", pageConfig.title)
            put("rows", pageConfig.rows)
            put("columns", pageConfig.columns)
            put("boxes", JSONArray().apply {
                pageConfig.boxes.forEach { box ->
                    put(boxConfigToJson(box))
                }
            })
        }

    private fun boxConfigToJson(box: DashboardBoxConfig): JSONObject =
        JSONObject().apply {
            put("sensor", box.sensor.name)
            put("row", box.row)
            put("column", box.column)
            put("rowSpan", box.rowSpan)
            put("columnSpan", box.columnSpan)
            putFloatJson(this, "valueScale", box.valueScale)
            putFloatJson(this, "iconScale", box.iconScale)
            putFloatJson(this, "iconValueGapScale", box.iconValueGapScale)
            putFloatJson(this, "scaleMin", box.scaleMin)
            putFloatJson(this, "scaleMax", box.scaleMax)
            putFloatJson(this, "smoothingAlpha", box.smoothingAlpha)
            putFloatJson(this, "warningLow", box.warningLow)
            putFloatJson(this, "criticalLow", box.criticalLow)
            putFloatJson(this, "warningHigh", box.warningHigh)
            putFloatJson(this, "criticalHigh", box.criticalHigh)
            putFloatJson(this, "oilPressureBoostArmBar", box.oilPressureBoostArmBar)
            putFloatJson(this, "oilPressureWarningBar", box.warningLow)
            putFloatJson(this, "oilPressureCriticalBar", box.criticalLow)
            put("decimalPlaces", box.decimalPlaces)
            put("backgroundColor", box.backgroundColor)
            put("foregroundColor", box.foregroundColor)
            put("unitColor", box.unitColor)
            put("alarmColor", box.alarmColor)
            put("minColor", box.minColor)
            put("maxColor", box.maxColor)
            put("valueColor", box.valueColor)
            put("warningColor", box.warningColor)
            put("criticalColor", box.criticalColor)
            put("warningValueColor", box.warningValueColor)
            put("criticalValueColor", box.criticalValueColor)
            put("splitValueDigits", box.splitValueDigits)
            put("alarmColorsBackground", box.alarmColorsBackground)
            put("alarmColorsValue", box.alarmColorsValue)
            put("oilPressureBoostAlarm", box.oilPressureBoostAlarm)
            put("showIcon", box.showIcon)
            put("showUnit", box.showUnit)
            put("showMinMax", box.showMinMax)
        }

    private fun dashboardPagesFromJson(raw: String?): List<DashboardPageConfig>? {
        if (raw.isNullOrBlank()) return null

        return runCatching {
            val array = JSONArray(raw)
            val defaults = DefaultDashboardPages.all
            (0 until array.length()).map { index ->
                val fallback = defaults.getOrElse(index) { defaults.last() }
                pageConfigFromJson(array.optJSONObject(index), fallback)
            }
        }.getOrNull()
    }

    private fun pageConfigFromJson(json: JSONObject?, fallback: DashboardPageConfig): DashboardPageConfig {
        if (json == null) return fallback

        val boxArray = json.optJSONArray("boxes")
        val boxes = if (boxArray != null) {
            (0 until boxArray.length()).map { index ->
                val fallbackBox = fallback.boxes.getOrElse(index) {
                    fallback.boxes.lastOrNull() ?: DashboardBoxConfig(DashboardSensor.BOOST, row = 0, column = 0)
                }
                boxConfigFromJson(boxArray.optJSONObject(index), fallbackBox)
            }
        } else {
            fallback.boxes
        }

        return DashboardPageConfig(
            id = json.optString("id", fallback.id),
            title = json.optString("title", fallback.title),
            rows = json.optInt("rows", fallback.rows),
            columns = json.optInt("columns", fallback.columns),
            boxes = boxes,
        )
    }

    private fun boxConfigFromJson(json: JSONObject?, fallback: DashboardBoxConfig): DashboardBoxConfig {
        if (json == null) return fallback

        return fallback.copy(
            sensor = dashboardSensorFromName(json.optString("sensor", fallback.sensor.name), fallback.sensor),
            row = json.optInt("row", fallback.row),
            column = json.optInt("column", fallback.column),
            rowSpan = json.optInt("rowSpan", fallback.rowSpan),
            columnSpan = json.optInt("columnSpan", fallback.columnSpan),
            valueScale = optFloatJson(json, "valueScale", fallback.valueScale),
            iconScale = optFloatJson(json, "iconScale", fallback.iconScale),
            iconValueGapScale = optFloatJson(json, "iconValueGapScale", fallback.iconValueGapScale),
            scaleMin = optFloatJson(json, "scaleMin", fallback.scaleMin),
            scaleMax = optFloatJson(json, "scaleMax", fallback.scaleMax),
            smoothingAlpha = optFloatJson(json, "smoothingAlpha", fallback.smoothingAlpha),
            warningLow = optFloatJson(json, "warningLow", fallback.warningLow),
            criticalLow = optFloatJson(json, "criticalLow", fallback.criticalLow),
            warningHigh = optFloatJson(json, "warningHigh", fallback.warningHigh),
            criticalHigh = optFloatJson(json, "criticalHigh", fallback.criticalHigh),
            oilPressureBoostArmBar = optFloatJson(json, "oilPressureBoostArmBar", fallback.oilPressureBoostArmBar),
            oilPressureWarningBar = optFloatJson(json, "oilPressureWarningBar", fallback.oilPressureWarningBar),
            oilPressureCriticalBar = optFloatJson(json, "oilPressureCriticalBar", fallback.oilPressureCriticalBar),
            decimalPlaces = json.optInt("decimalPlaces", fallback.decimalPlaces),
            backgroundColor = json.optInt("backgroundColor", fallback.backgroundColor),
            foregroundColor = json.optInt("foregroundColor", fallback.foregroundColor),
            unitColor = json.optInt("unitColor", fallback.unitColor),
            alarmColor = json.optInt("alarmColor", fallback.alarmColor),
            minColor = json.optInt("minColor", fallback.minColor),
            maxColor = json.optInt("maxColor", fallback.maxColor),
            valueColor = json.optInt("valueColor", fallback.valueColor),
            warningColor = json.optInt("warningColor", fallback.warningColor),
            criticalColor = json.optInt("criticalColor", fallback.criticalColor),
            warningValueColor = json.optInt("warningValueColor", fallback.warningValueColor),
            criticalValueColor = json.optInt("criticalValueColor", fallback.criticalValueColor),
            splitValueDigits = json.optBoolean("splitValueDigits", fallback.splitValueDigits),
            alarmColorsBackground = json.optBoolean("alarmColorsBackground", fallback.alarmColorsBackground),
            alarmColorsValue = json.optBoolean("alarmColorsValue", fallback.alarmColorsValue),
            oilPressureBoostAlarm = json.optBoolean("oilPressureBoostAlarm", fallback.oilPressureBoostAlarm),
            showIcon = json.optBoolean("showIcon", fallback.showIcon),
            showUnit = json.optBoolean("showUnit", fallback.showUnit),
            showMinMax = json.optBoolean("showMinMax", fallback.showMinMax),
        )
    }

    private fun dashboardSensorFromName(name: String?, fallback: DashboardSensor): DashboardSensor =
        runCatching { DashboardSensor.valueOf(name ?: fallback.name) }.getOrDefault(fallback)

    private fun putFloatJson(json: JSONObject, key: String, value: Float) {
        if (value.isNaN() || value.isInfinite()) {
            json.put(key, JSONObject.NULL)
        } else {
            json.put(key, value.toDouble())
        }
    }

    private fun optFloatJson(json: JSONObject, key: String, fallback: Float): Float =
        if (!json.has(key) || json.isNull(key)) {
            fallback
        } else {
            json.optDouble(key, fallback.toDouble()).toFloat()
        }

    private fun currentPageConfig(): DashboardPageConfig =
        dashboardPages.getOrNull(page.ordinal) ?: dashboardPages.first()

    private fun updateCurrentPage(transform: (DashboardPageConfig) -> DashboardPageConfig) {
        dashboardPages = dashboardPages.mapIndexed { index, pageConfig ->
            if (index == page.ordinal) transform(pageConfig) else pageConfig
        }
        saveDashboardPrefs()
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

    private fun startUsbAutoConnect() {
        if (dataMode != DataMode.USB) return
        autoRefresh = true
        autoUsbHandler.removeCallbacks(autoUsbRunnable)
        autoUsbHandler.postDelayed(autoUsbRunnable, 350L)
    }

    private fun stopUsbAutoConnect() {
        autoUsbHandler.removeCallbacks(autoUsbRunnable)
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
        if (dataMode == DataMode.USB) {
            startUsbAutoConnect()
        } else {
            stopUsbAutoConnect()
        }
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
            text = simModeButtonLabel()
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

    private fun simModeButtonLabel(): String =
        when (simTestMode) {
            SimTestMode.OFF -> "▣ SIM"
            SimTestMode.NORMAL -> "▣ SIM"
            SimTestMode.WARNING -> "⚠ WARN"
            SimTestMode.CRITICAL -> "‼ CRIT"
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

    private fun editModeButtonText(): String =
        if (editMode) "✓ DONE" else "✎ EDIT"

    private fun toggleEditMode() {
        editMode = !editMode
        if (editMode) {
            revealTopBarChrome(autoHide = false)
        } else {
            topBarButtonsVisible = false
            topBarHideHandler.removeCallbacks(topBarHideRunnable)
        }
        updateEditModeButton()
        appendLog("Edit mode ${if (editMode) "enabled" else "disabled"}")
        renderDashboard()
    }

    private fun collectTopBarButtons(view: View): List<Button> {
        val result = mutableListOf<Button>()

        fun visit(node: View) {
            if (node is Button) {
                result += node
            }
            if (node is ViewGroup) {
                for (i in 0 until node.childCount) {
                    visit(node.getChildAt(i))
                }
            }
        }

        visit(view)
        return result
    }

    private fun topBarChromeButtons(): List<Button> =
        topBarActionButtons.ifEmpty {
            listOfNotNull(simModeButton, editModeButton, gearButton)
        }

    private fun topBarHintText(): String =
        "Tap: min/max · Quick double tap: menu · Long press: menu"

    private fun View.setTooltipCompat(text: CharSequence?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            tooltipText = text
        }
    }

    private fun updateEditModeButton() {
        editModeButton?.apply {
            text = editModeButtonText()
            contentDescription = if (editMode) {
                "Finish editing dashboard boxes"
            } else {
                "Edit dashboard boxes"
            }
            setTooltipCompat(contentDescription)
        }
    }

    private fun updateTopBarButtonDescriptions() {
        updateEditModeButton()
        topBarHintText?.text = topBarHintText()

        simModeButton?.apply {
            contentDescription = "SIM test data mode. Current mode: ${simModeDescription()}"
            setTooltipCompat(contentDescription)
        }
        editModeButton?.apply {
            contentDescription = if (editMode) "Finish editing dashboard boxes" else "Edit dashboard boxes"
            setTooltipCompat(contentDescription)
        }
        gearButton?.apply {
            contentDescription = "Open controls menu"
            setTooltipCompat(contentDescription)
        }
    }

    private fun updateTopBarChromeVisibility() {
        updateTopBarButtonDescriptions()
        val show = topBarButtonsVisible || controlsVisible || editMode

        topBarChromeView?.visibility = if (show) View.VISIBLE else View.GONE

        topBarChromeButtons().forEach { button ->
            button.visibility = if (show) View.VISIBLE else View.GONE
        }
    }


    private fun revealTopBarChrome(autoHide: Boolean = true) {
        topBarButtonsVisible = true
        updateTopBarChromeVisibility()

        topBarHideHandler.removeCallbacks(topBarHideRunnable)
        if (autoHide) {
            topBarHideHandler.postDelayed(topBarHideRunnable, 4500L)
        }
    }

    private fun cancelPendingDashboardSingleTap() {
        lastDashboardBoxTapMs = 0L
        lastDashboardTapToken = null
    }

    private fun suppressDashboardClicksBriefly() {
        suppressDashboardClickUntilMs = SystemClock.uptimeMillis() + DASHBOARD_SWIPE_CLICK_SUPPRESS_MS
        cancelPendingDashboardSingleTap()
    }

    private fun dashboardClickSuppressed(): Boolean =
        SystemClock.uptimeMillis() < suppressDashboardClickUntilMs

    private fun handleDashboardBoxTapForChrome(minMaxKey: String?, tapToken: String) {
        val now = SystemClock.uptimeMillis()
        val isSecondTap =
            lastDashboardTapToken == tapToken &&
                now - lastDashboardBoxTapMs <= DASHBOARD_DOUBLE_TAP_MS

        lastDashboardBoxTapMs = now
        lastDashboardTapToken = tapToken

        if (isSecondTap) {
            cancelPendingDashboardSingleTap()
            revealTopBarChrome(autoHide = true)
            return
        }

        // Old behavior delayed this by ~430 ms to wait for a double tap. That felt
        // laggy on the head unit. Show min/max immediately; a quick second tap still
        // opens the top-bar menu.
        if (minMaxKey != null) {
            showMinMaxInline(minMaxKey)
        }
    }


    private fun minMaxKeyForBox(box: DashboardBoxConfig, snapshot: UtcompDataSnapshot): String? {
        val sensor = box.sensor
        val rawValue = sensor.readValue(snapshot)
        return if (sensor == DashboardSensor.TIME || rawValue == null || !box.showMinMax) {
            null
        } else {
            sensor.label
        }
    }

    private fun openBoxEditorOnce(boxIndex: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastBoxEditorLaunchMs < 700L) {
            return
        }

        lastBoxEditorLaunchMs = now
        cancelPendingDashboardSingleTap()
        lastDashboardBoxTapMs = 0L
        showBoxEditor(boxIndex)
    }


    private fun attachDashboardBoxActions(card: View, boxIndex: Int, minMaxKey: String?) {
        card.isClickable = true
        card.setOnClickListener {
            if (dashboardClickSuppressed()) return@setOnClickListener

            if (editMode) {
                openBoxEditorOnce(boxIndex)
            } else {
                handleDashboardBoxTapForChrome(minMaxKey, "box:$boxIndex")
            }
        }
        card.setOnLongClickListener {
            cancelPendingDashboardSingleTap()
            lastDashboardBoxTapMs = 0L
            if (editMode) {
                openBoxEditorOnce(boxIndex)
            } else {
                revealTopBarChrome(autoHide = true)
            }
            true
        }
    }


    private fun toggleControls() {
        controlsVisible = !controlsVisible
        if (controlsVisible) {
            revealTopBarChrome(autoHide = false)
        } else if (!editMode) {
            topBarHideHandler.removeCallbacks(topBarHideRunnable)
            topBarButtonsVisible = false
            updateTopBarChromeVisibility()
        }
        renderDashboard()
    }


    private fun setUsbDataMode() {
        dataMode = DataMode.USB
        handler.removeCallbacks(simRunnable)
        appendLog("USB DataMode.USB enabled")

        if (dataMode == DataMode.USB) {
        startUsbAutoConnect()
        } else {
        stopUsbAutoConnect()
        }
        saveDashboardPrefs()
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
        saveDashboardPrefs()
        renderDashboard()
    }

    private fun cyclePage() {
        nextPage()
    }

    private fun nextPage() {
        val pages = Page.entries
        page = pages[(page.ordinal + 1) % pages.size]
        renderDashboard()
    }

    private fun previousPage() {
        val pages = Page.entries
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
                    suppressDashboardClicksBriefly()
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


    private fun onUsbConnectionChanged(isConnected: Boolean) {
        runOnUiThread {
            if (connected == isConnected) return@runOnUiThread
            connected = isConnected
            renderDashboardNow()
        }
    }

    private fun onDecodedSnapshot(snapshot: UtcompDataSnapshot) {
        if (::csvLogger.isInitialized) {
            csvLogger.offer(snapshot, source = "usb")
        }
        if (decodedRenderPosted.compareAndSet(false, true)) {
            dashboardRenderHandler.post(decodedRenderRunnable)
        }
    }

    private fun showDataLoggerMenu() {
        val savedFolder = dataLogTreeUri
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        items += csvLogger.statusText()
        actions += { appendLog(csvLogger.statusText()) }

        items += "View latest CSV: internal"
        actions += { viewLatestCsvLog("internal", File(filesDir, "utcomp-logs")) }

        items += "View latest CSV: app external"
        actions += { viewLatestCsvLog("app external", File(getExternalFilesDir("utcomp-logs") ?: filesDir, "csv")) }

        items += "Pick CSV log to view"
        actions += { pickCsvLogToView() }

        if (csvLogger.isRunning) {
            items += "Stop CSV logging"
            actions += { csvLogger.stop() }
        } else {
            items += "Start CSV logging: internal app storage"
            actions += { startCsvLoggingInternal() }

            items += "Start CSV logging: app external storage"
            actions += { startCsvLoggingAppExternal() }

            if (savedFolder != null) {
                items += "Start CSV logging: saved SD/folder"
                actions += { startCsvLoggingTree(savedFolder) }
            }

            items += "Pick SD card/folder and start CSV logging"
            actions += { pickCsvLoggingFolder() }
        }

        AlertDialog.Builder(this)
            .setTitle("High-resolution data logging")
            .setItems(items.toTypedArray()) { _, which -> actions.getOrNull(which)?.invoke() }
            .setNegativeButton("Close", null)
            .show()
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
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "text/csv",
                "text/comma-separated-values",
                "application/csv",
                "text/plain",
            ))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, DATA_LOG_VIEW_FILE_REQUEST)
    }

    private fun viewPickedCsvLog(uri: Uri) {
        val title = "picked: ${uri.lastPathSegment ?: uri}"
        appendLog("Loading CSV viewer from picked file: $uri")
        loadCsvLogPreview(title = title) {
            contentResolver.openInputStream(uri)?.bufferedReader()
                ?: error("Could not open selected CSV")
        }
    }

    private fun loadCsvLogPreview(title: String, openReader: () -> BufferedReader) {
        Thread({
            val result = runCatching {
                openReader().use { reader -> readCsvLogPreview(reader) }
            }

            handler.post {
                result
                    .onSuccess { preview -> showCsvLogPreviewDialog(title, preview) }
                    .onFailure { error -> appendLog("CSV viewer failed: ${error.message}") }
            }
        }, "utcomp-csv-viewer").start()
    }

    private fun readCsvLogPreview(reader: BufferedReader): CsvViewerPreview {
        val headerLine = reader.readLine() ?: return emptyCsvViewerPreview("empty file")
        val headers = parseCsvLine(headerLine).map { it.trim() }
        val lowerHeaders = headers.map { it.lowercase(Locale.US) }

        fun indexOfAny(vararg names: String): Int =
            names.firstNotNullOfOrNull { name ->
                lowerHeaders.indexOf(name.lowercase(Locale.US)).takeIf { it >= 0 }
            } ?: -1

        val wallTimeIndex = indexOfAny("wall_time_ms")
        val isoTimeIndex = indexOfAny("wall_time_iso")
        val elapsedTimeIndex = indexOfAny("elapsed_realtime_ms")
        val timeIndex = listOf(isoTimeIndex, wallTimeIndex, elapsedTimeIndex).firstOrNull { it >= 0 } ?: -1
        val afrIndex = indexOfAny("afr1", "adc_in_ch1")
        val boostIndex = indexOfAny("bar1", "adc_in_ch3")
        val oilPressureIndex = indexOfAny("bar2", "adc_in_ch4")
        val oilTempIndex = indexOfAny("temperature_ntc1_c", "temperature_oil_c")

        val sourceColumns = listOf(
            "time=${headers.getOrNull(timeIndex) ?: "?"}",
            "afr=${headers.getOrNull(afrIndex) ?: "?"}",
            "boost=${headers.getOrNull(boostIndex) ?: "?"}",
            "oilP=${headers.getOrNull(oilPressureIndex) ?: "?"}",
            "oilT=${headers.getOrNull(oilTempIndex) ?: "?"}",
        ).joinToString(" · ")

        val graphRows = ArrayList<CsvViewerRow>(CSV_VIEW_GRAPH_MAX_POINTS)
        var graphStride = 1
        var total = 0
        var firstTime = ""
        var lastTime = ""
        var firstWallTimeMs: Long? = null
        var lastWallTimeMs: Long? = null
        var firstElapsedMs: Long? = null
        var lastElapsedMs: Long? = null

        val afrRange = CsvRangeAccumulator()
        val boostRange = CsvRangeAccumulator()
        val oilPressureRange = CsvRangeAccumulator()
        val oilTempRange = CsvRangeAccumulator()

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val fields = parseCsvLine(line)
            total++

            val wallTimeMs = fields.getOrNull(wallTimeIndex).toLongOrNullCompat()
            val elapsedMs = fields.getOrNull(elapsedTimeIndex).toLongOrNullCompat()
            val displayTime = compactCsvTime(fields.getOrNull(timeIndex).orEmpty())

            val row = CsvViewerRow(
                time = displayTime,
                wallTimeMs = wallTimeMs,
                elapsedRealtimeMs = elapsedMs,
                afr = fields.getOrNull(afrIndex).toFloatOrNan(),
                boost = fields.getOrNull(boostIndex).toFloatOrNan(),
                oilPressure = fields.getOrNull(oilPressureIndex).toFloatOrNan(),
                oilTemp = fields.getOrNull(oilTempIndex).toFloatOrNan(),
            )

            if (firstTime.isBlank()) firstTime = displayTime
            lastTime = displayTime
            if (firstWallTimeMs == null && wallTimeMs != null) firstWallTimeMs = wallTimeMs
            if (wallTimeMs != null) lastWallTimeMs = wallTimeMs
            if (firstElapsedMs == null && elapsedMs != null) firstElapsedMs = elapsedMs
            if (elapsedMs != null) lastElapsedMs = elapsedMs

            afrRange.add(row.afr)
            boostRange.add(row.boost)
            oilPressureRange.add(row.oilPressure)
            oilTempRange.add(row.oilTemp)

            if (total % graphStride == 0) {
                graphRows += row
                if (graphRows.size > CSV_VIEW_GRAPH_MAX_POINTS * 2) {
                    val compacted = graphRows.filterIndexed { index, _ -> index % 2 == 0 }
                    graphRows.clear()
                    graphRows.addAll(compacted)
                    graphStride *= 2
                }
            }
        }

        if (graphRows.size > CSV_VIEW_GRAPH_MAX_POINTS) {
            val keepEvery = (graphRows.size.toFloat() / CSV_VIEW_GRAPH_MAX_POINTS.toFloat())
            val compacted = ArrayList<CsvViewerRow>(CSV_VIEW_GRAPH_MAX_POINTS)
            var next = 0f
            for (index in graphRows.indices) {
                if (index + 0.001f >= next) {
                    compacted += graphRows[index]
                    next += keepEvery
                }
            }
            graphRows.clear()
            graphRows.addAll(compacted.take(CSV_VIEW_GRAPH_MAX_POINTS))
        }

        val durationMs =
            durationMs(firstWallTimeMs, lastWallTimeMs)
                ?: durationMs(firstElapsedMs, lastElapsedMs)
                ?: 0L

        val sampleRate = if (durationMs > 0L && total > 1) {
            (total - 1).toFloat() / (durationMs.toFloat() / 1000f)
        } else {
            Float.NaN
        }

        return CsvViewerPreview(
            totalRows = total,
            graphRows = graphRows.toList(),
            sourceColumns = sourceColumns,
            firstTime = firstTime.ifBlank { "—" },
            lastTime = lastTime.ifBlank { "—" },
            durationText = formatDuration(durationMs),
            sampleRateHz = sampleRate,
            stats = CsvViewerStats(
                afr = afrRange.toRange(),
                boost = boostRange.toRange(),
                oilPressure = oilPressureRange.toRange(),
                oilTemp = oilTempRange.toRange(),
            ),
        )
    }

    private fun emptyCsvViewerPreview(sourceColumns: String): CsvViewerPreview =
        CsvViewerPreview(
            totalRows = 0,
            graphRows = emptyList(),
            sourceColumns = sourceColumns,
            firstTime = "—",
            lastTime = "—",
            durationText = "—",
            sampleRateHz = Float.NaN,
            stats = CsvViewerStats(
                afr = CsvViewerRange(Float.NaN, Float.NaN),
                boost = CsvViewerRange(Float.NaN, Float.NaN),
                oilPressure = CsvViewerRange(Float.NaN, Float.NaN),
                oilTemp = CsvViewerRange(Float.NaN, Float.NaN),
            ),
        )

    private fun showCsvLogPreviewDialog(title: String, preview: CsvViewerPreview) {
        val padding = csvViewerDp(12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            minimumHeight = (resources.displayMetrics.heightPixels * 0.86f).toInt()
            background = GradientDrawable().apply {
                setColor(Color.rgb(8, 10, 14))
                cornerRadius = 0f
            }
        }

        val titleText = TextView(this).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(238, 240, 246))
            isSingleLine = false
        }
        root.addView(titleText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val metaText = TextView(this).apply {
            text = buildString {
                append("rows=${preview.totalRows}")
                append(" · graph=${preview.graphRows.size}")
                append(" · ${preview.durationText}")
                if (preview.sampleRateHz.isFinite()) {
                    append(" · ${preview.sampleRateHz.formatOrDash(1)} Hz")
                }
                append("\n")
                append("${preview.firstTime.takeLast(12)} → ${preview.lastTime.takeLast(12)}")
                append("\n")
                append(preview.sourceColumns)
            }
            textSize = 11f
            setTextColor(Color.rgb(170, 178, 190))
            setPadding(0, csvViewerDp(4), 0, csvViewerDp(8))
        }
        root.addView(metaText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val summaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        summaryRow.addView(csvSummaryCard("AFR", preview.stats.afr, 2, ""), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        summaryRow.addView(csvSummaryCard("Boost", preview.stats.boost, 2, "bar"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        summaryRow.addView(csvSummaryCard("Oil P", preview.stats.oilPressure, 2, "bar"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        summaryRow.addView(csvSummaryCard("Oil T", preview.stats.oilTemp, 1, "°C"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(summaryRow, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val markers = csvGraphWarningMarkers()
        val markerText = TextView(this).apply {
            text = if (markers.isEmpty()) {
                "No active warning/critical markers configured for these graph channels."
            } else {
                "Markers: " + markers.joinToString(" · ") { marker ->
                    "${marker.seriesLabel} ${marker.label}"
                }
            }
            textSize = 10.5f
            setTextColor(Color.rgb(170, 178, 190))
            setPadding(0, csvViewerDp(7), 0, 0)
        }
        root.addView(markerText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

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
            setMarkers(markers)
        }
        root.addView(graph, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            csvViewerDp(560),
        ).apply {
            topMargin = csvViewerDp(10)
            bottomMargin = csvViewerDp(10)
        })

        val outerScroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(8, 10, 14))
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }

        val dialog = AlertDialog.Builder(this)
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

    private fun csvGraphWarningMarkers(): List<CsvLogGraphMarker> {
        val currentBoxes = currentPageConfig().boxes
        val allBoxes = currentBoxes + dashboardPages.flatMap { it.boxes }

        fun boxFor(sensor: DashboardSensor): DashboardBoxConfig? =
            currentBoxes.firstOrNull { it.sensor == sensor }
                ?: allBoxes.firstOrNull { it.sensor == sensor }

        fun Float.markerText(decimals: Int): String =
            fixed(decimals)

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
        addMarkers(markers, "Boost", boxFor(DashboardSensor.BOOST), decimals = 2)

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
        addMarkers(markers, "Oil P", oilPressureBox, decimals = 2, lowSuffix = oilPressureSuffix)

        addMarkers(markers, "Oil T", boxFor(DashboardSensor.OIL_TEMP), decimals = 1)
        return markers
    }

    private fun csvSummaryCard(label: String, range: CsvViewerRange, decimals: Int, suffix: String): TextView =
        TextView(this).apply {
            text = buildString {
                appendLine(label)
                append(range.min.formatOrDash(decimals))
                append(" → ")
                append(range.max.formatOrDash(decimals))
                if (suffix.isNotBlank()) append(" $suffix")
            }
            textSize = 10.5f
            setTextColor(Color.rgb(234, 236, 242))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(csvViewerDp(5), csvViewerDp(7), csvViewerDp(5), csvViewerDp(7))
            background = GradientDrawable().apply {
                setColor(Color.rgb(18, 21, 28))
                cornerRadius = csvViewerDp(9).toFloat()
                setStroke(1, Color.rgb(50, 56, 68))
            }
        }

    private fun csvViewerDp(value: Int): Int =
        (value.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

    private fun durationMs(startMs: Long?, endMs: Long?): Long? =
        if (startMs != null && endMs != null && endMs >= startMs) {
            endMs - startMs
        } else {
            null
        }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "—"
        val totalSeconds = ms / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val hours = minutes / 60L
        val minutePart = minutes % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%dh %02dm %02ds", hours, minutePart, seconds)
        } else {
            String.format(Locale.US, "%dm %02ds", minutes, seconds)
        }
    }

    private fun compactCsvTime(raw: String): String {
        val value = raw.trim().trim('"')
        val tIndex = value.indexOf('T')
        if (tIndex >= 0 && tIndex + 1 < value.length) {
            return value.substring(tIndex + 1)
                .substringBefore('+')
                .substringBefore('Z')
        }
        return value
    }

    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0

        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    fields += current.toString()
                    current.setLength(0)
                }
                else -> current.append(char)
            }
            index++
        }
        fields += current.toString()
        return fields
    }

    private fun String?.toFloatOrNan(): Float =
        this?.trim()?.trim('"')?.toFloatOrNull() ?: Float.NaN

    private fun String?.toLongOrNullCompat(): Long? =
        this?.trim()?.trim('"')?.toLongOrNull()

    private fun Float.formatOrDash(decimals: Int): String =
        fixed(decimals)

    private fun startCsvLoggingInternal() {
        runCatching {
            csvLogger.startInternal()
        }.onFailure { error ->
            appendLog("CSV logging start failed: ${error.message}")
        }
    }

    private fun startCsvLoggingAppExternal() {
        runCatching {
            csvLogger.startAppExternal()
        }.onFailure { error ->
            appendLog("CSV logging start failed: ${error.message}")
        }
    }

    private fun startCsvLoggingTree(uri: Uri) {
        runCatching {
            csvLogger.startTree(uri)
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
        startActivityForResult(intent, DATA_LOG_TREE_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == DATA_LOG_VIEW_FILE_REQUEST) {
            if (resultCode != RESULT_OK) return
            val uri = data?.data ?: run {
                appendLog("CSV viewer file selection returned no URI")
                return
            }
            viewPickedCsvLog(uri)
            return
        }

        if (requestCode != DATA_LOG_TREE_REQUEST || resultCode != RESULT_OK) return

        val uri = data?.data ?: run {
            appendLog("CSV logging folder selection returned no URI")
            return
        }

        val flags = data.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

        runCatching {
            contentResolver.takePersistableUriPermission(uri, flags)
        }.onFailure { error ->
            appendLog("Could not persist CSV logging folder permission: ${error.message}")
        }

        dataLogTreeUri = uri
        saveDashboardPrefs()
        startCsvLoggingTree(uri)
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

    private fun requestFastLiveData() {
        requestUsb(TransmitterConstants.UtcompPid.GENERAL_DATA2, false)
    }

    private fun requestSlowLiveData() {
        listOf(
            TransmitterConstants.UtcompPid.GENERAL_DATA1,
            TransmitterConstants.UtcompPid.CONSUMPTION_DATA,
            TransmitterConstants.UtcompPid.TEMPERATURES_DATA,
            TransmitterConstants.UtcompPid.VSS_DATA,
            TransmitterConstants.UtcompPid.TRIP_DATA,
        ).forEach { requestUsb(it, false) }
    }

    private fun appendLog(line: String) {
        val highFrequencyLine =
            line.startsWith("USB raw[") ||
                line.startsWith("USB RX DATA") ||
                line.startsWith("USB TX DATA") ||
                line.startsWith("USB STATUS") ||
                line.startsWith("USB write[") ||
                line.startsWith("queued ") ||
                line.startsWith("TXP ") ||
                line.startsWith("DECODE ")

        if (highFrequencyLine) {
            Log.d(TAG, line)
        } else {
            Log.i(TAG, line)
        }

        if (highFrequencyLine) return

        runOnUiThread {
            if (!::logText.isInitialized || !::logScroll.isInitialized) return@runOnUiThread

            val stamp = timeFmt.format(Date())
            val newEntry = "$stamp  $line\n"

            // Keep the on-screen debug TextView bounded. Without this, the radio eventually
            // crashes with OOM inside TextView.append()/DynamicLayout.reflow() after enough
            // USB/debug lines were appended, even when the debug view is hidden.
            val maxLogChars = 80_000
            val keepLogChars = 55_000

            if (logText.length() > maxLogChars) {
                val current = logText.text
                val keepStart = (current.length - keepLogChars).coerceAtLeast(0)
                logText.text = "… UI log trimmed …\n${current.subSequence(keepStart, current.length)}"
            }

            logText.append(newEntry)

            if (logScroll.isShown) {
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
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
        val boostWave = kotlin.math.sin(t * 0.12f)
        val tempWave = kotlin.math.sin(t * 0.045f)

        val boostBar = 1.55f + boostWave * 0.22f
        val afr = 11.25f - boostWave * 0.18f
        val oilTempC = 104.0f + tempWave * 3.0f
        val oilPressureBar = 5.15f + kotlin.math.sin(t * 0.09f) * 0.18f

        return UtcompDataSnapshot().copy(
            bar1 = boostBar,
            afr1 = afr,
            temperatureNtc1 = oilTempC,
            bar2 = oilPressureBar,
            adcInValCh0 = 13.9f + kotlin.math.sin(t * 0.04f) * 0.05f,
            temperatureDsA = 23.3f + kotlin.math.sin(t * 0.035f) * 0.4f,
            temperatureDsB = 22.0f + kotlin.math.sin(t * 0.03f) * 0.3f,
        )
    }

    private fun forceSimAlarmValues(base: UtcompDataSnapshot, warning: Boolean): UtcompDataSnapshot {
        val highBoostBar = if (warning) 1.75f else 2.08f
        val targetAfr = if (warning) 11.05f else 10.60f
        val oilPressureBar = if (warning) 4.30f else 3.70f
        val oilTempC = if (warning) 118.0f else 124.0f

        return base.copy(
            bar1 = highBoostBar,
            afr1 = targetAfr,
            bar2 = oilPressureBar,
            temperatureNtc1 = oilTempC,
            adcInValCh0 = 13.9f,
            temperatureDsA = 23.3f,
            temperatureDsB = 22.0f,
        )
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

    private fun markDashboardTouchActive() {
        dashboardTouchActive = true
        dashboardTouchReleasedAtMs = 0L
    }

    private fun markDashboardTouchFinished() {
        dashboardTouchActive = false
        dashboardTouchReleasedAtMs = SystemClock.uptimeMillis()

        if (dashboardRenderDeferredByTouch) {
            scheduleDashboardRender(DASHBOARD_TOUCH_DEFER_RETRY_MS)
        }
    }

    private fun shouldDeferDashboardRenderForTouch(): Boolean {
        if (dashboardTouchActive) return true

        val releasedAt = dashboardTouchReleasedAtMs
        if (releasedAt <= 0L) return false

        return SystemClock.uptimeMillis() - releasedAt < DASHBOARD_TOUCH_RENDER_GRACE_MS
    }

    private fun scheduleDashboardRender(delayMs: Long = DASHBOARD_RENDER_MS) {
        if (!::dashboardRoot.isInitialized) return
        if (dashboardRenderPending) return

        dashboardRenderPending = true
        dashboardRenderHandler.postDelayed(dashboardRenderRunnable, delayMs.coerceAtLeast(0L))
    }

    private fun renderDashboardNow() {
        dashboardRenderHandler.removeCallbacks(dashboardRenderRunnable)
        dashboardRenderPending = false
        dashboardRenderDeferredByTouch = false
        renderDashboard()
    }

    private fun renderDashboard() {
        if (!::dashboardRoot.isInitialized || !::statusText.isInitialized) return

        if (::controlsPanel.isInitialized) {
            controlsPanel.visibility = if (controlsVisible) View.VISIBLE else View.GONE
        }

        updateTopBarChromeVisibility()

        updateEditModeButton()
        updateSimModeButton()

        val showDebugLog = controlsVisible || uiMode == UiMode.DEBUG
        if (::usb.isInitialized) usb.verboseLogging = showDebugLog
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

        when (uiMode) {
            UiMode.FANCY -> {
                simpleBinding = null
                renderFancy(renderSnapshot)
            }
            UiMode.SIMPLE -> renderSimple(renderSnapshot)
            UiMode.DEBUG -> {
                ralliartBinding = null
                simpleBinding = null
                dashboardRoot.removeAllViews()
                renderDebug(renderSnapshot)
            }
        }
    }

    private data class NamedColor(
        val name: String,
        val color: Int,
    )

    private fun DashboardSensor.defaultDisplayDecimals(): Int =
        when (this) {
            DashboardSensor.AFR -> 2
            DashboardSensor.BOOST,
            DashboardSensor.OIL_PRESSURE,
            DashboardSensor.BATTERY -> 2
            DashboardSensor.OIL_TEMP,
            DashboardSensor.OUTSIDE_TEMP,
            DashboardSensor.INSIDE_TEMP -> 1
            DashboardSensor.TIME -> 0
        }

    private fun DashboardSensor.defaultDisplaySmoothing(): Float =
        when (this) {
            DashboardSensor.AFR,
            DashboardSensor.BOOST,
            DashboardSensor.OIL_PRESSURE -> 0.35f
            else -> 1.0f
        }


    private fun displayValueForBox(box: DashboardBoxConfig, boxIndex: Int, rawValue: Float?): Float? {
        val value = rawValue ?: return null
        if (value.isNaN() || value.isInfinite()) return value

        val alpha = box.smoothingAlpha.coerceIn(0.01f, 1.0f)
        if (alpha >= 0.999f || simTestMode != SimTestMode.OFF) {
            smoothedValuesByBox[smoothedValueKey(boxIndex, box)] = value
            return value
        }

        val key = smoothedValueKey(boxIndex, box)
        val previous = smoothedValuesByBox[key]
        val smoothed = if (previous == null || previous.isNaN() || previous.isInfinite()) {
            value
        } else {
            previous + (value - previous) * alpha
        }
        smoothedValuesByBox[key] = smoothed
        return smoothed
    }

    private fun smoothedValueKey(boxIndex: Int, box: DashboardBoxConfig): String =
        "${page.ordinal}:$boxIndex:${box.sensor.name}"

    private fun formatBoxValue(value: Float?, decimals: Int): String =
        value?.fixed(decimals.coerceIn(0, 2)) ?: "--"

    private fun styledValueText(valueText: String, split: Boolean): CharSequence {
        if (!split) return valueText

        val dot = valueText.indexOf('.')
        if (dot <= 0 || dot >= valueText.length - 1) return valueText

        val span = SpannableString(valueText)
        span.setSpan(RelativeSizeSpan(1.18f), 0, dot, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(RelativeSizeSpan(0.62f), dot, valueText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return span
    }

    private fun decimalsText(decimals: Int): String =
        when (decimals.coerceIn(0, 2)) {
            0 -> "0 decimals"
            1 -> "1 decimal"
            else -> "2 decimals"
        }

    private fun smoothingText(alpha: Float): String =
        when {
            alpha >= 0.999f -> "off"
            alpha >= 0.45f -> "light"
            alpha >= 0.20f -> "medium"
            else -> "heavy"
        }

    private fun smoothingValues(): FloatArray =
        floatArrayOf(1.0f, 0.5f, 0.25f, 0.10f)

    private fun smoothingLabels(current: Float): Array<String> {
        val values = smoothingValues()
        val names = arrayOf("Off", "Light", "Medium", "Heavy")
        return names.mapIndexed { index, name ->
            val marker = if (kotlin.math.abs(values[index] - current) < 0.01f) " ✓" else ""
            "$name$marker"
        }.toTypedArray()
    }

    private fun showDecimalsPicker(boxIndex: Int, box: DashboardBoxConfig) {
        val labels = arrayOf(0, 1, 2).map { decimals ->
            val marker = if (box.decimalPlaces.coerceIn(0, 2) == decimals) " ✓" else ""
            "${decimalsText(decimals)}$marker"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Decimal digits")
            .setItems(labels) { _, which ->
                updateBoxConfig(boxIndex) { it.copy(decimalPlaces = which) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSmoothingPicker(boxIndex: Int, box: DashboardBoxConfig) {
        val values = smoothingValues()
        AlertDialog.Builder(this)
            .setTitle("Smoothing")
            .setItems(smoothingLabels(box.smoothingAlpha)) { _, which ->
                smoothedValuesByBox.remove(smoothedValueKey(boxIndex, box))
                updateBoxConfig(boxIndex) { it.copy(smoothingAlpha = values[which]) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

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

    private fun showFloatSettingEditor(
        title: String,
        current: Float,
        unit: String,
        onSelected: (Float) -> Unit,
    ) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine(true)
            hint = unit
            if (!current.isNaN()) {
                setText(trimFloat(current))
                selectAll()
            }
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Current: ${formatThreshold(current, unit)}")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val parsed = input.text.toString()
                    .trim()
                    .replace(",", ".")
                    .toFloatOrNull()

                if (parsed != null) onSelected(parsed)
            }
            .setNeutralButton("Off") { _, _ -> onSelected(Float.NaN) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun defaultOilPressureBoostArmBar(sensor: DashboardSensor): Float =
        when (sensor) {
            DashboardSensor.OIL_PRESSURE -> 0.30f
            else -> Float.NaN
        }

    private fun defaultOilPressureWarningBar(sensor: DashboardSensor): Float =
        when (sensor) {
            DashboardSensor.OIL_PRESSURE -> 4.50f
            else -> Float.NaN
        }

    private fun defaultOilPressureCriticalBar(sensor: DashboardSensor): Float =
        when (sensor) {
            DashboardSensor.OIL_PRESSURE -> 4.00f
            else -> Float.NaN
        }

    private fun showOilPressureBoostAlarmEditor(boxIndex: Int, box: DashboardBoxConfig) {
        val actions = arrayOf(
            "Boost-armed low alarm: ${enabledText(box.oilPressureBoostAlarm)}",
            "Arm low alarm at boost: ${formatThreshold(box.oilPressureBoostArmBar, "BAR")}",
            "Warning low pressure: ${formatThreshold(box.warningLow, "BAR")}",
            "Critical low pressure: ${formatThreshold(box.criticalLow, "BAR")}",
            "Reset UTCOMP-like oil pressure defaults",
        )

        AlertDialog.Builder(this)
            .setTitle("Oil pressure alarm")
            .setMessage(
                oilPressureBoostAlarmSummary(box) +
                    "\n\nWarning/critical pressure here edits the normal Low alarm thresholds."
            )
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> updateBoxConfig(boxIndex) {
                        it.copy(oilPressureBoostAlarm = !it.oilPressureBoostAlarm)
                    }
                    1 -> showFloatSettingEditor(
                        "Arm low oil pressure alarm at boost",
                        box.oilPressureBoostArmBar,
                        "BAR",
                    ) { value ->
                        updateBoxConfig(boxIndex) { it.copy(oilPressureBoostArmBar = value) }
                    }
                    2 -> showFloatSettingEditor(
                        "Warning low oil pressure",
                        box.warningLow,
                        "BAR",
                    ) { value ->
                        updateBoxConfig(boxIndex) { it.copy(warningLow = value) }
                    }
                    3 -> showFloatSettingEditor(
                        "Critical low oil pressure",
                        box.criticalLow,
                        "BAR",
                    ) { value ->
                        updateBoxConfig(boxIndex) { it.copy(criticalLow = value) }
                    }
                    4 -> updateBoxConfig(boxIndex) {
                        it.copy(
                            oilPressureBoostAlarm = true,
                            oilPressureBoostArmBar = defaultOilPressureBoostArmBar(DashboardSensor.OIL_PRESSURE),
                            warningLow = defaultOilPressureWarningBar(DashboardSensor.OIL_PRESSURE),
                            criticalLow = defaultOilPressureCriticalBar(DashboardSensor.OIL_PRESSURE),
                            warningHigh = Float.NaN,
                            criticalHigh = Float.NaN,
                        )
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }


    private fun showHighThresholdEditor(
        title: String,
        boxIndex: Int,
        box: DashboardBoxConfig,
        current: Float,
        update: DashboardBoxConfig.(Float) -> DashboardBoxConfig,
    ) {
        showFloatSettingEditor(title, current, box.sensor.unit) { newValue ->
            updateBoxConfig(boxIndex) { it.update(newValue) }
        }
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
            .setMessage("Apply ${box.sensor.label} visual style to all boxes on ${pageConfig.title}?\n\nAlarm thresholds and alarm behavior are preserved.")
            .setPositiveButton("Apply") { _, _ ->
                updateCurrentPage { it.withBoxDefaultsAppliedToPage(box) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun trimFloat(value: Float): String =
        if (value % 1.0f == 0.0f) value.toInt().toString() else value.fixed(1)

    private fun colorName(color: Int): String =
        allNamedColors().firstOrNull { namedColor -> namedColor.color == color }?.name
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

        val oilBoostText = if (box.sensor == DashboardSensor.OIL_PRESSURE) {
            oilPressureBoostAlarmSummary(box)
        } else {
            "only for Oil pressure"
        }

        val actions = arrayOf(
            "Sensor: ${box.sensor.label}",
            "Value size: ${formatScale(box.valueScale)}",
            "Icon size: ${formatScale(box.iconScale)}",
            "Icon/value gap: ${formatScale(box.iconValueGapScale)}",
            "Decimals: ${decimalsText(box.decimalPlaces)}",
            "Split decimals: ${enabledText(box.splitValueDigits)}",
            "Smoothing: ${smoothingText(box.smoothingAlpha)}",
            "Normal value color: ${colorName(box.valueColor)}",
            "Background color: ${colorName(box.backgroundColor)}",
            "Warning low: ${formatThreshold(box.warningLow, box.sensor.unit)}",
            "Critical low: ${formatThreshold(box.criticalLow, box.sensor.unit)}",
            "Warning high: ${formatThreshold(box.warningHigh, box.sensor.unit)}",
            "Critical high: ${formatThreshold(box.criticalHigh, box.sensor.unit)}",
            "Oil pressure boost arm: $oilBoostText",
            "Warning background: ${colorName(box.warningColor)}",
            "Warning value color: ${colorName(box.warningValueColor)}",
            "Critical background: ${colorName(box.criticalColor)}",
            "Critical value color: ${colorName(box.criticalValueColor)}",
            "Alarm background: ${enabledText(box.alarmColorsBackground)}",
            "Alarm value color: ${enabledText(box.alarmColorsValue)}",
            "Icon: ${shownText(box.showIcon)}",
            "Unit: ${shownText(box.showUnit)}",
            "Min/max: ${shownText(box.showMinMax)}",
            "Apply visual style to all boxes on page",
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
                    3 -> showScalePicker(
                        title = "Icon/value gap",
                        current = box.iconValueGapScale,
                    ) { selected -> updateBoxConfig(boxIndex) { it.copy(iconValueGapScale = selected) } }
                    4 -> showDecimalsPicker(boxIndex, box)
                    5 -> updateBoxConfig(boxIndex) { it.copy(splitValueDigits = !it.splitValueDigits) }
                    6 -> showSmoothingPicker(boxIndex, box)
                    7 -> showColorPicker(
                        title = "Normal value color",
                        current = box.valueColor,
                        palette = valueColorPalette(),
                    ) { selected -> updateBoxConfig(boxIndex) { it.copy(valueColor = selected, foregroundColor = selected) } }
                    8 -> showColorPicker(
                        title = "Background color",
                        current = box.backgroundColor,
                        palette = backgroundColorPalette(),
                    ) { selected -> updateBoxConfig(boxIndex) { it.copy(backgroundColor = selected) } }
                    9 -> showHighThresholdEditor(
                        title = "Warning low",
                        boxIndex = boxIndex,
                        box = box,
                        current = box.warningLow,
                    ) { newValue -> copy(warningLow = newValue) }
                    10 -> showHighThresholdEditor(
                        title = "Critical low",
                        boxIndex = boxIndex,
                        box = box,
                        current = box.criticalLow,
                    ) { newValue -> copy(criticalLow = newValue) }
                    11 -> showHighThresholdEditor(
                        title = "Warning high",
                        boxIndex = boxIndex,
                        box = box,
                        current = box.warningHigh,
                    ) { newValue -> copy(warningHigh = newValue) }
                    12 -> showHighThresholdEditor(
                        title = "Critical high",
                        boxIndex = boxIndex,
                        box = box,
                        current = box.criticalHigh,
                    ) { newValue -> copy(criticalHigh = newValue) }
                    13 -> if (box.sensor == DashboardSensor.OIL_PRESSURE) {
                        showOilPressureBoostAlarmEditor(boxIndex, box)
                    } else {
                        showSensorPicker(boxIndex)
                    }
                    14 -> showColorPicker(
                        title = "Warning background",
                        current = box.warningColor,
                        palette = warningColorPalette(),
                    ) { selected -> updateBoxConfig(boxIndex) { it.copy(warningColor = selected) } }
                    15 -> showColorPicker(
                        title = "Warning value color",
                        current = box.warningValueColor,
                        palette = alarmValueColorPalette(),
                    ) { selected -> updateBoxConfig(boxIndex) { it.copy(warningValueColor = selected) } }
                    16 -> showColorPicker(
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
                    17 -> showColorPicker(
                        title = "Critical value color",
                        current = box.criticalValueColor,
                        palette = alarmValueColorPalette(),
                    ) { selected -> updateBoxConfig(boxIndex) { it.copy(criticalValueColor = selected) } }
                    18 -> updateBoxConfig(boxIndex) { it.copy(alarmColorsBackground = !it.alarmColorsBackground) }
                    19 -> updateBoxConfig(boxIndex) { it.copy(alarmColorsValue = !it.alarmColorsValue) }
                    20 -> updateBoxConfig(boxIndex) { it.copy(showIcon = !it.showIcon) }
                    21 -> updateBoxConfig(boxIndex) { it.copy(showUnit = !it.showUnit) }
                    22 -> updateBoxConfig(boxIndex) { it.copy(showMinMax = !it.showMinMax) }
                    23 -> confirmApplyStyleToPage(boxIndex)
                    24 -> updateBoxConfig(boxIndex) { withDefaultAlarmThresholds(it) }
                    25 -> updateBoxConfig(boxIndex) { clearAlarmThresholds(it) }
                    26 -> updateBoxConfig(boxIndex) { resetBoxStyle(it) }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }


    private fun showSensorPicker(boxIndex: Int) {
        val sensors = DashboardSensor.entries
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
                decimalPlaces = box.sensor.defaultDisplayDecimals(),
                splitValueDigits = true,
                smoothingAlpha = box.sensor.defaultDisplaySmoothing(),
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
        if (value.isNaN()) "off" else fmt(value, if (unit.isBlank()) "" else "$unit")

    private fun Int.floorMod(mod: Int): Int =
        ((this % mod) + mod) % mod

    private enum class BoxAlarmLevel {
        NORMAL,
        WARNING,
        CRITICAL,
    }

    private fun alarmLevelFor(
        box: DashboardBoxConfig,
        rawValue: Float?,
        snapshot: UtcompDataSnapshot? = null,
    ): BoxAlarmLevel {
        val value = rawValue ?: return BoxAlarmLevel.NORMAL
        if (value.isNaN() || value.isInfinite()) return BoxAlarmLevel.NORMAL

        oilPressureBoostAlarmLevel(box, value, snapshot).let { oilLevel ->
            if (oilLevel != BoxAlarmLevel.NORMAL) return oilLevel
        }

        val skipGenericLowAlarm = box.sensor == DashboardSensor.OIL_PRESSURE && box.oilPressureBoostAlarm

        if (
            (!skipGenericLowAlarm && thresholdHitLow(value, box.criticalLow)) ||
            thresholdHitHigh(value, box.criticalHigh)
        ) {
            return BoxAlarmLevel.CRITICAL
        }
        if (
            (!skipGenericLowAlarm && thresholdHitLow(value, box.warningLow)) ||
            thresholdHitHigh(value, box.warningHigh)
        ) {
            return BoxAlarmLevel.WARNING
        }
        return BoxAlarmLevel.NORMAL
    }


    private fun oilPressureBoostAlarmLevel(
        box: DashboardBoxConfig,
        oilPressureBar: Float,
        snapshot: UtcompDataSnapshot?,
    ): BoxAlarmLevel {
        if (box.sensor != DashboardSensor.OIL_PRESSURE) return BoxAlarmLevel.NORMAL
        if (!box.oilPressureBoostAlarm) return BoxAlarmLevel.NORMAL
        if (oilPressureBar.isNaN() || oilPressureBar.isInfinite()) return BoxAlarmLevel.NORMAL

        val boostBar = snapshot?.bar1 ?: return BoxAlarmLevel.NORMAL
        if (boostBar.isNaN() || boostBar.isInfinite()) return BoxAlarmLevel.NORMAL
        if (box.oilPressureBoostArmBar.isNaN() || boostBar < box.oilPressureBoostArmBar) {
            return BoxAlarmLevel.NORMAL
        }

        if (!box.criticalLow.isNaN() && oilPressureBar <= box.criticalLow) {
            return BoxAlarmLevel.CRITICAL
        }
        if (!box.warningLow.isNaN() && oilPressureBar <= box.warningLow) {
            return BoxAlarmLevel.WARNING
        }

        return BoxAlarmLevel.NORMAL
    }


    private fun oilPressureBoostAlarmSummary(box: DashboardBoxConfig): String {
        if (box.sensor != DashboardSensor.OIL_PRESSURE) return "not applicable"
        if (!box.oilPressureBoostAlarm) return "disabled"

        return "armed >= ${trimFloat(box.oilPressureBoostArmBar)} BAR boost, " +
            "warn low <= ${trimFloat(box.warningLow)} BAR, " +
            "crit low <= ${trimFloat(box.criticalLow)} BAR"
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
            warningLow = if (sensor == DashboardSensor.OIL_PRESSURE) defaultOilPressureWarningBar(sensor) else Float.NaN,
            criticalLow = if (sensor == DashboardSensor.OIL_PRESSURE) defaultOilPressureCriticalBar(sensor) else Float.NaN,
            oilPressureBoostAlarm = sensor == DashboardSensor.OIL_PRESSURE,
            oilPressureBoostArmBar = defaultOilPressureBoostArmBar(sensor),
            oilPressureWarningBar = defaultOilPressureWarningBar(sensor),
            oilPressureCriticalBar = defaultOilPressureCriticalBar(sensor),
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
        renderRalliartFancyPage(s)
    }


    private data class FancySensorSlot(
        val sensor: DashboardSensor,
        val boxIndex: Int,
        val box: DashboardBoxConfig,
    )

    private fun renderRalliartFancyPage(snapshot: UtcompDataSnapshot) {
        val boostSlot = fancySensorSlot(DashboardSensor.BOOST)
        val afrSlot = fancySensorSlot(DashboardSensor.AFR)
        val oilPressureSlot = fancySensorSlot(DashboardSensor.OIL_PRESSURE)
        val oilTempSlot = fancySensorSlot(DashboardSensor.OIL_TEMP)

        val binding = ensureRalliartBinding(
            boostSlot = boostSlot,
            afrSlot = afrSlot,
            oilPressureSlot = oilPressureSlot,
            oilTempSlot = oilTempSlot,
        )

        val (_, shownBoost) = fancyDisplayValue(boostSlot, snapshot)
        val (_, shownAfr) = fancyDisplayValue(afrSlot, snapshot)
        val (rawOilPressure, shownOilPressure) = fancyDisplayValue(oilPressureSlot, snapshot)
        val (rawOilTemp, shownOilTemp) = fancyDisplayValue(oilTempSlot, snapshot)

        val boost = shownBoost ?: Float.NaN
        val afr = shownAfr ?: Float.NaN
        val oilPressure = shownOilPressure ?: Float.NaN
        val oilTemp = shownOilTemp ?: Float.NaN

        val (targetMin, targetMax) = afrTargetRangeForBoost(boost)
        val afrColor = when {
            !afr.isFinite() -> Color.rgb(218, 222, 228)
            afr in targetMin..targetMax -> Color.rgb(102, 214, 132)
            afr >= targetMin - 0.45f && afr <= targetMax + 0.45f -> Color.rgb(255, 188, 72)
            else -> Color.rgb(255, 98, 98)
        }

        val boostColor = if (boost.isFinite() && boost > 2.0f) {
            Color.rgb(255, 196, 72)
        } else {
            boostSlot.box.valueColor
        }

        val oilPressureAlarm = alarmLevelFor(oilPressureSlot.box, rawOilPressure, snapshot)
        val oilPressureColor = boxValueColor(oilPressureSlot.box, oilPressureAlarm)
        val oilTempAlarm = alarmLevelFor(oilTempSlot.box, rawOilTemp, snapshot)
        val oilTempColor = if (oilTempAlarm == BoxAlarmLevel.NORMAL) {
            Color.rgb(255, 226, 226)
        } else {
            boxValueColor(oilTempSlot.box, oilTempAlarm)
        }

        val outside = formatTopValue(snapshot.temperatureDsA, "°C")
        val inside = formatTopValue(snapshot.temperatureDsB, "°C")
        val battery = formatTopValue(snapshot.adcInValCh0, " V")
        binding.headerText.text = "OUT $outside   |   IN $inside   |   $battery   |   ${clockFmt.format(Date())}"

        updateRalliartValue(binding.boostValueText, boost, boostSlot, boostColor)
        binding.boostNeedle.currentValue = boost.coerceIn(-1.0f, 2.0f)
        updateRalliartMinMax(
            views = binding.boostMinMax,
            key = boostSlot.sensor.label,
            value = boost,
            slot = boostSlot,
        )

        updateRalliartValue(binding.afrValueText, afr, afrSlot, afrColor)
        (binding.afrMarker.background as? GradientDrawable)?.setColor(afrColor)
        binding.afr = afr
        binding.afrTargetMin = targetMin
        binding.afrTargetMax = targetMax
        binding.updateAfrOverlay()

        updateRalliartAlarmFill(
            binding.oilPressureAlarmFill,
            ralliartAlarmOverlayColor(oilPressureSlot.box, oilPressureAlarm),
        )
        updateRalliartValue(
            binding.oilPressureValueText,
            oilPressure,
            oilPressureSlot,
            oilPressureColor,
        )
        updateRalliartMinMax(
            views = binding.oilPressureMinMax,
            key = oilPressureSlot.sensor.label,
            value = oilPressure,
            slot = oilPressureSlot,
        )

        updateRalliartAlarmFill(
            binding.oilTempAlarmFill,
            ralliartAlarmOverlayColor(oilTempSlot.box, oilTempAlarm),
        )
        updateRalliartValue(binding.oilTempValueText, oilTemp, oilTempSlot, oilTempColor)
        updateRalliartMinMax(
            views = binding.oilTempMinMax,
            key = oilTempSlot.sensor.label,
            value = oilTemp,
            slot = oilTempSlot,
        )
    }

    private fun ensureRalliartBinding(
        boostSlot: FancySensorSlot,
        afrSlot: FancySensorSlot,
        oilPressureSlot: FancySensorSlot,
        oilTempSlot: FancySensorSlot,
    ): RalliartDashboardBinding {
        val key = RalliartLayoutKey(page, currentPageConfig())
        ralliartBinding?.let { current ->
            if (current.key == key && current.root.parent === dashboardRoot) return current
        }

        val binding = buildRalliartDashboard(
            key = key,
            boostSlot = boostSlot,
            afrSlot = afrSlot,
            oilPressureSlot = oilPressureSlot,
            oilTempSlot = oilTempSlot,
        )
        dashboardRoot.removeAllViews()
        dashboardRoot.addView(
            binding.root,
            LinearLayout.LayoutParams(RALLIART_CANVAS_WIDTH, RALLIART_CANVAS_HEIGHT),
        )
        ralliartBinding = binding
        return binding
    }

    private fun buildRalliartDashboard(
        key: RalliartLayoutKey,
        boostSlot: FancySensorSlot,
        afrSlot: FancySensorSlot,
        oilPressureSlot: FancySensorSlot,
        oilTempSlot: FancySensorSlot,
    ): RalliartDashboardBinding {
        clearRalliartViewportPadding()

        val root = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.ralliart_dashboard_static)
            installDashboardSwipeHandler()
            clipToPadding = false
            clipChildren = false
        }

        val oilPressureAlarmFill = createRalliartAlarmFill().also {
            root.addView(it, ralliartLayoutParams(RALLIART_OIL_PRESSURE_HIT_BOX))
        }
        val oilTempAlarmFill = createRalliartAlarmFill().also {
            root.addView(it, ralliartLayoutParams(RALLIART_OIL_TEMP_HIT_BOX))
        }

        val headerText = TextView(this).apply {
            textSize = 16f
            includeFontPadding = false
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            isSingleLine = true
            setTextColor(Color.rgb(236, 238, 244))
        }.also { root.addView(it, ralliartLayoutParams(RALLIART_HEADER_STATUS_BOX)) }

        addRalliartHitZone(root, RALLIART_BOOST_HIT_BOX, boostSlot, boostSlot.sensor.label)
        addRalliartHitZone(
            root,
            RALLIART_AFR_HIT_BOX,
            afrSlot,
            if (afrSlot.box.showMinMax) afrSlot.sensor.label else null,
        )
        addRalliartHitZone(
            root,
            RALLIART_OIL_PRESSURE_HIT_BOX,
            oilPressureSlot,
            oilPressureSlot.sensor.label,
        )
        addRalliartHitZone(
            root,
            RALLIART_OIL_TEMP_HIT_BOX,
            oilTempSlot,
            oilTempSlot.sensor.label,
        )

        val boostValueText = createRalliartValueText(70f).also {
            root.addView(it, ralliartLayoutParams(RALLIART_BOOST_VALUE_BOX))
        }
        val boostNeedle = RalliartBoostNeedleView(this).apply {
            minValue = -1.0f
            maxValue = 2.0f
            warningValue = 2.0f
            showDebugGuides = false
        }.also { root.addView(it, ralliartLayoutParams(RALLIART_BOOST_NEEDLE_BOX)) }
        val boostMinMax = createRalliartMinMaxViews(boostSlot).also { views ->
            if (views != null) root.addView(views.container, ralliartLayoutParams(RALLIART_BOOST_MIN_MAX_BOX))
        }

        val afrValueText = createRalliartValueText(66f).also {
            root.addView(it, ralliartLayoutParams(RALLIART_AFR_VALUE_BOX))
        }
        root.addView(RalliartAfrDebugBarView(this).apply {
            minValue = 10.0f
            maxValue = 20.0f
            showDebugGuides = false
        }, ralliartLayoutParams(RALLIART_AFR_DEBUG_GUIDE_BOX))

        val afrOverlay = FrameLayout(this)
        val afrTargetBand = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.argb(150, 76, 170, 88))
                cornerRadius = 4f
            }
        }
        val afrMarker = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(218, 222, 228))
                cornerRadius = 5f
            }
        }
        afrOverlay.addView(afrTargetBand, FrameLayout.LayoutParams(4, FrameLayout.LayoutParams.MATCH_PARENT))
        afrOverlay.addView(afrMarker, FrameLayout.LayoutParams(8, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(afrOverlay, ralliartLayoutParams(RALLIART_AFR_LIVE_BAR_BOX))

        val oilPressureValueText = createRalliartValueText(58f).also {
            root.addView(it, ralliartLayoutParams(RALLIART_OIL_PRESSURE_VALUE_BOX))
        }
        val oilPressureMinMax = createRalliartMinMaxViews(oilPressureSlot).also { views ->
            if (views != null) {
                root.addView(views.container, ralliartLayoutParams(RALLIART_OIL_PRESSURE_MIN_MAX_BOX))
            }
        }

        val oilTempValueText = createRalliartValueText(58f).also {
            root.addView(it, ralliartLayoutParams(RALLIART_OIL_TEMP_VALUE_BOX))
        }
        val oilTempMinMax = createRalliartMinMaxViews(oilTempSlot).also { views ->
            if (views != null) root.addView(views.container, ralliartLayoutParams(RALLIART_OIL_TEMP_MIN_MAX_BOX))
        }

        val binding = RalliartDashboardBinding(
            key = key,
            root = root,
            headerText = headerText,
            boostValueText = boostValueText,
            boostNeedle = boostNeedle,
            boostMinMax = boostMinMax,
            afrValueText = afrValueText,
            afrOverlay = afrOverlay,
            afrTargetBand = afrTargetBand,
            afrMarker = afrMarker,
            oilPressureAlarmFill = oilPressureAlarmFill,
            oilPressureValueText = oilPressureValueText,
            oilPressureMinMax = oilPressureMinMax,
            oilTempAlarmFill = oilTempAlarmFill,
            oilTempValueText = oilTempValueText,
            oilTempMinMax = oilTempMinMax,
        )
        afrOverlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            binding.updateAfrOverlay()
        }
        return binding
    }

    private fun clearRalliartViewportPadding() {
        dashboardRoot.apply {
            setPadding(0, 0, 0, 0)
            clipToPadding = false
            clipChildren = false
            gravity = Gravity.NO_GRAVITY
            minimumWidth = RALLIART_CANVAS_WIDTH
            minimumHeight = RALLIART_CANVAS_HEIGHT
        }

        var parentView: android.view.ViewParent? = dashboardRoot.parent
        repeat(6) {
            val group = parentView as? ViewGroup ?: return@repeat
            group.setPadding(0, 0, 0, 0)
            group.clipToPadding = false
            group.clipChildren = false
            parentView = group.parent
        }
    }

    private fun addRalliartHitZone(
        root: FrameLayout,
        box: IntArray,
        slot: FancySensorSlot,
        minMaxKey: String?,
    ) {
        val hitZone = ClickableLinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            installDashboardSwipeHandler()
        }
        attachFancyCardActions(hitZone, slot, minMaxKey)
        root.addView(hitZone, ralliartLayoutParams(box))
    }

    private fun createRalliartValueText(size: Float): TextView =
        TextView(this).apply {
            textSize = size
            includeFontPadding = false
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

    private fun createRalliartAlarmFill(): View =
        View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = 8f
            }
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }

    private fun createRalliartMinMaxViews(slot: FancySensorSlot): RalliartMinMaxViews? {
        if (!slot.box.showMinMax) return null

        val maxText = TextView(this).apply {
            textSize = 16f
            setTextColor(slot.box.maxColor)
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        val minText = TextView(this).apply {
            textSize = 16f
            setTextColor(slot.box.minColor)
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 2, 0, 2)
            isClickable = false
            isFocusable = false
            visibility = View.GONE
            addView(maxText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(minText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        return RalliartMinMaxViews(container, maxText, minText)
    }

    private fun updateRalliartValue(
        textView: TextView,
        value: Float,
        slot: FancySensorSlot,
        color: Int,
    ) {
        val text = styledValueText(
            formatBoxValue(value, slot.box.decimalPlaces),
            slot.box.splitValueDigits,
        )
        if (!android.text.TextUtils.equals(textView.text, text)) textView.text = text
        if (textView.currentTextColor != color) textView.setTextColor(color)
    }

    private fun updateRalliartMinMax(
        views: RalliartMinMaxViews?,
        key: String,
        value: Float,
        slot: FancySensorSlot,
    ) {
        if (views == null || !value.isFinite()) {
            views?.container?.visibility = View.GONE
            return
        }

        val stats = trackMinMax(key, value)
        val visible = shouldShowMinMax(key)
        views.container.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return

        val maxText = formatBoxValue(stats.max, slot.box.decimalPlaces)
        val minText = formatBoxValue(stats.min, slot.box.decimalPlaces)
        if (views.maxText.text.toString() != maxText) views.maxText.text = maxText
        if (views.minText.text.toString() != minText) views.minText.text = minText
    }

    private fun updateRalliartAlarmFill(view: View, color: Int?) {
        view.visibility = if (color == null) View.GONE else View.VISIBLE
        if (color != null) (view.background as? GradientDrawable)?.setColor(color)
    }

    private fun ralliartAlarmOverlayColor(
        box: DashboardBoxConfig,
        alarmLevel: BoxAlarmLevel,
    ): Int? {
        if (!box.alarmColorsBackground) return null
        val source = when (alarmLevel) {
            BoxAlarmLevel.CRITICAL -> box.criticalColor
            BoxAlarmLevel.WARNING -> box.warningColor
            BoxAlarmLevel.NORMAL -> return null
        }
        return Color.argb(210, Color.red(source), Color.green(source), Color.blue(source))
    }

    private fun formatTopValue(value: Float, suffix: String): String =
        if (value.isFinite()) formatBoxValue(value, 1) + suffix else "--$suffix"

    private fun ralliartLayoutParams(box: IntArray): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(box[2], box[3]).apply {
            leftMargin = box[0]
            topMargin = box[1]
        }






    private fun fancySensorSlot(sensor: DashboardSensor): FancySensorSlot {
        val pageConfig = currentPageConfig()
        val currentIndex = pageConfig.boxes.indexOfFirst { it.sensor == sensor }
        if (currentIndex >= 0) {
            return FancySensorSlot(sensor, currentIndex, pageConfig.boxes[currentIndex])
        }

        val fallbackBox = DefaultDashboardPages.race2x2.boxes.first { it.sensor == sensor }
        return FancySensorSlot(sensor, -1, fallbackBox)
    }

    private fun fancyDisplayValue(slot: FancySensorSlot, snapshot: UtcompDataSnapshot): Pair<Float?, Float?> {
        val rawValue = slot.sensor.readValue(snapshot)
        val renderIndex = if (slot.boxIndex >= 0) slot.boxIndex else 100 + slot.sensor.ordinal
        val displayValue = displayValueForBox(slot.box, renderIndex, rawValue)
        return rawValue to (displayValue ?: rawValue)
    }

    private fun attachFancyCardActions(card: View, slot: FancySensorSlot, minMaxKey: String?) {
        card.installDashboardSwipeHandler()
        if (slot.boxIndex >= 0) {
            attachDashboardBoxActions(card, slot.boxIndex, minMaxKey)
            return
        }

        card.isClickable = true
        card.setOnClickListener {
            if (dashboardClickSuppressed()) return@setOnClickListener
            handleDashboardBoxTapForChrome(minMaxKey, "fancy:${slot.sensor.name}")
        }
        card.setOnLongClickListener {
            cancelPendingDashboardSingleTap()
            lastDashboardBoxTapMs = 0L
            revealTopBarChrome(autoHide = true)
            true
        }
    }

    private fun lerpFloat(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction.coerceIn(0f, 1f)

    private fun afrTargetRangeForBoost(boostBar: Float): Pair<Float, Float> {
        if (boostBar.isNaN() || boostBar.isInfinite()) {
            return 12.8f to 14.2f
        }

        val boost = boostBar.coerceIn(-1.0f, 2.0f)

        return when {
            boost <= 0.20f -> {
                13.2f to 15.0f
            }
            boost < 2.0f -> {
                val fraction = ((boost - 0.20f) / 1.80f).coerceIn(0f, 1f)
                val low = lerpFloat(12.4f, 10.2f, fraction)
                val high = lerpFloat(14.2f, 12.5f, fraction)
                low to high
            }
            else -> {
                10.2f to 12.5f
            }
        }
    }

    private fun renderSimple(snapshot: UtcompDataSnapshot) {
        val binding = ensureSimpleDashboardBinding()
        binding.cards.forEach { updateSimpleCard(it, snapshot) }
    }

    private fun ensureSimpleDashboardBinding(): SimpleDashboardBinding {
        val pageConfig = currentPageConfig()
        val key = SimpleLayoutKey(page, pageConfig)
        simpleBinding?.let { current ->
            if (current.key == key && current.root.parent === dashboardRoot) return current
        }

        dashboardRoot.apply {
            setBackgroundColor(Color.BLACK)
            setPadding(0, 0, 0, 0)
            clipToPadding = false
            clipChildren = false
            gravity = Gravity.NO_GRAVITY
        }

        var parentView: android.view.ViewParent? = dashboardRoot.parent
        repeat(4) {
            val group = parentView as? ViewGroup ?: return@repeat
            group.setBackgroundColor(Color.BLACK)
            group.setPadding(0, 0, 0, 0)
            group.clipToPadding = false
            group.clipChildren = false
            parentView = group.parent
        }

        val cardBindings = pageConfig.boxes
            .mapIndexed { index, box -> index to box }
            .sortedWith(
                compareBy<Pair<Int, DashboardBoxConfig>> { it.second.row }
                    .thenBy { it.second.column },
            )
            .map { (boxIndex, box) -> buildSimpleCard(boxIndex, box) }

        val root = buildSimpleGrid(pageConfig.rows, pageConfig.columns, cardBindings.map { it.card })
        dashboardRoot.removeAllViews()
        dashboardRoot.addView(
            root,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        ralliartBinding = null
        return SimpleDashboardBinding(key, root, cardBindings).also { simpleBinding = it }
    }

    private fun buildSimpleGrid(
        rows: Int,
        columns: Int,
        cards: List<LinearLayout>,
    ): LinearLayout {
        val safeRows = rows.coerceAtLeast(1)
        val safeColumns = columns.coerceAtLeast(1)
        val totalCells = safeRows * safeColumns
        val cells = cards.take(totalCells)

        return ClickableLinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.BLACK)

            for (rowIndex in 0 until safeRows) {
                val row = ClickableLinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, 0)
                    setBackgroundColor(Color.BLACK)
                }

                for (columnIndex in 0 until safeColumns) {
                    val index = rowIndex * safeColumns + columnIndex
                    val cell = cells.getOrNull(index) ?: ClickableLinearLayout(this@MainActivity)
                    cell.minimumWidth = 0
                    cell.minimumHeight = 0
                    cell.installDashboardSwipeHandler()
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

                addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    ),
                )
            }
        }
    }

    private fun buildSimpleCard(
        boxIndex: Int,
        box: DashboardBoxConfig,
    ): SimpleCardBinding {
        val sensor = box.sensor
        val minMaxKey = if (sensor == DashboardSensor.TIME || !box.showMinMax) null else sensor.label
        val background = GradientDrawable().apply {
            setColor(box.backgroundColor)
            cornerRadius = 0f
            setStroke(2, Color.rgb(255, 110, 36))
        }
        val card = ClickableLinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            contentDescription = sensor.label
            this.background = background
            setPadding(8, 4, 8, 4)
            clipToPadding = false
            clipChildren = false
        }

        val safeValueScale = box.valueScale.coerceIn(0.25f, 4.0f)
        val safeIconScale = box.iconScale.coerceIn(0.25f, 4.0f)
        val safeIconValueGapScale = box.iconValueGapScale.coerceIn(0.25f, 4.0f)
        val iconSize = (40f * safeIconScale).toInt().coerceAtLeast(18)
        val iconGapPx = (8f * safeValueScale * safeIconValueGapScale).coerceAtLeast(2f)
        val unitGapPx = (4f * safeValueScale).toInt().coerceAtLeast(1)
        val minMaxTextSize = 11.5f * safeValueScale
        val minMaxPaddingY = (2f * safeValueScale).toInt().coerceAtLeast(2)
        val minMaxWidthPx = (58f * safeValueScale).toInt().coerceAtLeast(50)
        val minMaxCornerInsetPx = (5f * safeValueScale).coerceAtLeast(4f)

        var iconView: View? = null
        var unitTextView: TextView? = null
        var maxStatView: TextView? = null
        var minStatView: TextView? = null

        val content = FrameLayout(this).apply {
            clipToPadding = false
            clipChildren = false
        }
        val valueLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            isBaselineAligned = false
            clipToPadding = false
            clipChildren = false
        }
        val valueTextView = TextView(this).apply {
            text = "--"
            textSize = 29f * safeValueScale
            setTextColor(box.valueColor)
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        valueLine.addView(
            valueTextView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.BOTTOM },
        )

        if (box.showUnit && sensor.unit.isNotBlank()) {
            unitTextView = TextView(this).apply {
                text = sensor.unit
                textSize = 10.5f * safeValueScale
                setTextColor(box.unitColor)
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                includeFontPadding = false
            }.also { unitText ->
                valueLine.addView(
                    unitText,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        gravity = Gravity.BOTTOM
                        leftMargin = unitGapPx
                    },
                )
            }
        }

        content.addView(
            valueLine,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        if (box.showIcon && sensor != DashboardSensor.TIME) {
            val iconResource = iconDrawableResourceId(sensor.iconResourceName)
            iconView = if (iconResource != 0) {
                ImageView(this).apply {
                    setImageResource(iconResource)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    alpha = 0.95f
                }
            } else {
                val fallbackIcon = fallbackIconFor(sensor)
                TextView(this).apply {
                    text = fallbackIcon
                    textSize = if (fallbackIcon.length > 2) {
                        13f * safeIconScale
                    } else {
                        22f * safeIconScale
                    }
                    gravity = Gravity.CENTER
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    setTextColor(box.unitColor)
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                }
            }
            content.addView(iconView, FrameLayout.LayoutParams(iconSize, iconSize))
        }

        if (minMaxKey != null) {
            maxStatView = TextView(this).apply {
                textSize = minMaxTextSize
                setTextColor(box.maxColor)
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                visibility = View.GONE
                setPadding(0, minMaxPaddingY, 0, minMaxPaddingY)
            }
            minStatView = TextView(this).apply {
                textSize = minMaxTextSize
                setTextColor(box.minColor)
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                visibility = View.GONE
                setPadding(0, minMaxPaddingY, 0, minMaxPaddingY)
            }
            content.addView(
                maxStatView,
                FrameLayout.LayoutParams(
                    minMaxWidthPx,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            content.addView(
                minStatView,
                FrameLayout.LayoutParams(
                    minMaxWidthPx,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val valueBounds = android.graphics.RectF()
        val unitBounds = android.graphics.RectF()
        val scratchBounds = android.graphics.Rect()

        fun textInkBounds(textView: TextView, out: android.graphics.RectF) {
            val baseline = textView.baseline
            val rawText = textView.text?.toString().orEmpty()
            if (textView.height <= 0 || baseline < 0 || rawText.isEmpty()) {
                out.set(
                    textView.left.toFloat(),
                    textView.top.toFloat(),
                    textView.right.toFloat(),
                    textView.bottom.toFloat(),
                )
                return
            }

            textView.paint.getTextBounds(rawText, 0, rawText.length, scratchBounds)
            out.set(
                textView.left + scratchBounds.left.toFloat(),
                textView.top + baseline + scratchBounds.top.toFloat(),
                textView.left + scratchBounds.right.toFloat(),
                textView.top + baseline + scratchBounds.bottom.toFloat(),
            )
        }

        fun alignSimpleContent() {
            if (content.width <= 0 || content.height <= 0 || valueLine.width <= 0 ||
                valueLine.height <= 0 || valueTextView.height <= 0
            ) return

            valueLine.translationX = 0f
            valueLine.translationY = 0f
            valueTextView.translationY = 0f
            unitTextView?.translationY = 0f

            val icon = iconView
            val iconBlockWidth = if (icon != null && icon.width > 0) {
                icon.width.toFloat() + iconGapPx
            } else {
                0f
            }
            val groupWidth = iconBlockWidth + valueLine.width
            val groupLeft = (content.width - groupWidth) / 2f
            textInkBounds(valueTextView, valueBounds)
            val valueLineTop = content.height / 2f - (valueBounds.top + valueBounds.bottom) / 2f
            valueLine.x = groupLeft + iconBlockWidth
            valueLine.y = valueLineTop
            val targetBottom = valueLine.y + valueBounds.bottom

            unitTextView?.let { unitText ->
                if (unitText.height > 0) {
                    textInkBounds(unitText, unitBounds)
                    unitText.translationY = targetBottom - (valueLine.y + unitBounds.bottom)
                }
            }

            icon?.let {
                if (it.width > 0 && it.height > 0) {
                    it.x = groupLeft
                    it.y = targetBottom - it.height
                }
            }

            val statRight = content.width - minMaxCornerInsetPx
            val statLeft = (statRight - minMaxWidthPx).coerceAtLeast(minMaxCornerInsetPx)
            maxStatView?.let { maxView ->
                if (maxView.visibility != View.GONE && maxView.width > 0 && maxView.height > 0) {
                    maxView.x = statLeft
                    maxView.y = minMaxCornerInsetPx
                }
            }
            minStatView?.let { minView ->
                if (minView.visibility != View.GONE && minView.width > 0 && minView.height > 0) {
                    minView.x = statLeft
                    minView.y = (content.height - minMaxCornerInsetPx - minView.height)
                        .coerceAtLeast(minMaxCornerInsetPx)
                }
            }
        }

        val alignListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            alignSimpleContent()
        }
        content.addOnLayoutChangeListener(alignListener)
        valueLine.addOnLayoutChangeListener(alignListener)
        valueTextView.addOnLayoutChangeListener(alignListener)
        unitTextView?.addOnLayoutChangeListener(alignListener)
        iconView?.addOnLayoutChangeListener(alignListener)
        maxStatView?.addOnLayoutChangeListener(alignListener)
        minStatView?.addOnLayoutChangeListener(alignListener)
        content.post { alignSimpleContent() }

        card.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        attachDashboardBoxActions(card, boxIndex, minMaxKey)

        return SimpleCardBinding(
            boxIndex = boxIndex,
            box = box,
            card = card,
            background = background,
            valueText = valueTextView,
            unitText = unitTextView,
            maxText = maxStatView,
            minText = minStatView,
            minMaxKey = minMaxKey,
        )
    }

    private fun updateSimpleCard(
        binding: SimpleCardBinding,
        snapshot: UtcompDataSnapshot,
    ) {
        val box = binding.box
        val sensor = box.sensor
        val rawValue = sensor.readValue(snapshot)
        val displayValue = displayValueForBox(box, binding.boxIndex, rawValue)
        val formattedValue = if (sensor == DashboardSensor.TIME) {
            clockFmt.format(Date())
        } else {
            formatBoxValue(displayValue ?: rawValue, box.decimalPlaces)
        }

        if (binding.valueText.text.toString() != formattedValue) {
            binding.valueText.text = styledValueText(formattedValue, box.splitValueDigits)
        }

        val alarmLevel = alarmLevelFor(box, rawValue, snapshot)
        val valueColor = boxValueColor(box, alarmLevel)
        binding.background.setColor(boxBackgroundColor(box, alarmLevel))
        if (binding.valueText.currentTextColor != valueColor) {
            binding.valueText.setTextColor(valueColor)
        }
        val effectiveUnitColor = if (alarmLevel == BoxAlarmLevel.NORMAL) box.unitColor else valueColor
        binding.unitText?.let { unitText ->
            if (unitText.currentTextColor != effectiveUnitColor) unitText.setTextColor(effectiveUnitColor)
        }
        binding.card.alpha = if (editMode) 0.92f else 1.0f

        val minMaxKey = binding.minMaxKey
        val value = rawValue
        val stats = if (minMaxKey != null && value != null && value.isFinite()) {
            trackMinMax(minMaxKey, value)
        } else {
            null
        }
        val showStats = stats != null && minMaxKey != null && shouldShowMinMax(minMaxKey)
        binding.maxText?.visibility = if (showStats) View.VISIBLE else View.GONE
        binding.minText?.visibility = if (showStats) View.VISIBLE else View.GONE
        if (showStats && stats != null) {
            val maxText = formatBoxValue(stats.max, box.decimalPlaces)
            val minText = formatBoxValue(stats.min, box.decimalPlaces)
            if (binding.maxText?.text?.toString() != maxText) binding.maxText?.text = maxText
            if (binding.minText?.text?.toString() != minText) binding.minText?.text = minText
        }
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

    private fun View.installDashboardSwipeHandler() {
        isClickable = true
        setOnTouchListener { _, event ->
                handlePageSwipe(event)
            }
    }

    private fun iconDrawableResourceId(resourceName: String?): Int =
        when (resourceName) {
            "ic_utcomp_battery_48dp" -> R.drawable.ic_rcomp_accu_48dp
            "ic_rcomp_accu_48dp" -> R.drawable.ic_rcomp_accu_48dp
            "ic_rcomp_afr_48dp" -> R.drawable.ic_rcomp_afr_48dp
            "ic_rcomp_boost_48dp" -> R.drawable.ic_rcomp_boost_48dp
            "ic_rcomp_inside_temp_48dp" -> R.drawable.ic_rcomp_inside_temp_48dp
            "ic_rcomp_oilpres_48dp" -> R.drawable.ic_rcomp_oilpres_48dp
            "ic_rcomp_oiltemp_48dp" -> R.drawable.ic_rcomp_oiltemp_48dp
            "ic_rcomp_outside_temp_48dp" -> R.drawable.ic_rcomp_outside_temp_48dp
            "ic_rcomp_timer_trip_48dp" -> R.drawable.ic_rcomp_timer_trip_48dp
            "ic_rcomp_volts_48dp" -> R.drawable.ic_rcomp_accu_48dp
            "ic_rcomp_afr1_48dp" -> R.drawable.ic_rcomp_afr_48dp
            "ic_rcomp_afr2_48dp" -> R.drawable.ic_rcomp_afr_48dp
            "ic_rcomp_bar_48dp" -> R.drawable.ic_rcomp_boost_48dp
            "ic_rcomp_barvac_48dp" -> R.drawable.ic_rcomp_boost_48dp
            "ic_rcomp_outside_temp_cold_48dp" -> R.drawable.ic_rcomp_outside_temp_48dp
            "ic_rcomp_pressure_general_48dp" -> R.drawable.ic_rcomp_oilpres_48dp
            "ic_rcomp_u1_temp_48dp" -> R.drawable.ic_rcomp_oiltemp_48dp
            "ic_rcomp_u2_temp_48dp" -> R.drawable.ic_rcomp_oiltemp_48dp
            "ic_rcomp_u3_temp_48dp" -> R.drawable.ic_rcomp_oiltemp_48dp
            "ic_rcomp_u4_temp_48dp" -> R.drawable.ic_rcomp_oiltemp_48dp
            else -> 0
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
