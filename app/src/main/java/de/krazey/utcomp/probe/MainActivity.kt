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
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import de.krazey.utcomp.probe.protocol.TransmitterConstants
import de.krazey.utcomp.probe.protocol.TransmitterPacket
import de.krazey.utcomp.probe.simulation.SimulationEngine
import de.krazey.utcomp.probe.transport.UtcompUsbTransport
import de.krazey.utcomp.probe.utcomp.UtcompDataSnapshot
import de.krazey.utcomp.probe.utcomp.UtcompDecoder
import de.krazey.utcomp.probe.utcomp.pretty
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.LinkedHashMap
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private companion object {
        const val TAG = "UTCOMPProbe"
    }

    private enum class DataMode { USB, SIM }
    private enum class UiMode { FANCY, SIMPLE, DEBUG }
    private enum class Page { PERFORMANCE, TEMPS, TRIP }
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
    private var page = Page.PERFORMANCE
    private var connected = false
    private var autoRefresh = false
    private var simTick = 0L
    private var controlsVisible = false
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
            isFillViewport = false
            dashboardRoot = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 10, 0, 10)
            }
            addView(dashboardRoot, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
        page = when (page) {
            Page.PERFORMANCE -> Page.TEMPS
            Page.TEMPS -> Page.TRIP
            Page.TRIP -> Page.PERFORMANCE
        }
        renderDashboard()
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
        when (page) {
            Page.PERFORMANCE -> {
                addFancyRow(
                    fancyCard("Boost", s.bar1, "bar", -1.0f, 2.0f, "ADC3 boost pressure"),
                    fancyCard("AFR", s.afr1, "", 10f, 22f, "ADC1 wideband O2"),
                )
                addFancyRow(
                    fancyCard("Oil pressure", s.bar2, "bar", 0f, 8f, "ADC4 oil pressure"),
                    fancyCard("Oil temp", s.temperatureNtc1, "°C", 0f, 140f, "ADCVCC1 NTC"),
                )
                addFancyRow(
                    compactCard("Battery", fmt(s.adcInValCh0, " V"), "supply"),
                    compactCard("Inside", fmt(s.temperatureDsB, " °C"), "DS-B"),
                )
            }

            Page.TEMPS -> {
                addFancyRow(
                    fancyCard("Outside", s.temperatureDsA, "°C", -20f, 80f, "DS-A behind oil cooler"),
                    fancyCard("Inside", s.temperatureDsB, "°C", -20f, 80f, "DS-B cabin"),
                )
                addFancyRow(
                    fancyCard("Oil / NTC", s.temperatureNtc1, "°C", 0f, 140f, "ADCVCC1 NTC"),
                    fancyCard("Engine", s.temperatureEngine, "°C", 0f, 140f, "coolant slot"),
                )
            }

            Page.TRIP -> {
                addFancyRow(
                    fancyCard("Speed", s.vssSpeed1s.toFloat(), "km/h", 0f, 260f, "vehicle speed"),
                    fancyCard("Fuel left", s.fuelLeftPb, "L", 0f, 80f, "petrol tank"),
                )
                addFancyRow(
                    compactCard("Trip distance", fmt(s.tripDist, " km"), "current trip"),
                    compactCard("Trip cost", fmt(s.tripCost, " €"), "current trip"),
                )
                addFancyRow(
                    compactCard("Avg cons.", fmt(s.consumptionAvg, " l/100km"), "average"),
                    compactCard("Cur cons.", fmt(s.consumptionCur, " l/100km"), "current"),
                )
            }
        }
    }

    private fun renderSimple(s: UtcompDataSnapshot) {
        val values = when (page) {
            Page.PERFORMANCE -> listOf(
                rowValue("🌀", "Boost", fmt(s.bar1, " bar")),
                rowValue("λ", "AFR", fmt(s.afr1, "")),
                rowValue("🛢", "Oil pressure", fmt(s.bar2, " bar")),
                rowValue("🌡", "Oil temp", fmt(s.temperatureNtc1, " °C")),
                rowValue("🔋", "Battery", fmt(s.adcInValCh0, " V")),
                rowValue("🏠", "Inside", fmt(s.temperatureDsB, " °C")),
            )

            Page.TEMPS -> listOf(
                rowValue("🌡", "Outside DS-A", fmt(s.temperatureDsA, " °C")),
                rowValue("🌡", "Inside DS-B", fmt(s.temperatureDsB, " °C")),
                rowValue("🌡", "Oil / NTC", fmt(s.temperatureNtc1, " °C")),
                rowValue("🌡", "Engine", fmt(s.temperatureEngine, " °C")),
            )

            Page.TRIP -> listOf(
                rowValue("🚗", "Speed", "${s.vssSpeed1s} km/h"),
                rowValue("⛽", "Fuel PB", fmt(s.fuelLeftPb, " L")),
                rowValue("📏", "Trip distance", fmt(s.tripDist, " km")),
                rowValue("💶", "Trip cost", fmt(s.tripCost, " €")),
                rowValue("📊", "Avg consumption", fmt(s.consumptionAvg, " l/100km")),
                rowValue("⚡", "Current consumption", fmt(s.consumptionCur, " l/100km")),
            )
        }

        values.forEach { addSimpleRow(it.icon, it.label, it.value) }
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
            textSize = 30f
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

        card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            this.max = 1000
            progress = progressFor(value, min, maxValue)
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
            textSize = 24f
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
            setPadding(18, 16, 18, 16)
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

    private fun progressFor(value: Float, min: Float, maxValue: Float): Int {
        if (value.isNaN() || value.isInfinite() || maxValue <= min) return 0
        val normalized = (value - min) / (maxValue - min)
        return (normalized.coerceIn(0f, 1f) * 1000f).roundToInt()
    }

    private fun fmt(v: Float, suffix: String): String =
        if (v.isNaN() || v.isInfinite()) "—" else v.pretty() + suffix
}
