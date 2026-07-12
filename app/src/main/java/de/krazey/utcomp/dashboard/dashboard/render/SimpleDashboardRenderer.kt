package de.krazey.utcomp.dashboard.dashboard.render

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import de.krazey.utcomp.dashboard.dashboard.DashboardBoxConfig
import de.krazey.utcomp.dashboard.dashboard.DashboardPageConfig
import de.krazey.utcomp.dashboard.dashboard.DashboardSensor
import de.krazey.utcomp.dashboard.utcomp.UtcompDataSnapshot

internal class SimpleDashboardRenderer(
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

    private data class LayoutKey(
        val pageId: String,
        val config: DashboardPageConfig,
        val editMode: Boolean,
    )

    private data class CardBinding(
        val boxIndex: Int,
        val box: DashboardBoxConfig,
        val card: LinearLayout,
        val background: GradientDrawable,
        val valueText: TextView,
        val unitText: TextView?,
        val maxText: TextView?,
        val minText: TextView?,
        val minMaxKey: String?,
        var renderedValue: String = "",
        var renderedMax: String = "",
        var renderedMin: String = "",
    )

    private data class DashboardBinding(
        val key: LayoutKey,
        val root: GridLayout,
        val cards: List<CardBinding>,
    )

    private var binding: DashboardBinding? = null
    private val bindingCache = object : LinkedHashMap<LayoutKey, DashboardBinding>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<LayoutKey, DashboardBinding>?,
        ): Boolean = size > MAX_CACHED_LAYOUTS
    }
    private var normalizedSource: DashboardPageConfig? = null
    private var normalizedPage: DashboardPageConfig? = null

    private companion object {
        const val MAX_CACHED_LAYOUTS = 24
    }

    fun render(pageConfig: DashboardPageConfig, snapshot: UtcompDataSnapshot) {
        val current = ensureBinding(normalized(pageConfig))
        current.cards.forEach { updateCard(it, snapshot) }
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

    private fun ensureBinding(pageConfig: DashboardPageConfig): DashboardBinding {
        val key = LayoutKey(pageConfig.id, pageConfig, host.isEditMode())
        binding?.let { current ->
            if (current.key == key && current.root.parent === dashboardRoot) return current
        }

        configureViewport()
        bindingCache[key]?.let { cached ->
            attach(cached)
            binding = cached
            return cached
        }

        val cardBindings = pageConfig.boxes.mapIndexed { boxIndex, box ->
            buildCard(pageConfig, boxIndex, box)
        }
        val created = DashboardBinding(key, buildGrid(pageConfig, cardBindings), cardBindings)
        bindingCache[key] = created
        attach(created)
        binding = created
        return created
    }

    private fun attach(binding: DashboardBinding) {
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

    private fun configureViewport() {
        dashboardRoot.apply {
            setBackgroundColor(Color.BLACK)
            setPadding(0, 0, 0, 0)
            clipToPadding = false
            clipChildren = false
            gravity = Gravity.NO_GRAVITY
            minimumWidth = 0
            minimumHeight = 0
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
    }

    private fun buildGrid(
        pageConfig: DashboardPageConfig,
        cards: List<CardBinding>,
    ): GridLayout {
        val occupied = pageConfig.occupiedCells()
        return GridLayout(activity).apply {
            rowCount = pageConfig.rows
            columnCount = pageConfig.columns
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.BLACK)

            cards.forEach { cardBinding ->
                val box = cardBinding.box
                addView(
                    cardBinding.card,
                    GridLayout.LayoutParams(
                        GridLayout.spec(box.row, box.rowSpan, 1f),
                        GridLayout.spec(box.column, box.columnSpan, 1f),
                    ).apply {
                        width = 0
                        height = 0
                        setMargins(6, 6, 6, 6)
                    },
                )
            }

            if (host.isEditMode()) {
                for (row in 0 until pageConfig.rows) {
                    for (column in 0 until pageConfig.columns) {
                        val cell = row * pageConfig.columns + column
                        if (cell in occupied) continue
                        addView(
                            buildEmptyCell(row, column),
                            GridLayout.LayoutParams(
                                GridLayout.spec(row, 1, 1f),
                                GridLayout.spec(column, 1, 1f),
                            ).apply {
                                width = 0
                                height = 0
                                setMargins(6, 6, 6, 6)
                            },
                        )
                    }
                }
            }
        }
    }

    private fun buildEmptyCell(row: Int, column: Int): TextView {
        val mergeState = host.mergeVisualStateForCell(row, column)
        return TextView(activity).apply {
            text = when (mergeState) {
                DashboardMergeVisualState.TARGET -> "MERGE"
                DashboardMergeVisualState.BLOCKED -> ""
                else -> "+"
            }
            textSize = if (mergeState == DashboardMergeVisualState.TARGET) 17f else 32f
            gravity = Gravity.CENTER
            setTextColor(
                if (mergeState == DashboardMergeVisualState.TARGET) {
                    Color.rgb(128, 236, 158)
                } else {
                    Color.rgb(170, 180, 198)
                },
            )
            alpha = if (mergeState == DashboardMergeVisualState.BLOCKED) 0.28f else 1f
            contentDescription = when (mergeState) {
                DashboardMergeVisualState.TARGET ->
                    "Merge into row ${row + 1}, column ${column + 1}"
                DashboardMergeVisualState.BLOCKED -> "Unavailable merge cell"
                else -> "Add box at row ${row + 1}, column ${column + 1}"
            }
            background = GradientDrawable().apply {
                setColor(Color.rgb(13, 16, 22))
                val strokeColor = if (mergeState == DashboardMergeVisualState.TARGET) {
                    Color.rgb(78, 210, 118)
                } else {
                    Color.rgb(76, 88, 108)
                }
                setStroke(if (mergeState == DashboardMergeVisualState.TARGET) 4 else 2, strokeColor, 8f, 6f)
            }
            isClickable = mergeState != DashboardMergeVisualState.BLOCKED
            isFocusable = isClickable
            setOnClickListener {
                if (mergeState == DashboardMergeVisualState.TARGET) {
                    host.selectMergeCell(row, column)
                } else {
                    host.addBoxAt(row, column)
                }
            }
        }
    }

    private fun buildCard(
        pageConfig: DashboardPageConfig,
        boxIndex: Int,
        box: DashboardBoxConfig,
    ): CardBinding {
        val sensor = box.sensor
        val minMaxKey = if (sensor == DashboardSensor.TIME || !box.showMinMax) {
            null
        } else {
            "box:$boxIndex:${sensor.name}"
        }
        val background = GradientDrawable().apply {
            setColor(box.backgroundColor)
            cornerRadius = 0f
            setStroke(2, box.borderColor)
        }
        val card = ClickableLinearLayout(activity).apply {
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
        val safeGapScale = box.iconValueGapScale.coerceIn(0.25f, 4.0f)
        val safeMinMaxScale = box.minMaxScale.coerceIn(0.25f, 4.0f)
        val iconSize = (40f * safeIconScale).toInt().coerceAtLeast(18)
        val iconGapPx = (8f * safeValueScale * safeGapScale).coerceAtLeast(2f)
        val unitGapPx = (4f * safeValueScale).toInt().coerceAtLeast(1)
        val minMaxTextSize = 11.5f * safeValueScale * safeMinMaxScale
        val minMaxPaddingY = (2f * safeValueScale * safeMinMaxScale).toInt().coerceAtLeast(2)
        val minMaxWidthPx = (58f * safeValueScale * safeMinMaxScale).toInt().coerceAtLeast(42)
        val minMaxCornerInsetPx = (5f * safeValueScale).coerceAtLeast(4f)

        var iconView: View? = null
        var unitTextView: TextView? = null
        var maxStatView: TextView? = null
        var minStatView: TextView? = null

        val content = FrameLayout(activity).apply {
            clipToPadding = false
            clipChildren = false
        }
        val valueLine = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            isBaselineAligned = false
            clipToPadding = false
            clipChildren = false
        }
        val valueTextView = TextView(activity).apply {
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
            unitTextView = TextView(activity).apply {
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
            val iconResource = host.iconDrawableResourceId(sensor.iconResourceName)
            iconView = if (iconResource != 0) {
                ImageView(activity).apply {
                    setImageResource(iconResource)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    alpha = 0.95f
                }
            } else {
                val fallbackIcon = host.fallbackIconFor(sensor)
                TextView(activity).apply {
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
            maxStatView = createMinMaxText(minMaxTextSize, box.maxColor, minMaxPaddingY)
            minStatView = createMinMaxText(minMaxTextSize, box.minColor, minMaxPaddingY)
            content.addView(
                maxStatView,
                FrameLayout.LayoutParams(minMaxWidthPx, FrameLayout.LayoutParams.WRAP_CONTENT),
            )
            content.addView(
                minStatView,
                FrameLayout.LayoutParams(minMaxWidthPx, FrameLayout.LayoutParams.WRAP_CONTENT),
            )
        }

        installContentAlignment(
            content = content,
            valueLine = valueLine,
            valueTextView = valueTextView,
            unitTextView = unitTextView,
            iconView = iconView,
            maxStatView = maxStatView,
            minStatView = minStatView,
            iconGapPx = iconGapPx,
            minMaxWidthPx = minMaxWidthPx,
            minMaxCornerInsetPx = minMaxCornerInsetPx,
        )

        card.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        host.attachBoxActions(
            view = card,
            boxIndex = boxIndex,
            minMaxKey = minMaxKey,
            editorTitle = "${pageConfig.title} • ${sensor.label}",
        )

        return CardBinding(
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

    private fun createMinMaxText(textSizeSp: Float, color: Int, paddingY: Int): TextView =
        TextView(activity).apply {
            textSize = textSizeSp
            setTextColor(color)
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            visibility = View.GONE
            setPadding(0, paddingY, 0, paddingY)
        }

    private fun installContentAlignment(
        content: FrameLayout,
        valueLine: LinearLayout,
        valueTextView: TextView,
        unitTextView: TextView?,
        iconView: View?,
        maxStatView: TextView?,
        minStatView: TextView?,
        iconGapPx: Float,
        minMaxWidthPx: Int,
        minMaxCornerInsetPx: Float,
    ) {
        val valueBounds = RectF()
        val unitBounds = RectF()
        val scratchBounds = Rect()

        fun textInkBounds(textView: TextView, out: RectF) {
            val baseline = textView.baseline
            val rawText = textView.text.toString()
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

        fun align() {
            if (content.width <= 0 || content.height <= 0 || valueLine.width <= 0 ||
                valueLine.height <= 0 || valueTextView.height <= 0
            ) return

            valueLine.translationX = 0f
            valueLine.translationY = 0f
            valueTextView.translationY = 0f
            unitTextView?.translationY = 0f

            val iconBlockWidth = if (iconView != null && iconView.width > 0) {
                iconView.width.toFloat() + iconGapPx
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

            iconView?.let { icon ->
                if (icon.width > 0 && icon.height > 0) {
                    icon.x = groupLeft
                    icon.y = targetBottom - icon.height
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

        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> align() }
        content.addOnLayoutChangeListener(listener)
        valueLine.addOnLayoutChangeListener(listener)
        valueTextView.addOnLayoutChangeListener(listener)
        unitTextView?.addOnLayoutChangeListener(listener)
        iconView?.addOnLayoutChangeListener(listener)
        maxStatView?.addOnLayoutChangeListener(listener)
        minStatView?.addOnLayoutChangeListener(listener)
        content.post { align() }
    }

    private fun updateCard(binding: CardBinding, snapshot: UtcompDataSnapshot) {
        val box = binding.box
        val sensor = box.sensor
        val rawValue = sensor.readValue(snapshot)
        val displayValue = host.displayValueForBox(box, binding.boxIndex, rawValue)
        val formattedValue = if (sensor == DashboardSensor.TIME) {
            host.currentClockText()
        } else {
            host.formatBoxValue(displayValue ?: rawValue, box.decimalPlaces)
        }

        if (binding.renderedValue != formattedValue) {
            binding.valueText.text = host.styledValueText(formattedValue, box.splitValueDigits)
            binding.renderedValue = formattedValue
        }

        val alarmLevel = host.alarmLevelFor(box, rawValue, snapshot)
        val valueColor = host.boxValueColor(box, alarmLevel)
        binding.background.setColor(host.boxBackgroundColor(box, alarmLevel))
        if (binding.valueText.currentTextColor != valueColor) {
            binding.valueText.setTextColor(valueColor)
        }
        val effectiveUnitColor = if (alarmLevel == DashboardAlarmLevel.NORMAL) {
            box.unitColor
        } else {
            valueColor
        }
        binding.unitText?.let { unitText ->
            if (unitText.currentTextColor != effectiveUnitColor) {
                unitText.setTextColor(effectiveUnitColor)
            }
        }
        val mergeState = host.mergeVisualStateForBox(binding.boxIndex)
        when (mergeState) {
            DashboardMergeVisualState.SOURCE -> {
                binding.background.setStroke(5, Color.rgb(92, 180, 255))
                binding.card.alpha = 1f
            }
            DashboardMergeVisualState.TARGET -> {
                binding.background.setStroke(5, Color.rgb(78, 210, 118))
                binding.card.alpha = 1f
            }
            DashboardMergeVisualState.BLOCKED -> {
                binding.background.setStroke(2, Color.rgb(55, 62, 76))
                binding.card.alpha = 0.32f
            }
            DashboardMergeVisualState.NONE -> {
                binding.background.setStroke(2, box.borderColor)
                binding.card.alpha = if (host.isEditMode()) 0.86f else 1.0f
            }
        }

        val minMaxKey = binding.minMaxKey
        val stats = if (minMaxKey != null && rawValue != null && rawValue.isFinite()) {
            host.trackMinMax(minMaxKey, rawValue)
        } else {
            null
        }
        val visibleStats = if (
            stats != null && minMaxKey != null && host.shouldShowMinMax(minMaxKey)
        ) {
            stats
        } else {
            null
        }
        val visibility = if (visibleStats != null) View.VISIBLE else View.GONE
        if (binding.maxText?.visibility != visibility) binding.maxText?.visibility = visibility
        if (binding.minText?.visibility != visibility) binding.minText?.visibility = visibility
        visibleStats?.let { currentStats ->
            val maxText = host.formatBoxValue(currentStats.max, box.decimalPlaces)
            val minText = host.formatBoxValue(currentStats.min, box.decimalPlaces)
            if (binding.renderedMax != maxText) {
                binding.maxText?.text = maxText
                binding.renderedMax = maxText
            }
            if (binding.renderedMin != minText) {
                binding.minText?.text = minText
                binding.renderedMin = minText
            }
        }
    }
}
