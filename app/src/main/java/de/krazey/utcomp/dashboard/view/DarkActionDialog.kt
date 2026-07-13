package de.krazey.utcomp.dashboard.view

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal data class DarkActionItem(
    val title: String,
    val description: String = "",
    val accentColor: Int = Color.WHITE,
    val onClick: () -> Unit,
)

internal object DarkActionDialog {
    private val panelColor = Color.rgb(15, 18, 24)
    private val borderColor = Color.rgb(38, 78, 104)
    private val secondaryTextColor = Color.rgb(170, 180, 194)

    fun show(
        activity: Activity,
        title: String,
        subtitle: String = "",
        items: List<DarkActionItem>,
        closeLabel: String = "Close",
    ) {
        val dialog = Dialog(activity)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 14f), dp(activity, 14f), dp(activity, 14f), dp(activity, 14f))
            background = roundedBackground(
                color = panelColor,
                radius = dp(activity, 18f).toFloat(),
                strokeColor = borderColor,
                strokeWidth = dp(activity, 2f),
            )
        }

        content.addView(
            TextView(activity).apply {
                text = title
                textSize = 20f
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.START
                setPadding(dp(activity, 4f), 0, dp(activity, 4f), dp(activity, 4f))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        if (subtitle.isNotBlank()) {
            content.addView(
                TextView(activity).apply {
                    text = subtitle
                    textSize = 12.5f
                    setTextColor(secondaryTextColor)
                    setPadding(
                        dp(activity, 4f),
                        0,
                        dp(activity, 4f),
                        dp(activity, 10f),
                    )
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        items.forEach { item ->
            content.addView(
                actionView(activity, item) {
                    dialog.dismiss()
                    item.onClick()
                },
            )
        }

        content.addView(
            actionView(
                activity = activity,
                item = DarkActionItem(
                    title = closeLabel,
                    description = "Return to the dashboard",
                    accentColor = Color.rgb(150, 210, 245),
                    onClick = {},
                ),
                onClick = dialog::dismiss,
            ),
        )

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        dialog.setContentView(scroll)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.72f }
        }
        dialog.show()

        val metrics = activity.resources.displayMetrics
        val width = minOf((metrics.widthPixels * 0.92f).toInt(), dp(activity, 760f))
        val height = minOf((metrics.heightPixels * 0.88f).toInt(), dp(activity, 720f))
        dialog.window?.setLayout(width, height)
    }

    private fun actionView(
        activity: Activity,
        item: DarkActionItem,
        onClick: () -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(activity, 64f)
        isClickable = true
        isFocusable = true
        contentDescription = buildString {
            append(item.title)
            if (item.description.isNotBlank()) append(". ${item.description}")
        }
        background = roundedBackground(
            color = Color.BLACK,
            radius = dp(activity, 14f).toFloat(),
            strokeColor = borderColor,
            strokeWidth = dp(activity, 2f),
        )
        setPadding(
            dp(activity, 14f),
            dp(activity, 9f),
            dp(activity, 14f),
            dp(activity, 9f),
        )
        setOnClickListener { onClick() }

        addView(
            TextView(activity).apply {
                text = item.title
                textSize = 16f
                setTextColor(item.accentColor)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        if (item.description.isNotBlank()) {
            addView(
                TextView(activity).apply {
                    text = item.description
                    textSize = 11.5f
                    setTextColor(secondaryTextColor)
                    setPadding(0, dp(activity, 2f), 0, 0)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            val margin = dp(activity, 4f)
            setMargins(0, margin, 0, margin)
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

    private fun dp(activity: Activity, value: Float): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
