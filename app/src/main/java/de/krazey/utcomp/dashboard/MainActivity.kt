package de.krazey.utcomp.dashboard

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.content.SharedPreferences
import android.text.style.RelativeSizeSpan
import android.text.Spanned
import android.text.SpannableString
import de.krazey.utcomp.dashboard.dashboard.DashboardConfigJson
import de.krazey.utcomp.dashboard.dashboard.DashboardEditorController
import de.krazey.utcomp.dashboard.dashboard.DashboardPageEditorController
import de.krazey.utcomp.dashboard.dashboard.RalliartHeaderEditorController
import de.krazey.utcomp.dashboard.dashboard.DefaultDashboardPages
import de.krazey.utcomp.dashboard.dashboard.DashboardSensor
import de.krazey.utcomp.dashboard.dashboard.DashboardPageConfig
import de.krazey.utcomp.dashboard.dashboard.DashboardBoxConfig
import de.krazey.utcomp.dashboard.logging.CsvLogController
import de.krazey.utcomp.dashboard.logging.UtcompCsvLogger
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import de.krazey.utcomp.dashboard.protocol.TransmitterConstants
import de.krazey.utcomp.dashboard.protocol.TransmitterPacket
import de.krazey.utcomp.dashboard.simulation.SimulationEngine
import de.krazey.utcomp.dashboard.transport.UsbRecoveryPolicy
import de.krazey.utcomp.dashboard.transport.UtcompUsbTransport
import de.krazey.utcomp.dashboard.utcomp.UtcompDataSnapshot
import de.krazey.utcomp.dashboard.utcomp.UtcompDecoder
import de.krazey.utcomp.dashboard.utcomp.UtcompDeviceConfig
import de.krazey.utcomp.dashboard.utcomp.pretty
import de.krazey.utcomp.dashboard.dashboard.render.DashboardAlarmLevel
import de.krazey.utcomp.dashboard.dashboard.render.DashboardMergeVisualState
import de.krazey.utcomp.dashboard.dashboard.render.DashboardMinMax
import de.krazey.utcomp.dashboard.dashboard.render.DashboardRenderHost
import de.krazey.utcomp.dashboard.dashboard.render.RalliartDashboardRenderer
import de.krazey.utcomp.dashboard.dashboard.render.SimpleDashboardRenderer
import de.krazey.utcomp.dashboard.util.fixed
import de.krazey.utcomp.dashboard.view.DashboardSwipeScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity(), DashboardRenderHost {
    private class ClickableLinearLayout(context: Context) : LinearLayout(context) {
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private class MaxHeightScrollView(context: Context) : ScrollView(context) {
        var maxHeightFraction: Float = 1f

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val displayCap =
                (resources.displayMetrics.heightPixels * maxHeightFraction.coerceIn(0.1f, 1f))
                    .toInt()
            val availableHeight = when (MeasureSpec.getMode(heightMeasureSpec)) {
                MeasureSpec.UNSPECIFIED -> displayCap
                else -> minOf(MeasureSpec.getSize(heightMeasureSpec), displayCap)
            }
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(availableHeight, MeasureSpec.AT_MOST),
            )
        }
    }

    private companion object {
        private const val USB_FAST_POLL_MS = 50L
        private const val USB_SLOW_POLL_MS = 1000L
        private const val DASHBOARD_RENDER_MS = 125L
        private const val DASHBOARD_TOUCH_RENDER_GRACE_MS = 220L
        private const val DASHBOARD_TOUCH_DEFER_RETRY_MS = 80L
        private const val DASHBOARD_DOUBLE_TAP_MS = 320L
        private const val DASHBOARD_SWIPE_CLICK_SUPPRESS_MS = 280L
        private val MENU_BORDER_COLOR = Color.rgb(38, 78, 104)
        private val MENU_PANEL_COLOR = Color.rgb(15, 18, 24)

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

    private data class MergeSession(
        val pageId: String,
        val sourceBoxIndex: Int,
        val targetCells: Set<Int>,
    )

    private lateinit var usb: UtcompUsbTransport
    private lateinit var statusText: TextView
    private lateinit var controlsPanel: MaxHeightScrollView
    private lateinit var dashboardRoot: LinearLayout
    private lateinit var logTitleText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var csvLogger: UtcompCsvLogger
    private lateinit var csvLogController: CsvLogController
    private lateinit var dashboardEditorController: DashboardEditorController
    private lateinit var dashboardPageEditorController: DashboardPageEditorController
    private lateinit var ralliartHeaderEditorController: RalliartHeaderEditorController
    private lateinit var ralliartRenderer: RalliartDashboardRenderer
    private lateinit var simpleRenderer: SimpleDashboardRenderer
    private var dataLogTreeUri: Uri? = null

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.US)
    private val clockDate = Date()
    private var cachedClockMinute = Long.MIN_VALUE
    private var cachedClockText = ""
    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var dataMode = DataMode.USB
    private var simTestMode = SimTestMode.OFF
    private val simTickerHandler = Handler(Looper.getMainLooper())
    private val usbPollThread = HandlerThread("utcomp-usb-poll").apply { start() }
    private val autoUsbHandler = Handler(usbPollThread.looper)
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
            if (dataMode != DataMode.USB || !autoRefresh) return

            if (!connected) {
                runCatching { usb.requestPermissionAndConnect(logDiscovery = false) }
                    .onFailure { appendLog("USB reconnect attempt failed: ${it.message}") }
                if (dataMode == DataMode.USB && autoRefresh && !connected) {
                    autoUsbHandler.postDelayed(
                        this,
                        UsbRecoveryPolicy.NORMAL_RECONNECT_DELAY_MS,
                    )
                }
                return
            }

            val nowMs = SystemClock.elapsedRealtime()
            runCatching {
                requestFastLiveData()

                if (nowMs - lastSlowUsbRequestMs >= USB_SLOW_POLL_MS) {
                    lastSlowUsbRequestMs = nowMs
                    requestSlowLiveData()
                }
            }.onFailure { appendLog("USB polling failed: ${it.message}") }

            if (dataMode == DataMode.USB && autoRefresh) {
                autoUsbHandler.postDelayed(this, USB_FAST_POLL_MS)
            }
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
    private var lastRenderedUiMode: UiMode? = null
    private var currentPageIndex = 0
    @Volatile
    private var connected = false

    @Volatile
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
    private var ralliartPageConfig = DefaultDashboardPages.ralliart
    private var mergeSession: MergeSession? = null
    private val minMaxByScope = LinkedHashMap<String, DashboardMinMax>()
    private val smoothedValuesByBox = LinkedHashMap<Int, Float>()
    private var activeMinMaxCard: String? = null
    private var minMaxHideRunnable: Runnable? = null

    private var debugTextView: TextView? = null

    private val simRunnable = object : Runnable {
        override fun run() {
            if (simTestMode != SimTestMode.OFF || dataMode == DataMode.SIM) {
                SimulationEngine.update(UtcompDecoder.snapshot, simTick++)
                scheduleDashboardRender(delayMs = 0L)
                handler.postDelayed(this, 500)
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
        csvLogController = CsvLogController(
            activity = this,
            logger = csvLogger,
            appendLog = ::appendLog,
            savedTreeUri = { dataLogTreeUri },
            onTreeUriChanged = { uri ->
                dataLogTreeUri = uri
                saveDashboardPrefs()
            },
            currentPageConfig = ::currentPageConfig,
            dashboardPages = { dashboardPages },
        )
        dashboardEditorController = DashboardEditorController(
            context = this,
            currentPageConfig = ::currentPageConfig,
            updateCurrentPage = ::updateCurrentPage,
            updateBoxConfig = ::updateBoxConfig,
            onSmoothingChanged = { boxIndex, box ->
                smoothedValuesByBox.remove(smoothedValueKey(boxIndex, box))
            },
            isLayoutEditable = { uiMode == UiMode.SIMPLE },
            onEditPageGrid = {
                dashboardPageEditorController.showCurrentPageGridEditor()
            },
            onStartMerge = ::startMerge,
        )
        dashboardPageEditorController = DashboardPageEditorController(
            context = this,
            pages = { dashboardPages },
            currentPageIndex = { currentPageIndex },
            onPagesChanged = ::replaceDashboardPages,
        )
        ralliartHeaderEditorController = RalliartHeaderEditorController(
            context = this,
            currentPageConfig = { ralliartPageConfig },
            updateCurrentPage = ::updateCurrentPage,
        )
        usb = UtcompUsbTransport(
            context = this,
            log = ::appendLog,
            onConnectionChanged = ::onUsbConnectionChanged,
            onDecodedSnapshot = ::onDecodedSnapshot,
        )
        buildUi()
        ralliartRenderer = RalliartDashboardRenderer(this, dashboardRoot, this)
        simpleRenderer = SimpleDashboardRenderer(this, dashboardRoot, this)
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
        autoRefresh = false
        stopUsbAutoConnect()
        handler.removeCallbacksAndMessages(null)
        usb.unregister()
        usb.close()
        usbPollThread.quitSafely()
        if (::csvLogger.isInitialized) csvLogger.close()
        stopSimTicker()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = ClickableLinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            setBackgroundColor(Color.rgb(10, 12, 16))
        }

        val topBar = ClickableLinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), 0, dp(2f), dp(4f))
        }

        topBar.addView(TextView(this).apply {
            text = topBarHintText()
            textSize = 11f
            maxLines = 2
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.rgb(165, 174, 190))
            this@MainActivity.topBarHintText = this
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, dp(8f), 0)
        })

        topBar.addView(Button(this).apply {
            text = simModeButtonLabel()
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundedBg(
                Color.rgb(16, 20, 30),
                dp(18f).toFloat(),
                strokeWidth = dp(2f),
            )
            setOnClickListener { cycleSimTestMode() }
            this@MainActivity.simModeButton = this
        }, LinearLayout.LayoutParams(dp(86f), dp(48f)).apply {
            setMargins(0, 0, dp(6f), 0)
        })

        topBar.addView(Button(this).apply {
            text = editModeButtonText()
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundedBg(
                Color.rgb(16, 20, 30),
                dp(18f).toFloat(),
                strokeWidth = dp(2f),
            )
            setOnClickListener { toggleEditMode() }
            this@MainActivity.editModeButton = this
        }, LinearLayout.LayoutParams(dp(86f), dp(48f)).apply {
            setMargins(0, 0, dp(6f), 0)
        })

        topBar.addView(Button(this).apply {
            text = "⚙ MENU"
            textSize = 12f
            isAllCaps = false
            setOnClickListener { toggleControls() }
            this@MainActivity.gearButton = this
            setOnLongClickListener { toggleEditMode(); true }
            background = roundedBg(
                Color.rgb(16, 20, 30),
                dp(18f).toFloat(),
                strokeWidth = dp(2f),
            )
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(86f), dp(48f)))

        root.addView(topBar, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        topBarActionButtons = collectTopBarButtons(topBar)
        topBarChromeView = topBar
        updateTopBarButtonDescriptions()
        updateTopBarChromeVisibility()

        statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(190, 198, 210))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(6f))
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
        row2.addView(button("Pages / Grid") { showSimplePageManager() })
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
        row4.addView(button("Toggle subtitles") { toggleSourceSubtitles() })
        controls.addView(row4)

        val row5 = ClickableLinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row5.addView(button("Data log") { csvLogController.showMenu() })
        controls.addView(row5)

        controlsPanel = MaxHeightScrollView(this).apply {
            visibility = View.GONE
            isFillViewport = true
            maxHeightFraction = 0.68f
            background = roundedBg(
                MENU_PANEL_COLOR,
                dp(18f).toFloat(),
                MENU_BORDER_COLOR,
                dp(2f),
            )
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            addView(
                controls,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        root.addView(
            controlsPanel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val dashScroll = DashboardSwipeScrollView(this).apply {
            isFillViewport = true
            isPageSwipeEnabled = {
                mergeSession == null && dashboardPages.size > 1 && uiMode == UiMode.SIMPLE
            }
            onTouchStateChanged = { active ->
                if (active) markDashboardTouchActive() else markDashboardTouchFinished()
            }
            onHorizontalGestureStarted = {
                cancelPendingDashboardSingleTap()
            }
            onPageSwipe = { pageDelta ->
                suppressDashboardClicksBriefly()
                if (pageDelta > 0) nextPage() else previousPage()
            }
            dashboardRoot = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10f), 0, dp(10f))
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
        textSize = 16f
        isAllCaps = false
        minimumHeight = dp(64f)
        setTextColor(Color.WHITE)
        background = roundedBg(
            color = Color.BLACK,
            radius = dp(14f).toFloat(),
            strokeColor = MENU_BORDER_COLOR,
            strokeWidth = dp(2f),
        )
        setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f,
        ).apply {
            val margin = dp(3f)
            setMargins(margin, margin, margin, margin)
        }
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private val dashboardPrefs: SharedPreferences
        get() = getSharedPreferences("utcomp_dashboard", MODE_PRIVATE)

    private fun applyFastSensorSmoothingMigrationIfNeeded() {
        val prefs = dashboardPrefs
        if (prefs.getBoolean("fastSensorSmoothingV1Applied", false)) return

        var touched = false
        fun migrate(pageConfig: DashboardPageConfig): DashboardPageConfig =
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

        dashboardPages = dashboardPages.map(::migrate)
        ralliartPageConfig = migrate(ralliartPageConfig)

        prefs.edit().putBoolean("fastSensorSmoothingV1Applied", true).apply()
        if (touched) saveDashboardPrefs()
    }

    private fun loadDashboardPrefs() {
        val prefs = dashboardPrefs

        prefs.getString("uiMode", null)?.let { saved ->
            enumValues<UiMode>().firstOrNull { it.name == saved }?.let { uiMode = it }
        }

        dataLogTreeUri = prefs.getString("dataLogTreeUri", null)?.let { Uri.parse(it) }

        DashboardConfigJson.decode(prefs.getString("dashboardPagesJson", null))?.let { savedPages ->
            if (savedPages.isNotEmpty()) {
                dashboardPages = savedPages.map(DashboardPageConfig::normalized)
            }
        }

        val savedPageId = prefs.getString("pageId", null)
        currentPageIndex = dashboardPages.indexOfFirst { it.id == savedPageId }
            .takeIf { it >= 0 }
            ?: when (prefs.getString("page", null)) {
                "STRIP_1X4" -> 1
                "FULL_2X4" -> 2
                else -> 0
            }
        currentPageIndex = currentPageIndex.coerceIn(dashboardPages.indices)

        val savedRalliart = DashboardConfigJson.decode(
            prefs.getString("ralliartPageJson", null),
            defaults = listOf(DefaultDashboardPages.ralliart),
        )?.firstOrNull()
        ralliartPageConfig = savedRalliart?.normalized()
            ?: DefaultDashboardPages.ralliart.withSensorSettingsFrom(simplePageConfig())

        if (savedRalliart == null) {
            prefs.edit()
                .putString(
                    "ralliartPageJson",
                    DashboardConfigJson.encode(listOf(ralliartPageConfig)),
                )
                .apply()
        }

        if (!prefs.getBoolean("pageScopedSourceLineV1Applied", false)) {
            val legacyShowSourceLine = prefs.getBoolean("showSourceLine", true)
            dashboardPages = dashboardPages.map { page ->
                page.copy(showSourceLine = legacyShowSourceLine)
            }
            ralliartPageConfig = ralliartPageConfig.copy(
                showSourceLine = legacyShowSourceLine,
            )
            prefs.edit()
                .remove("showSourceLine")
                .putBoolean("pageScopedSourceLineV1Applied", true)
                .putString(
                    "dashboardPagesJson",
                    DashboardConfigJson.encode(dashboardPages),
                )
                .putString(
                    "ralliartPageJson",
                    DashboardConfigJson.encode(listOf(ralliartPageConfig)),
                )
                .apply()
        }
    }

    private fun saveDashboardPrefs() {
        saveDashboardPrefs(includePageConfig = true)
    }

    private fun saveDashboardViewPrefs() {
        saveDashboardPrefs(includePageConfig = false)
    }

    private fun saveDashboardPrefs(includePageConfig: Boolean) {
        runCatching {
            dashboardPrefs.edit().apply {
                putString("uiMode", uiMode.name)
                putString("pageId", simplePageConfig().id)
                putString("dataLogTreeUri", dataLogTreeUri?.toString())
                if (includePageConfig) {
                    putString("dashboardPagesJson", DashboardConfigJson.encode(dashboardPages))
                    putString(
                        "ralliartPageJson",
                        DashboardConfigJson.encode(listOf(ralliartPageConfig)),
                    )
                }
            }.apply()
        }.onFailure { error ->
            appendLog("Could not save dashboard settings: ${error.message}")
        }
    }

    private fun simplePageConfig(): DashboardPageConfig =
        dashboardPages.getOrNull(currentPageIndex) ?: dashboardPages.first()

    private fun currentPageConfig(): DashboardPageConfig =
        when (uiMode) {
            UiMode.FANCY -> ralliartPageConfig
            UiMode.SIMPLE,
            UiMode.DEBUG -> simplePageConfig()
        }

    private fun updateCurrentPage(transform: (DashboardPageConfig) -> DashboardPageConfig) {
        when (uiMode) {
            UiMode.FANCY -> {
                ralliartPageConfig = transform(ralliartPageConfig).normalized()
                ralliartRenderer.clear()
            }
            UiMode.SIMPLE -> {
                dashboardPages = dashboardPages.mapIndexed { index, pageConfig ->
                    if (index == currentPageIndex) {
                        transform(pageConfig).normalized()
                    } else {
                        pageConfig
                    }
                }
                simpleRenderer.clear()
            }
            UiMode.DEBUG -> return
        }
        saveDashboardPrefs()
        renderDashboard()
    }

    private fun replaceDashboardPages(
        pages: List<DashboardPageConfig>,
        selectedIndex: Int,
    ) {
        mergeSession = null
        clearTransientMinMaxDisplay()
        dashboardPages = pages.ifEmpty { DefaultDashboardPages.all }
            .map(DashboardPageConfig::normalized)
        currentPageIndex = selectedIndex.coerceIn(dashboardPages.indices)
        smoothedValuesByBox.clear()
        simpleRenderer.clear()
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

    private fun startMerge(boxIndex: Int) {
        if (uiMode != UiMode.SIMPLE) {
            Toast.makeText(this, "Merging is available on simple pages", Toast.LENGTH_SHORT).show()
            return
        }
        val page = currentPageConfig().normalized()
        val targetCells = page.mergeTargetCells(boxIndex)
        if (targetCells.isEmpty()) {
            Toast.makeText(this, "No rectangular adjacent cell is available", Toast.LENGTH_SHORT)
                .show()
            return
        }

        editMode = true
        mergeSession = MergeSession(page.id, boxIndex, targetCells)
        simpleRenderer.clear()
        updateEditModeButton()
        updateTopBarChromeVisibility()
        appendLog("Merge started for ${page.boxes[boxIndex].sensor.label}")
        Toast.makeText(
            this,
            "Select a green cell. Tap the blue source or CANCEL to stop.",
            Toast.LENGTH_LONG,
        ).show()
        renderDashboard()
    }

    private fun cancelMerge(render: Boolean = true) {
        if (mergeSession == null) return
        mergeSession = null
        simpleRenderer.clear()
        updateEditModeButton()
        updateTopBarChromeVisibility()
        appendLog("Merge cancelled")
        if (render) renderDashboard()
    }

    private fun selectMergeBox(boxIndex: Int) {
        val session = mergeSession ?: return
        if (boxIndex == session.sourceBoxIndex) {
            cancelMerge()
            return
        }

        val page = currentPageConfig().normalized()
        if (session.pageId != page.id) {
            cancelMerge()
            return
        }
        val box = page.boxes.getOrNull(boxIndex) ?: return
        val targetCell = buildList {
            for (row in box.row until box.row + box.rowSpan) {
                for (column in box.column until box.column + box.columnSpan) {
                    add(row * page.columns + column)
                }
            }
        }.firstOrNull(session.targetCells::contains)

        if (targetCell == null) {
            Toast.makeText(this, "Select one of the highlighted green cells", Toast.LENGTH_SHORT)
                .show()
            return
        }
        selectMergeCell(targetCell / page.columns, targetCell % page.columns)
    }

    private fun startUsbAutoConnect(initialDelayMs: Long = 350L) {
        if (dataMode != DataMode.USB) return
        autoRefresh = true
        autoUsbHandler.removeCallbacks(autoUsbRunnable)
        autoUsbHandler.postDelayed(autoUsbRunnable, initialDelayMs)
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
                dp(18f).toFloat(),
                strokeWidth = dp(2f),
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
        when {
            mergeSession != null -> "✕ CANCEL"
            editMode -> "✓ DONE"
            else -> "✎ EDIT"
        }

    private fun toggleEditMode() {
        if (mergeSession != null) {
            cancelMerge()
            return
        }

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
        when {
            mergeSession != null ->
                "MERGE: tap a green cell · tap the blue source or CANCEL to stop"
            uiMode == UiMode.DEBUG ->
                "Tap or long press: menu"
            editMode && uiMode == UiMode.FANCY ->
                "Tap a gauge or the Ralliart top bar to edit"
            editMode ->
                "Tap a dashboard box to edit"
            else ->
                "Tap: min/max · Quick double tap: menu · Long press: menu"
        }

    private fun View.setTooltipCompat(text: CharSequence?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            tooltipText = text
        }
    }

    private fun updateEditModeButton() {
        editModeButton?.apply {
            text = editModeButtonText()
            contentDescription = when {
                mergeSession != null -> "Cancel dashboard box merge"
                editMode -> "Finish editing dashboard boxes"
                else -> "Edit dashboard boxes"
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
            contentDescription = when {
                mergeSession != null -> "Cancel dashboard box merge"
                editMode -> "Finish editing dashboard boxes"
                else -> "Edit dashboard boxes"
            }
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

    private fun openBoxEditorOnce(boxIndex: Int, editorTitle: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastBoxEditorLaunchMs < 700L) {
            return
        }

        lastBoxEditorLaunchMs = now
        cancelPendingDashboardSingleTap()
        lastDashboardBoxTapMs = 0L
        dashboardEditorController.showBoxEditor(boxIndex, editorTitle)
    }


    override fun attachBoxActions(
        view: View,
        boxIndex: Int,
        minMaxKey: String?,
        editorTitle: String,
    ) {
        val card = view
        card.isClickable = true
        card.setOnClickListener {
            if (dashboardClickSuppressed()) return@setOnClickListener

            if (editMode) {
                if (boxIndex >= 0) {
                    if (mergeSession != null) {
                        selectMergeBox(boxIndex)
                    } else {
                        openBoxEditorOnce(boxIndex, editorTitle)
                    }
                }
            } else {
                handleDashboardBoxTapForChrome(minMaxKey, "box:$boxIndex")
            }
        }
        card.setOnLongClickListener {
            cancelPendingDashboardSingleTap()
            lastDashboardBoxTapMs = 0L
            if (editMode) {
                if (boxIndex >= 0) {
                    if (mergeSession != null) {
                        selectMergeBox(boxIndex)
                    } else {
                        openBoxEditorOnce(boxIndex, editorTitle)
                    }
                }
            } else {
                revealTopBarChrome(autoHide = true)
            }
            true
        }
    }


    override fun attachRalliartHeaderActions(view: View) {
        view.isClickable = true
        view.setOnClickListener {
            if (dashboardClickSuppressed()) return@setOnClickListener
            if (editMode) {
                openRalliartHeaderEditorOnce()
            } else {
                handleDashboardBoxTapForChrome(null, "ralliart-header")
            }
        }
        view.setOnLongClickListener {
            cancelPendingDashboardSingleTap()
            lastDashboardBoxTapMs = 0L
            if (editMode) {
                openRalliartHeaderEditorOnce()
            } else {
                revealTopBarChrome(autoHide = true)
            }
            true
        }
    }

    private fun openRalliartHeaderEditorOnce() {
        val now = SystemClock.uptimeMillis()
        if (now - lastBoxEditorLaunchMs < 700L) return
        lastBoxEditorLaunchMs = now
        cancelPendingDashboardSingleTap()
        lastDashboardBoxTapMs = 0L
        ralliartHeaderEditorController.showEditor()
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
        if (autoRefresh) {
            startUsbAutoConnect(initialDelayMs = 0L)
        } else {
            stopUsbAutoConnect()
        }
        renderDashboard()
    }

    private fun cycleUiMode() {
        cancelMerge(render = false)
        clearTransientMinMaxDisplay()
        uiMode = when (uiMode) {
            UiMode.FANCY -> UiMode.SIMPLE
            UiMode.SIMPLE -> UiMode.DEBUG
            UiMode.DEBUG -> UiMode.FANCY
        }
        saveDashboardViewPrefs()
        renderDashboard()
    }

    private fun showSimplePageManager() {
        cancelMerge(render = false)
        clearTransientMinMaxDisplay()
        if (uiMode != UiMode.SIMPLE) {
            uiMode = UiMode.SIMPLE
            saveDashboardViewPrefs()
            renderDashboard()
        }
        dashboardPageEditorController.showPageManager()
    }

    private fun nextPage() {
        if (uiMode != UiMode.SIMPLE || dashboardPages.isEmpty()) return
        cancelMerge(render = false)
        clearTransientMinMaxDisplay()
        currentPageIndex = (currentPageIndex + 1) % dashboardPages.size
        saveDashboardViewPrefs()
        renderDashboard()
    }

    private fun previousPage() {
        if (uiMode != UiMode.SIMPLE || dashboardPages.isEmpty()) return
        cancelMerge(render = false)
        clearTransientMinMaxDisplay()
        currentPageIndex =
            (currentPageIndex + dashboardPages.size - 1) % dashboardPages.size
        saveDashboardViewPrefs()
        renderDashboard()
    }



    private fun onUsbConnectionChanged(isConnected: Boolean) {
        val changed = connected != isConnected
        connected = isConnected

        autoUsbHandler.removeCallbacks(autoUsbRunnable)
        if (dataMode == DataMode.USB && autoRefresh) {
            val delayMs = if (isConnected) {
                0L
            } else {
                UsbRecoveryPolicy.FAST_RECONNECT_DELAY_MS
            }
            autoUsbHandler.postDelayed(autoUsbRunnable, delayMs)
        }

        if (changed) {
            runOnUiThread { renderDashboardNow() }
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

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (::csvLogController.isInitialized) {
            csvLogController.onActivityResult(requestCode, resultCode, data)
        }
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
            if (uiMode == UiMode.DEBUG) {
                append("Debug snapshot")
            } else {
                append(currentPageConfig().title)
                append("  •  $uiMode")
            }
            append("  •  $dataMode")
            append("  •  USB ${if (connected) "OK" else "—"}")
            if (autoRefresh) append("  •  AUTO")
            if (editMode) append("  •  EDIT")
            if (s.firmware != "?") append("  •  fw ${s.firmware}")
        }

        prepareDashboardMode()
        when (uiMode) {
            UiMode.FANCY -> renderFancy(renderSnapshot)
            UiMode.SIMPLE -> renderSimple(renderSnapshot)
            UiMode.DEBUG -> renderDebug(renderSnapshot)
        }
    }

    private fun prepareDashboardMode() {
        if (lastRenderedUiMode == uiMode) return

        dashboardRoot.removeAllViews()
        dashboardRoot.minimumWidth = 0
        dashboardRoot.minimumHeight = 0
        dashboardRoot.setPadding(0, 0, 0, 0)
        dashboardRoot.isClickable = false
        dashboardRoot.setOnClickListener(null)
        dashboardRoot.setOnLongClickListener(null)
        debugTextView = null
        lastRenderedUiMode = uiMode
    }

    override fun displayValueForBox(
        box: DashboardBoxConfig,
        boxIndex: Int,
        rawValue: Float?,
    ): Float? {
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

    private fun smoothedValueKey(boxIndex: Int, box: DashboardBoxConfig): Int =
        ((currentPageConfig().id.hashCode() * 31) + boxIndex) * 31 + box.sensor.ordinal

    override fun formatBoxValue(value: Float?, decimals: Int): String =
        value?.fixed(decimals.coerceIn(0, 2)) ?: "--"

    override fun styledValueText(valueText: String, split: Boolean): CharSequence {
        if (!split) return valueText

        val dot = valueText.indexOf('.')
        if (dot <= 0 || dot >= valueText.length - 1) return valueText

        val span = SpannableString(valueText)
        span.setSpan(RelativeSizeSpan(1.18f), 0, dot, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(RelativeSizeSpan(0.62f), dot, valueText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return span
    }

    private fun Int.floorMod(mod: Int): Int =
        ((this % mod) + mod) % mod

    override fun alarmLevelFor(
        box: DashboardBoxConfig,
        rawValue: Float?,
        snapshot: UtcompDataSnapshot?,
    ): DashboardAlarmLevel {
        val value = rawValue ?: return DashboardAlarmLevel.NORMAL
        if (value.isNaN() || value.isInfinite()) return DashboardAlarmLevel.NORMAL

        oilPressureBoostAlarmLevel(box, value, snapshot).let { oilLevel ->
            if (oilLevel != DashboardAlarmLevel.NORMAL) return oilLevel
        }

        val skipGenericLowAlarm = box.sensor == DashboardSensor.OIL_PRESSURE && box.oilPressureBoostAlarm

        if (
            (!skipGenericLowAlarm && thresholdHitLow(value, box.criticalLow)) ||
            thresholdHitHigh(value, box.criticalHigh)
        ) {
            return DashboardAlarmLevel.CRITICAL
        }
        if (
            (!skipGenericLowAlarm && thresholdHitLow(value, box.warningLow)) ||
            thresholdHitHigh(value, box.warningHigh)
        ) {
            return DashboardAlarmLevel.WARNING
        }
        return DashboardAlarmLevel.NORMAL
    }


    private fun oilPressureBoostAlarmLevel(
        box: DashboardBoxConfig,
        oilPressureBar: Float,
        snapshot: UtcompDataSnapshot?,
    ): DashboardAlarmLevel {
        if (box.sensor != DashboardSensor.OIL_PRESSURE) return DashboardAlarmLevel.NORMAL
        if (!box.oilPressureBoostAlarm) return DashboardAlarmLevel.NORMAL
        if (oilPressureBar.isNaN() || oilPressureBar.isInfinite()) return DashboardAlarmLevel.NORMAL

        val boostBar = snapshot?.bar1 ?: return DashboardAlarmLevel.NORMAL
        if (boostBar.isNaN() || boostBar.isInfinite()) return DashboardAlarmLevel.NORMAL
        if (box.oilPressureBoostArmBar.isNaN() || boostBar < box.oilPressureBoostArmBar) {
            return DashboardAlarmLevel.NORMAL
        }

        if (!box.criticalLow.isNaN() && oilPressureBar <= box.criticalLow) {
            return DashboardAlarmLevel.CRITICAL
        }
        if (!box.warningLow.isNaN() && oilPressureBar <= box.warningLow) {
            return DashboardAlarmLevel.WARNING
        }

        return DashboardAlarmLevel.NORMAL
    }


    private fun thresholdHitHigh(value: Float, threshold: Float): Boolean =
        !threshold.isNaN() && value >= threshold

    private fun thresholdHitLow(value: Float, threshold: Float): Boolean =
        !threshold.isNaN() && value <= threshold

    override fun boxBackgroundColor(
        box: DashboardBoxConfig,
        alarmLevel: DashboardAlarmLevel,
    ): Int =
        if (!box.alarmColorsBackground) {
            box.backgroundColor
        } else {
            when (alarmLevel) {
                DashboardAlarmLevel.CRITICAL -> box.criticalColor
                DashboardAlarmLevel.WARNING -> box.warningColor
                DashboardAlarmLevel.NORMAL -> box.backgroundColor
            }
        }

    override fun boxValueColor(
        box: DashboardBoxConfig,
        alarmLevel: DashboardAlarmLevel,
    ): Int =
        if (!box.alarmColorsValue) {
            box.valueColor
        } else {
            when (alarmLevel) {
                DashboardAlarmLevel.CRITICAL -> box.criticalValueColor
                DashboardAlarmLevel.WARNING -> box.warningValueColor
                DashboardAlarmLevel.NORMAL -> box.valueColor
            }
        }

    override fun sourceSubtitleFor(sensor: DashboardSensor): String =
        if (currentPageConfig().showSourceLine) {
            UtcompDeviceConfig.subtitleFor(sensor)
        } else {
            ""
        }

    override fun fallbackIconFor(sensor: DashboardSensor): String =
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

    private fun renderFancy(snapshot: UtcompDataSnapshot) {
        ralliartRenderer.render(currentPageConfig(), snapshot)
    }

    private fun renderSimple(snapshot: UtcompDataSnapshot) {
        simpleRenderer.render(currentPageConfig(), snapshot)
    }

    private fun renderDebug(s: UtcompDataSnapshot) {
        val textView = debugTextView?.takeIf { it.parent === dashboardRoot } ?: TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(220, 225, 235))
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(false)
        }.also { created ->
            val openMenu = View.OnClickListener {
                if (!dashboardClickSuppressed()) {
                    cancelPendingDashboardSingleTap()
                    revealTopBarChrome(autoHide = true)
                }
            }
            val openMenuLong = View.OnLongClickListener {
                openMenu.onClick(it)
                true
            }

            dashboardRoot.isClickable = true
            dashboardRoot.setOnClickListener(openMenu)
            dashboardRoot.setOnLongClickListener(openMenuLong)
            created.isClickable = true
            created.setOnClickListener(openMenu)
            created.setOnLongClickListener(openMenuLong)

            dashboardRoot.removeAllViews()
            dashboardRoot.addView(
                created,
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            debugTextView = created
        }

        val text = buildString(384) {
            appendLine("Debug snapshot")
            appendLine("fw=${s.firmware} utcompPro=${s.utcompPro}")
            appendLine("bar1=${s.bar1} bar2=${s.bar2} bar3=${s.bar3}")
            appendLine("afr1=${s.afr1} afr2=${s.afr2}")
            appendLine(
                "adc0=${s.adcInValCh0} adc1=${s.adcInValCh1} " +
                    "adc2=${s.adcInValCh2} adc3=${s.adcInValCh3} adc4=${s.adcInValCh4}"
            )
            appendLine("ntc1=${s.temperatureNtc1} dsA=${s.temperatureDsA} dsB=${s.temperatureDsB}")
            appendLine("rpm=${s.rpm} speed=${s.vssSpeed1s}")
            appendLine("fuelPb=${s.fuelLeftPb} fuelLpg=${s.fuelLeftLpg}")
            appendLine("tripDist=${s.tripDist} tripCost=${s.tripCost}")
        }
        if (textView.text.toString() != text) textView.text = text
    }

    override fun currentClockText(): String {
        val nowMs = System.currentTimeMillis()
        val minute = nowMs / 60_000L
        if (minute != cachedClockMinute) {
            cachedClockMinute = minute
            clockDate.time = nowMs
            cachedClockText = clockFmt.format(clockDate)
        }
        return cachedClockText
    }

    override fun mergeVisualStateForBox(boxIndex: Int): DashboardMergeVisualState {
        val session = mergeSession ?: return DashboardMergeVisualState.NONE
        val page = currentPageConfig().normalized()
        if (session.pageId != page.id) return DashboardMergeVisualState.NONE
        if (boxIndex == session.sourceBoxIndex) return DashboardMergeVisualState.SOURCE

        val box = page.boxes.getOrNull(boxIndex) ?: return DashboardMergeVisualState.BLOCKED
        for (row in box.row until box.row + box.rowSpan) {
            for (column in box.column until box.column + box.columnSpan) {
                if (row * page.columns + column in session.targetCells) {
                    return DashboardMergeVisualState.TARGET
                }
            }
        }
        return DashboardMergeVisualState.BLOCKED
    }

    override fun mergeVisualStateForCell(row: Int, column: Int): DashboardMergeVisualState {
        val session = mergeSession ?: return DashboardMergeVisualState.NONE
        val page = currentPageConfig().normalized()
        if (session.pageId != page.id) return DashboardMergeVisualState.NONE
        val cell = row * page.columns + column
        return if (cell in session.targetCells) {
            DashboardMergeVisualState.TARGET
        } else {
            DashboardMergeVisualState.BLOCKED
        }
    }

    override fun selectMergeCell(row: Int, column: Int) {
        val session = mergeSession ?: return
        val page = currentPageConfig().normalized()
        if (session.pageId != page.id) {
            cancelMerge()
            return
        }
        val cell = row * page.columns + column
        if (cell !in session.targetCells) {
            Toast.makeText(this, "Select one of the highlighted green cells", Toast.LENGTH_SHORT)
                .show()
            return
        }

        val merged = page.mergeBoxIntoCell(session.sourceBoxIndex, row, column)
        if (merged == page) {
            Toast.makeText(this, "Those cells do not form a rectangle", Toast.LENGTH_SHORT).show()
            return
        }

        mergeSession = null
        simpleRenderer.clear()
        updateEditModeButton()
        updateTopBarChromeVisibility()
        updateCurrentPage { merged }
    }

    override fun iconDrawableResourceId(resourceName: String?): Int =
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






    private fun roundedBg(
        color: Int,
        radius: Float,
        strokeColor: Int = MENU_BORDER_COLOR,
        strokeWidth: Int = 2,
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            setStroke(strokeWidth, strokeColor)
        }


    private fun minMaxScopePrefix(): String =
        "${uiMode.name}:${currentPageConfig().id}:"

    private fun scopedMinMaxKey(key: String): String = minMaxScopePrefix() + key

    override fun trackMinMax(key: String, value: Float): DashboardMinMax {
        val stats = minMaxByScope.getOrPut(scopedMinMaxKey(key)) { DashboardMinMax() }
        if (value.isNaN() || value.isInfinite()) return stats

        if (stats.min.isNaN() || value < stats.min) stats.min = value
        if (stats.max.isNaN() || value > stats.max) stats.max = value
        return stats
    }

    private fun resetAllMinMax() {
        val scopePrefix = minMaxScopePrefix()
        minMaxByScope.keys.removeAll { it.startsWith(scopePrefix) }
        clearTransientMinMaxDisplay()
        appendLog("Min/max values reset for ${currentPageConfig().title} / $uiMode")
        renderDashboard()
    }

    private fun toggleMinMaxMode() {
        if (uiMode == UiMode.DEBUG) {
            appendLog("Min/max display is unavailable in debug mode")
            return
        }
        val alwaysVisible = !currentPageConfig().minMaxAlwaysVisible
        clearTransientMinMaxDisplay()
        appendLog(
            "Min/max display for ${currentPageConfig().title} / $uiMode: " +
                if (alwaysVisible) "always" else "tap for 3 seconds",
        )
        updateCurrentPage { it.copy(minMaxAlwaysVisible = alwaysVisible) }
    }

    private fun toggleSourceSubtitles() {
        if (uiMode == UiMode.DEBUG) {
            appendLog("Sensor source subtitles are unavailable in debug mode")
            return
        }
        val showSourceLine = !currentPageConfig().showSourceLine
        appendLog(
            "Sensor source subtitles for ${currentPageConfig().title} / $uiMode: " +
                if (showSourceLine) "enabled" else "hidden",
        )
        updateCurrentPage { it.copy(showSourceLine = showSourceLine) }
    }

    override fun shouldShowMinMax(key: String): Boolean =
        currentPageConfig().minMaxAlwaysVisible || activeMinMaxCard == scopedMinMaxKey(key)

    override fun isEditMode(): Boolean = editMode

    override fun addBoxAt(row: Int, column: Int) {
        if (mergeSession != null) {
            selectMergeCell(row, column)
        } else {
            updateCurrentPage { it.addBoxAt(row, column) }
        }
    }

    private fun clearTransientMinMaxDisplay() {
        activeMinMaxCard = null
        minMaxHideRunnable?.let { handler.removeCallbacks(it) }
        minMaxHideRunnable = null
    }

    private fun showMinMaxInline(key: String) {
        val scopedKey = scopedMinMaxKey(key)
        activeMinMaxCard = scopedKey
        minMaxHideRunnable?.let { handler.removeCallbacks(it) }

        if (!currentPageConfig().minMaxAlwaysVisible) {
            val hide = Runnable {
                if (activeMinMaxCard == scopedKey) {
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
