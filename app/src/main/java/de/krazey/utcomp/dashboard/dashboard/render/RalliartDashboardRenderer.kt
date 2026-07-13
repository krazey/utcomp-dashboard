package de.krazey.utcomp.dashboard.dashboard.render

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import de.krazey.utcomp.dashboard.R
import de.krazey.utcomp.dashboard.dashboard.DashboardBoxConfig
import de.krazey.utcomp.dashboard.dashboard.DashboardPageConfig
import de.krazey.utcomp.dashboard.dashboard.DashboardSensor
import de.krazey.utcomp.dashboard.dashboard.DefaultDashboardPages
import de.krazey.utcomp.dashboard.utcomp.UtcompDataSnapshot
import de.krazey.utcomp.dashboard.view.RalliartAfrDebugBarView
import de.krazey.utcomp.dashboard.view.RalliartBoostNeedleView

internal class RalliartDashboardRenderer(
    private val activity: Activity,
    private val dashboardRoot: LinearLayout,
    private val host: DashboardRenderHost,
) {
    private class ClickableLinearLayout(activity: Activity) : LinearLayout(activity) {
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private class DesignCanvasHost(
        activity: Activity,
        private val designWidth: Int,
        private val designHeight: Int,
    ) : FrameLayout(activity) {
        init {
            setBackgroundColor(Color.BLACK)
            clipToPadding = false
            clipChildren = false
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = measuredSize(widthMeasureSpec, designWidth)
            val height = measuredSize(heightMeasureSpec, designHeight)
            setMeasuredDimension(width, height)

            val childWidth = MeasureSpec.makeMeasureSpec(designWidth, MeasureSpec.EXACTLY)
            val childHeight = MeasureSpec.makeMeasureSpec(designHeight, MeasureSpec.EXACTLY)
            for (index in 0 until childCount) {
                getChildAt(index).measure(childWidth, childHeight)
            }
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val fit = fitDesignCanvas(
                containerWidth = right - left,
                containerHeight = bottom - top,
                designWidth = designWidth,
                designHeight = designHeight,
            )
            for (index in 0 until childCount) {
                getChildAt(index).apply {
                    layout(0, 0, designWidth, designHeight)
                    pivotX = 0f
                    pivotY = 0f
                    scaleX = fit.scale
                    scaleY = fit.scale
                    translationX = fit.offsetX
                    translationY = fit.offsetY
                }
            }
        }

        private fun measuredSize(measureSpec: Int, fallback: Int): Int =
            when (MeasureSpec.getMode(measureSpec)) {
                MeasureSpec.UNSPECIFIED -> fallback
                else -> MeasureSpec.getSize(measureSpec)
            }
    }

    private data class SensorSlot(
        val sensor: DashboardSensor,
        val boxIndex: Int,
        val box: DashboardBoxConfig,
    )

    private data class LayoutKey(
        val pageId: String,
        val config: DashboardPageConfig,
    )

    private data class MinMaxViews(
        val minText: TextView,
        val maxText: TextView,
        val visibilityViews: List<View>,
        var renderedMin: String = "",
        var renderedMax: String = "",
    )

    private class Binding(
        val key: LayoutKey,
        val root: FrameLayout,
        val headerText: TextView,
        val boostValueText: TextView,
        val boostNeedle: RalliartBoostNeedleView,
        val boostMinMax: MinMaxViews?,
        val afrValueText: TextView,
        val afrOverlay: FrameLayout,
        val afrTargetBand: View,
        val afrMarker: View,
        val oilPressureAlarmFill: View,
        val oilPressureValueText: TextView,
        val oilPressureMinMax: MinMaxViews?,
        val oilTempAlarmFill: View,
        val oilTempValueText: TextView,
        val oilTempMinMax: MinMaxViews?,
    ) {
        var afr = Float.NaN
        var afrTargetMin = 12.8f
        var afrTargetMax = 14.2f
        var renderedBoost = ""
        var renderedAfr = ""
        var renderedOilPressure = ""
        var renderedOilTemp = ""
        var headerOutsideBits = Int.MIN_VALUE
        var headerInsideBits = Int.MIN_VALUE
        var headerBatteryBits = Int.MIN_VALUE
        var headerClock = "\u0000"

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
            val bandWidth = (bandEnd - bandStart).coerceAtLeast(6)
            val bandParams = afrTargetBand.layoutParams as FrameLayout.LayoutParams
            if (bandParams.width != bandWidth || bandParams.leftMargin != bandStart) {
                bandParams.width = bandWidth
                bandParams.leftMargin = bandStart
                afrTargetBand.layoutParams = bandParams
            }

            val markerLeft = ((total * valueFraction).toInt() - 4)
                .coerceIn(0, (total - 8).coerceAtLeast(0))
            val markerParams = afrMarker.layoutParams as FrameLayout.LayoutParams
            if (markerParams.leftMargin != markerLeft) {
                markerParams.leftMargin = markerLeft
                afrMarker.layoutParams = markerParams
            }
        }
    }

    private var binding: Binding? = null
    private val bindingCache = object : LinkedHashMap<LayoutKey, Binding>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<LayoutKey, Binding>?,
        ): Boolean = size > MAX_CACHED_LAYOUTS
    }
    private var normalizedSource: DashboardPageConfig? = null
    private var normalizedPage: DashboardPageConfig? = null

    fun render(pageConfig: DashboardPageConfig, snapshot: UtcompDataSnapshot) {
        val normalized = normalized(pageConfig)
        val boostSlot = sensorSlot(normalized, DashboardSensor.BOOST)
        val afrSlot = sensorSlot(normalized, DashboardSensor.AFR)
        val oilPressureSlot = sensorSlot(normalized, DashboardSensor.OIL_PRESSURE)
        val oilTempSlot = sensorSlot(normalized, DashboardSensor.OIL_TEMP)
        val current = ensureBinding(
            pageConfig = normalized,
            boostSlot = boostSlot,
            afrSlot = afrSlot,
            oilPressureSlot = oilPressureSlot,
            oilTempSlot = oilTempSlot,
        )

        val rawBoost = boostSlot.sensor.readValue(snapshot)
        val rawAfr = afrSlot.sensor.readValue(snapshot)
        val rawOilPressure = oilPressureSlot.sensor.readValue(snapshot)
        val rawOilTemp = oilTempSlot.sensor.readValue(snapshot)

        val boost = displayValue(boostSlot, rawBoost) ?: Float.NaN
        val afr = displayValue(afrSlot, rawAfr) ?: Float.NaN
        val oilPressure = displayValue(oilPressureSlot, rawOilPressure) ?: Float.NaN
        val oilTemp = displayValue(oilTempSlot, rawOilTemp) ?: Float.NaN
        val targetMin = afrTargetMinForBoost(boost)
        val targetMax = afrTargetMaxForBoost(boost)
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

        val oilPressureAlarm = host.alarmLevelFor(
            oilPressureSlot.box,
            rawOilPressure,
            snapshot,
        )
        val oilPressureColor = host.boxValueColor(oilPressureSlot.box, oilPressureAlarm)
        val oilTempAlarm = host.alarmLevelFor(oilTempSlot.box, rawOilTemp, snapshot)
        val oilTempColor = if (oilTempAlarm == DashboardAlarmLevel.NORMAL) {
            Color.rgb(255, 226, 226)
        } else {
            host.boxValueColor(oilTempSlot.box, oilTempAlarm)
        }

        updateHeader(current, snapshot)

        current.renderedBoost = updateValue(
            current.boostValueText,
            boost,
            boostSlot,
            boostColor,
            current.renderedBoost,
        )
        current.boostNeedle.currentValue = boost.coerceIn(-1.0f, 2.0f)
        updateMinMax(current.boostMinMax, boostSlot.sensor.name, boost, boostSlot)

        current.renderedAfr = updateValue(
            current.afrValueText,
            afr,
            afrSlot,
            afrColor,
            current.renderedAfr,
        )
        (current.afrMarker.background as? GradientDrawable)?.setColor(afrColor)
        current.afr = afr
        current.afrTargetMin = targetMin
        current.afrTargetMax = targetMax
        current.updateAfrOverlay()

        updateAlarmFill(
            current.oilPressureAlarmFill,
            alarmOverlayColor(oilPressureSlot.box, oilPressureAlarm),
        )
        current.renderedOilPressure = updateValue(
            current.oilPressureValueText,
            oilPressure,
            oilPressureSlot,
            oilPressureColor,
            current.renderedOilPressure,
        )
        updateMinMax(
            current.oilPressureMinMax,
            oilPressureSlot.sensor.name,
            oilPressure,
            oilPressureSlot,
        )

        updateAlarmFill(
            current.oilTempAlarmFill,
            alarmOverlayColor(oilTempSlot.box, oilTempAlarm),
        )
        current.renderedOilTemp = updateValue(
            current.oilTempValueText,
            oilTemp,
            oilTempSlot,
            oilTempColor,
            current.renderedOilTemp,
        )
        updateMinMax(
            current.oilTempMinMax,
            oilTempSlot.sensor.name,
            oilTemp,
            oilTempSlot,
        )
    }

    fun clear() {
        binding = null
        bindingCache.clear()
        normalizedSource = null
        normalizedPage = null
    }

    private fun normalized(pageConfig: DashboardPageConfig): DashboardPageConfig {
        if (normalizedSource === pageConfig) return normalizedPage ?: pageConfig
        return pageConfig.normalized().also { normalized ->
            normalizedSource = pageConfig
            normalizedPage = normalized
        }
    }

    private fun ensureBinding(
        pageConfig: DashboardPageConfig,
        boostSlot: SensorSlot,
        afrSlot: SensorSlot,
        oilPressureSlot: SensorSlot,
        oilTempSlot: SensorSlot,
    ): Binding {
        val key = LayoutKey(pageConfig.id, pageConfig)
        binding?.let { current ->
            if (current.key == key && current.root.parent === dashboardRoot) return current
        }

        configureViewport()
        bindingCache[key]?.let { cached ->
            attach(cached)
            binding = cached
            return cached
        }

        val created = buildDashboard(
            key,
            boostSlot,
            afrSlot,
            oilPressureSlot,
            oilTempSlot,
        )
        bindingCache[key] = created
        attach(created)
        binding = created
        return created
    }

    private fun attach(binding: Binding) {
        if (binding.root.parent === dashboardRoot) return
        (binding.root.parent as? ViewGroup)?.removeView(binding.root)
        dashboardRoot.removeAllViews()
        dashboardRoot.addView(
            binding.root,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun buildDashboard(
        key: LayoutKey,
        boostSlot: SensorSlot,
        afrSlot: SensorSlot,
        oilPressureSlot: SensorSlot,
        oilTempSlot: SensorSlot,
    ): Binding {
        val canvas = FrameLayout(activity).apply {
            setBackgroundResource(R.drawable.ralliart_dashboard_static)
            clipToPadding = false
            clipChildren = false
        }
        val root = DesignCanvasHost(activity, CANVAS_WIDTH, CANVAS_HEIGHT).apply {
            addView(canvas, FrameLayout.LayoutParams(CANVAS_WIDTH, CANVAS_HEIGHT))
        }

        val oilPressureAlarmFill = createAlarmFill().also {
            canvas.addView(it, layoutParams(OIL_PRESSURE_HIT_BOX))
        }
        val oilTempAlarmFill = createAlarmFill().also {
            canvas.addView(it, layoutParams(OIL_TEMP_HIT_BOX))
        }
        val headerText = TextView(activity).apply {
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                16f * key.config.ralliartHeaderTextScale.coerceIn(0.5f, 2.0f),
            )
            includeFontPadding = false
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            isSingleLine = true
            setTextColor(Color.rgb(236, 238, 244))
        }.also { canvas.addView(it, layoutParams(HEADER_STATUS_BOX)) }


        val boostValueText = createValueText(70f).also {
            canvas.addView(it, layoutParams(BOOST_VALUE_BOX))
        }
        val boostNeedle = RalliartBoostNeedleView(activity).apply {
            minValue = -1.0f
            maxValue = 2.0f
            warningValue = 2.0f
            showDebugGuides = false
        }.also { canvas.addView(it, layoutParams(BOOST_NEEDLE_BOX)) }
        // BOOST min/max is part of the fixed Ralliart gauge. Keep it
        // available even when an older simple-page config stored showMinMax=false.
        val boostMinMax = createStackedMinMax(
            canvas,
            boostSlot,
            BOOST_MIN_MAX_BOX,
            enabled = true,
        )

        val afrValueText = createValueText(66f).also {
            canvas.addView(it, layoutParams(AFR_VALUE_BOX))
        }
        canvas.addView(
            RalliartAfrDebugBarView(activity).apply {
                minValue = 10.0f
                maxValue = 20.0f
                showDebugGuides = false
            },
            layoutParams(AFR_DEBUG_GUIDE_BOX),
        )

        val afrOverlay = FrameLayout(activity)
        val afrTargetBand = View(activity).apply {
            background = GradientDrawable().apply {
                setColor(Color.argb(150, 76, 170, 88))
                cornerRadius = 4f
            }
        }
        val afrMarker = View(activity).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(218, 222, 228))
                cornerRadius = 5f
            }
        }
        afrOverlay.addView(
            afrTargetBand,
            FrameLayout.LayoutParams(4, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        afrOverlay.addView(
            afrMarker,
            FrameLayout.LayoutParams(8, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        canvas.addView(afrOverlay, layoutParams(AFR_LIVE_BAR_BOX))

        val oilPressureValueText = createValueText(58f).also {
            canvas.addView(it, layoutParams(OIL_PRESSURE_VALUE_BOX))
        }
        val oilPressureMinMax = createSplitMinMax(
            canvas,
            oilPressureSlot,
            OIL_PRESSURE_MIN_BOX,
            OIL_PRESSURE_MAX_BOX,
        )
        val oilTempValueText = createValueText(58f).also {
            canvas.addView(it, layoutParams(OIL_TEMP_VALUE_BOX))
        }
        val oilTempMinMax = createSplitMinMax(
            canvas,
            oilTempSlot,
            OIL_TEMP_MIN_BOX,
            OIL_TEMP_MAX_BOX,
        )

        // Keep transparent hit zones above all visual children. Some custom views
        // consume touch dispatch even when they are not clickable, which made the
        // boost min/max tap target unreliable after the renderer extraction.
        addHitZone(
            canvas,
            BOOST_HIT_BOX,
            boostSlot,
            "Ralliart • Boost",
            minMaxKey = boostSlot.sensor.name,
        )
        addHitZone(canvas, AFR_HIT_BOX, afrSlot, "Ralliart • AFR")
        addHitZone(canvas, OIL_PRESSURE_HIT_BOX, oilPressureSlot, "Ralliart • Oil pressure")
        addHitZone(canvas, OIL_TEMP_HIT_BOX, oilTempSlot, "Ralliart • Oil temp")
        addHeaderHitZone(canvas)

        val created = Binding(
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
            created.updateAfrOverlay()
        }
        return created
    }

    private fun configureViewport() {
        dashboardRoot.apply {
            setPadding(0, 0, 0, 0)
            clipToPadding = false
            clipChildren = false
            gravity = Gravity.NO_GRAVITY
            minimumWidth = 0
            minimumHeight = 0
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

    private fun addHitZone(
        root: FrameLayout,
        bounds: IntArray,
        slot: SensorSlot,
        editorTitle: String,
        minMaxKey: String? = if (slot.box.showMinMax) slot.sensor.name else null,
    ) {
        val hitZone = ClickableLinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        host.attachBoxActions(hitZone, slot.boxIndex, minMaxKey, editorTitle)
        root.addView(hitZone, layoutParams(bounds))
    }

    private fun addHeaderHitZone(root: FrameLayout) {
        val hitZone = ClickableLinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        host.attachRalliartHeaderActions(hitZone)
        root.addView(hitZone, layoutParams(HEADER_STATUS_BOX))
    }

    private fun createValueText(size: Float): TextView =
        TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            includeFontPadding = false
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

    private fun createAlarmFill(): View =
        View(activity).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = 8f
            }
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }

    private fun createStackedMinMax(
        root: FrameLayout,
        slot: SensorSlot,
        bounds: IntArray,
        enabled: Boolean = slot.box.showMinMax,
    ): MinMaxViews? {
        if (!enabled) return null
        val textSize = 16f * slot.box.minMaxScale.coerceIn(0.25f, 4.0f)
        val maxText = createMinMaxText(textSize, slot.box.maxColor)
        val minText = createMinMaxText(textSize, slot.box.minColor)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 2, 0, 2)
            isClickable = false
            isFocusable = false
            visibility = View.GONE
            addView(
                maxText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                minText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        root.addView(container, layoutParams(bounds))
        return MinMaxViews(minText, maxText, listOf(container, minText, maxText))
    }

    private fun createSplitMinMax(
        root: FrameLayout,
        slot: SensorSlot,
        minBounds: IntArray,
        maxBounds: IntArray,
    ): MinMaxViews? {
        if (!slot.box.showMinMax) return null
        val textSize = 13.5f * slot.box.minMaxScale.coerceIn(0.25f, 4.0f)
        val minText = createMinMaxText(textSize, slot.box.minColor).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        val maxText = createMinMaxText(textSize, slot.box.maxColor).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
        }
        root.addView(minText, layoutParams(minBounds))
        root.addView(maxText, layoutParams(maxBounds))
        return MinMaxViews(minText, maxText, listOf(minText, maxText))
    }

    private fun createMinMaxText(size: Float, color: Int): TextView =
        TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            setTextColor(color)
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }

    private fun updateValue(
        textView: TextView,
        value: Float,
        slot: SensorSlot,
        color: Int,
        renderedText: String,
    ): String {
        val formatted = host.formatBoxValue(value, slot.box.decimalPlaces)
        if (renderedText != formatted) {
            textView.text = host.styledValueText(formatted, slot.box.splitValueDigits)
        }
        if (textView.currentTextColor != color) textView.setTextColor(color)
        return formatted
    }

    private fun updateMinMax(
        views: MinMaxViews?,
        key: String,
        value: Float,
        slot: SensorSlot,
    ) {
        if (views == null || !value.isFinite()) {
            views?.visibilityViews?.forEach { it.visibility = View.GONE }
            return
        }
        val stats = host.trackMinMax(key, value)
        val visible = host.shouldShowMinMax(key)
        views.visibilityViews.forEach { it.visibility = if (visible) View.VISIBLE else View.GONE }
        if (!visible) return

        val maxValue = host.formatBoxValue(stats.max, slot.box.decimalPlaces)
        val minValue = host.formatBoxValue(stats.min, slot.box.decimalPlaces)
        val maxText = maxValue
        val minText = minValue
        if (views.renderedMax != maxText) {
            views.maxText.text = maxText
            views.renderedMax = maxText
        }
        if (views.renderedMin != minText) {
            views.minText.text = minText
            views.renderedMin = minText
        }
    }

    private fun updateAlarmFill(view: View, color: Int?) {
        view.visibility = if (color == null) View.GONE else View.VISIBLE
        if (color != null) (view.background as? GradientDrawable)?.setColor(color)
    }

    private fun alarmOverlayColor(
        box: DashboardBoxConfig,
        alarmLevel: DashboardAlarmLevel,
    ): Int? {
        if (!box.alarmColorsBackground) return null
        val source = when (alarmLevel) {
            DashboardAlarmLevel.CRITICAL -> box.criticalColor
            DashboardAlarmLevel.WARNING -> box.warningColor
            DashboardAlarmLevel.NORMAL -> return null
        }
        return Color.argb(210, Color.red(source), Color.green(source), Color.blue(source))
    }

    private fun sensorSlot(pageConfig: DashboardPageConfig, sensor: DashboardSensor): SensorSlot {
        val index = pageConfig.boxes.indexOfFirst { it.sensor == sensor }
        if (index >= 0) return SensorSlot(sensor, index, pageConfig.boxes[index])
        val fallback = DefaultDashboardPages.ralliart.boxes.first { it.sensor == sensor }
        return SensorSlot(sensor, -1, fallback)
    }

    private fun displayValue(slot: SensorSlot, rawValue: Float?): Float? {
        val renderIndex = if (slot.boxIndex >= 0) slot.boxIndex else 100 + slot.sensor.ordinal
        return host.displayValueForBox(slot.box, renderIndex, rawValue) ?: rawValue
    }

    private fun updateHeader(binding: Binding, snapshot: UtcompDataSnapshot) {
        val config = binding.key.config
        val outsideBits = if (config.ralliartHeaderShowOutside) {
            snapshot.temperatureDsA.toBits()
        } else {
            0
        }
        val insideBits = if (config.ralliartHeaderShowInside) {
            snapshot.temperatureDsB.toBits()
        } else {
            0
        }
        val batteryBits = if (config.ralliartHeaderShowBattery) {
            snapshot.adcInValCh0.toBits()
        } else {
            0
        }
        val clock = if (config.ralliartHeaderShowClock) host.currentClockText() else ""
        if (
            binding.headerOutsideBits == outsideBits &&
            binding.headerInsideBits == insideBits &&
            binding.headerBatteryBits == batteryBits &&
            binding.headerClock == clock
        ) {
            return
        }

        val header = StringBuilder(96).apply {
            fun appendField(value: String) {
                if (isNotEmpty()) append("   |   ")
                append(value)
            }
            if (config.ralliartHeaderShowOutside) {
                appendField("OUT ${formatTopValue(snapshot.temperatureDsA, "°C")}")
            }
            if (config.ralliartHeaderShowInside) {
                appendField("IN ${formatTopValue(snapshot.temperatureDsB, "°C")}")
            }
            if (config.ralliartHeaderShowBattery) {
                appendField(formatTopValue(snapshot.adcInValCh0, " V"))
            }
            if (config.ralliartHeaderShowClock) appendField(clock)
        }.toString()
        binding.headerText.text = header
        binding.headerOutsideBits = outsideBits
        binding.headerInsideBits = insideBits
        binding.headerBatteryBits = batteryBits
        binding.headerClock = clock
    }

    private fun formatTopValue(value: Float, suffix: String): String =
        if (value.isFinite()) host.formatBoxValue(value, 1) + suffix else "--$suffix"

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction.coerceIn(0f, 1f)

    private fun afrTargetMinForBoost(boostBar: Float): Float {
        if (!boostBar.isFinite()) return 12.8f
        val boost = boostBar.coerceIn(-1.0f, 2.0f)
        if (boost <= 0.20f) return 13.2f
        if (boost >= 2.0f) return 10.2f
        val fraction = ((boost - 0.20f) / 1.80f).coerceIn(0f, 1f)
        return lerp(12.4f, 10.2f, fraction)
    }

    private fun afrTargetMaxForBoost(boostBar: Float): Float {
        if (!boostBar.isFinite()) return 14.2f
        val boost = boostBar.coerceIn(-1.0f, 2.0f)
        if (boost <= 0.20f) return 15.0f
        if (boost >= 2.0f) return 12.5f
        val fraction = ((boost - 0.20f) / 1.80f).coerceIn(0f, 1f)
        return lerp(14.2f, 12.5f, fraction)
    }

    private fun layoutParams(bounds: IntArray): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(bounds[2], bounds[3]).apply {
            leftMargin = bounds[0]
            topMargin = bounds[1]
        }

    private companion object {
        const val MAX_CACHED_LAYOUTS = 16
        const val CANVAS_WIDTH = 1024
        const val CANVAS_HEIGHT = 600
        val HEADER_STATUS_BOX = intArrayOf(315, 14, 687, 36)
        val BOOST_HIT_BOX = intArrayOf(32, 70, 472, 472)
        val BOOST_VALUE_BOX = intArrayOf(120, 266, 310, 92)
        val BOOST_NEEDLE_BOX = intArrayOf(49, 87, 452, 452)
        val BOOST_MIN_MAX_BOX = intArrayOf(334, 392, 68, 58)
        val AFR_HIT_BOX = intArrayOf(538, 76, 494, 250)
        val AFR_VALUE_BOX = intArrayOf(652, 138, 236, 82)
        val AFR_DEBUG_GUIDE_BOX = intArrayOf(571, 240, 395, 52)
        val AFR_LIVE_BAR_BOX = intArrayOf(571, 254, 395, 18)
        val OIL_PRESSURE_HIT_BOX = intArrayOf(548, 325, 214, 197)
        val OIL_PRESSURE_VALUE_BOX = intArrayOf(580, 392, 148, 74)
        val OIL_PRESSURE_MIN_BOX = intArrayOf(558, 472, 94, 42)
        val OIL_PRESSURE_MAX_BOX = intArrayOf(660, 472, 94, 42)
        val OIL_TEMP_HIT_BOX = intArrayOf(774, 325, 214, 197)
        val OIL_TEMP_VALUE_BOX = intArrayOf(800, 392, 162, 76)
        val OIL_TEMP_MIN_BOX = intArrayOf(784, 472, 94, 42)
        val OIL_TEMP_MAX_BOX = intArrayOf(886, 472, 94, 42)
    }
}
