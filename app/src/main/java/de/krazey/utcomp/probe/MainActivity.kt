package de.krazey.utcomp.probe

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
    private enum class UiMode { FANCY, SIMPLE, DEBUG }
    private enum class Page(val label: String, val rows: Int, val columns: Int) {
        RACE_2X2("Race 2×2", 2, 2),
        STRIP_1X4("Strip 1×4", 4, 1),
        FULL_2X4("Full 2×4", 4, 2),
        ;

        override fun toString(): String = label
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
    private var uiMode = UiMode.FANCY
    private var page = Page.RACE_2X2
    private var connected = false
    private var autoRefresh = false
    private var simTick = 0L
    private var controlsVisible = false
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
            if (dataMode == DataMode.SIM) {
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
            text = "⚙"
            textSize = 18f
            setAllCaps(false)
            setOnClickListener { toggleControls() }
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
        row1.addView(button("SIM mode") { setDataMode(DataMode.SIM) })
        row1.addView(button("Auto") { toggleAutoRefresh() })
        controls.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(button("Style") { cycleUiMode() })
        row2.addView(button("Page") { cyclePage() })
        row2.addView(button("REQ live") { requestLiveData(logEach = true) })
        controls.addView(row2)

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(button("REQ settings") { requestUsb(TransmitterConstants.UtcompPid.GENERAL_SETTINGS1, true) })
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

    private fun renderDashboard() {
        if (!::dashboardRoot.isInitialized || !::statusText.isInitialized) return

        if (::controlsPanel.isInitialized) {
            controlsPanel.visibility = if (controlsVisible) View.VISIBLE else View.GONE
        }

        val showDebugLog = controlsVisible || uiMode == UiMode.DEBUG
        if (::logTitleText.isInitialized) logTitleText.visibility = if (showDebugLog) View.VISIBLE else View.GONE
        if (::logScroll.isInitialized) logScroll.visibility = if (showDebugLog) View.VISIBLE else View.GONE
        if (::statusText.isInitialized) statusText.visibility = if (showDebugLog) View.VISIBLE else View.GONE

        val s = UtcompDecoder.snapshot
        statusText.text = buildString {
            append("$page")
            append("  •  $uiMode")
            append("  •  $dataMode")
            append("  •  USB ${if (connected) "OK" else "—"}")
            if (autoRefresh) append("  •  AUTO")
            if (s.firmware != "?") append("  •  fw ${s.firmware}")
        }

        dashboardRoot.removeAllViews()

        when (uiMode) {
            UiMode.FANCY -> renderFancy(s)
            UiMode.SIMPLE -> renderSimple(s)
            UiMode.DEBUG -> renderDebug(s)
        }
    }

    private fun renderFancy(s: UtcompDataSnapshot) {
        val cards = when (page) {
            Page.RACE_2X2 -> listOf(
                fancyCard("Boost", s.bar1, "bar", -1.0f, 2.0f, "ADC3 boost pressure"),
                fancyCard("AFR", s.afr1, "", 10f, 22f, "ADC1 wideband O2"),
                fancyCard("Oil temp", s.temperatureNtc1, "°C", 0f, 140f, "ADCVCC1 NTC"),
                fancyCard("Oil pressure", s.bar2, "bar", 0f, 8f, "ADC4 oil pressure"),
            )

            Page.STRIP_1X4 -> listOf(
                fancyCard("Boost", s.bar1, "bar", -1.0f, 2.0f, "ADC3 boost pressure"),
                fancyCard("AFR", s.afr1, "", 10f, 22f, "ADC1 wideband O2"),
                fancyCard("Oil temp", s.temperatureNtc1, "°C", 0f, 140f, "ADCVCC1 NTC"),
                fancyCard("Oil pressure", s.bar2, "bar", 0f, 8f, "ADC4 oil pressure"),
            )

            Page.FULL_2X4 -> listOf(
                fancyCard("Boost", s.bar1, "bar", -1.0f, 2.0f, "ADC3 boost pressure"),
                fancyCard("AFR", s.afr1, "", 10f, 22f, "ADC1 wideband O2"),
                fancyCard("Oil temp", s.temperatureNtc1, "°C", 0f, 140f, "ADCVCC1 NTC"),
                fancyCard("Oil pressure", s.bar2, "bar", 0f, 8f, "ADC4 oil pressure"),
                compactCard("Battery", fmt(s.adcInValCh0, " V"), "supply"),
                compactCard("Outside", fmt(s.temperatureDsA, " °C"), "DS-A"),
                compactCard("Inside", fmt(s.temperatureDsB, " °C"), "DS-B"),
                compactCard("Time", SimpleDateFormat("HH:mm", Locale.US).format(Date()), "system"),
            )
        }

        addPresetGrid(page.rows, page.columns, cards)
    }

    private fun renderSimple(s: UtcompDataSnapshot) {
        val cards = when (page) {
            Page.RACE_2X2 -> listOf(
                simpleGridCard("🌀", "ic_rcomp_boost_48dp", "Boost", fmt(s.bar1, ""), "BAR", "Boost", s.bar1, " BAR"),
                simpleGridCard("AFR", "ic_rcomp_afr_48dp", "AFR", fmt(s.afr1, ""), "", "AFR", s.afr1, ""),
                simpleGridCard("🛢", "ic_rcomp_oiltemp_48dp", "Oil temp", fmt(s.temperatureNtc1, ""), "°C", "Oil temp", s.temperatureNtc1, " °C"),
                simpleGridCard("💧", "ic_rcomp_oilpres_48dp", "Oil pressure", fmt(s.bar2, ""), "BAR", "Oil pressure", s.bar2, " BAR"),
            )

            Page.STRIP_1X4 -> listOf(
                simpleGridCard("🌀", "ic_rcomp_boost_48dp", "Boost", fmt(s.bar1, ""), "BAR", "Boost", s.bar1, " BAR"),
                simpleGridCard("AFR", "ic_rcomp_afr_48dp", "AFR", fmt(s.afr1, ""), "", "AFR", s.afr1, ""),
                simpleGridCard("🛢", "ic_rcomp_oiltemp_48dp", "Oil temp", fmt(s.temperatureNtc1, ""), "°C", "Oil temp", s.temperatureNtc1, " °C"),
                simpleGridCard("💧", "ic_rcomp_oilpres_48dp", "Oil pressure", fmt(s.bar2, ""), "BAR", "Oil pressure", s.bar2, " BAR"),
            )

            Page.FULL_2X4 -> listOf(
                simpleGridCard("🌀", "ic_rcomp_boost_48dp", "Boost", fmt(s.bar1, ""), "BAR", "Boost", s.bar1, " BAR"),
                simpleGridCard("AFR", "ic_rcomp_afr_48dp", "AFR", fmt(s.afr1, ""), "", "AFR", s.afr1, ""),
                simpleGridCard("🛢", "ic_rcomp_oiltemp_48dp", "Oil temp", fmt(s.temperatureNtc1, ""), "°C", "Oil temp", s.temperatureNtc1, " °C"),
                simpleGridCard("💧", "ic_rcomp_oilpres_48dp", "Oil pressure", fmt(s.bar2, ""), "BAR", "Oil pressure", s.bar2, " BAR"),
                simpleGridCard("🏠", "ic_rcomp_inside_temp_48dp", "Inside", fmt(s.temperatureDsB, ""), "°C", "Inside", s.temperatureDsB, " °C"),
                simpleGridCard("☁", "ic_rcomp_outside_temp_48dp", "Outside", fmt(s.temperatureDsA, ""), "°C", "Outside", s.temperatureDsA, " °C"),
                simpleGridCard("🔋", "ic_utcomp_battery_48dp", "Battery", fmt(s.adcInValCh0, ""), "V", "Battery", s.adcInValCh0, " V"),
                simpleGridCard("19:47", "ic_rcomp_timer_trip_48dp", "Time", SimpleDateFormat("HH:mm", Locale.US).format(Date()), "", null, null, ""),
            )
        }

        addPresetGrid(page.rows, page.columns, cards)
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
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = label
            background = roundedBg(Color.rgb(11, 14, 20), 0f)
            setPadding(12, 4, 10, 4)

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

            val iconResId = resources.getIdentifier(iconResourceName, "drawable", packageName)
            if (iconResId != 0) {
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(iconResId)
                    adjustViewBounds = true
                    alpha = 0.95f
                }, LinearLayout.LayoutParams(44, 44).apply {
                    setMargins(0, 0, 10, 0)
                })
            } else {
                addView(TextView(this@MainActivity).apply {
                    text = fallbackIcon
                    textSize = if (fallbackIcon.length > 2) 14f else 22f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                }, LinearLayout.LayoutParams(44, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 10, 0)
                })
            }

            val valueRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            valueRow.addView(TextView(this@MainActivity).apply {
                text = value
                textSize = 29f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            if (unit.isNotBlank()) {
                valueRow.addView(TextView(this@MainActivity).apply {
                    text = " $unit"
                    textSize = 14f
                    setTextColor(Color.rgb(210, 216, 225))
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
                setTextColor(Color.rgb(255, 72, 72))
                gravity = Gravity.END
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
            }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

            statBox.addView(TextView(this@MainActivity).apply {
                text = if (showStats) fmt(stats!!.min, minMaxSuffix) else ""
                textSize = 11f
                setTextColor(Color.rgb(80, 170, 255))
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
