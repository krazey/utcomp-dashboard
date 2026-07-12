package de.krazey.utcomp.dashboard.dashboard

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal data class EditorMenuRow(
    val label: String,
    val value: String? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

internal data class EditorMenuSection(
    val title: String,
    val rows: List<EditorMenuRow>,
)

internal fun showEditorMenu(
    context: Context,
    title: String,
    sections: List<EditorMenuSection>,
) {
    lateinit var dialog: AlertDialog
    val density = context.resources.displayMetrics.density
    fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(14))
        background = GradientDrawable().apply {
            setColor(Color.rgb(15, 18, 25))
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), Color.rgb(58, 66, 82))
        }
    }

    content.addView(
        TextView(context).apply {
            text = title
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(2), 0, dp(2), dp(12))
        },
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ),
    )

    val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.rgb(21, 25, 34))
            cornerRadius = dp(10).toFloat()
        }
    }

    sections.forEachIndexed { sectionIndex, section ->
        if (sectionIndex > 0) {
            list.addView(View(context).apply {
                setBackgroundColor(Color.rgb(74, 83, 101))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2)))
        }

        list.addView(
            TextView(context).apply {
                text = section.title.uppercase()
                textSize = 13f
                letterSpacing = 0.08f
                setTextColor(Color.rgb(255, 151, 74))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(dp(14), dp(12), dp(14), dp(7))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        section.rows.forEachIndexed { rowIndex, row ->
            val rowView = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = row.enabled
                isFocusable = row.enabled
                alpha = if (row.enabled) 1f else 0.45f
                setPadding(dp(16), dp(11), dp(16), dp(11))
                if (row.enabled) {
                    background = context.getDrawable(android.R.drawable.list_selector_background)
                    setOnClickListener {
                        dialog.dismiss()
                        row.onClick()
                    }
                }

                addView(TextView(context).apply {
                    text = row.label
                    textSize = 16f
                    setTextColor(Color.rgb(245, 247, 251))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                row.value?.takeIf { it.isNotBlank() }?.let { value ->
                    addView(TextView(context).apply {
                        text = value
                        textSize = 13.5f
                        setTextColor(Color.rgb(181, 190, 207))
                        setPadding(0, dp(3), 0, 0)
                    })
                }
            }
            list.addView(
                rowView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            if (rowIndex != section.rows.lastIndex) {
                list.addView(View(context).apply {
                    setBackgroundColor(Color.rgb(45, 51, 64))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    marginStart = dp(14)
                    marginEnd = dp(14)
                })
            }
        }
    }

    val scroll = ScrollView(context).apply {
        isFillViewport = false
        addView(
            list,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }
    content.addView(
        scroll,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ),
    )

    val close = TextView(context).apply {
        text = "CLOSE"
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(123, 204, 255))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(dp(12), dp(14), dp(12), dp(10))
        isClickable = true
        isFocusable = true
        background = context.getDrawable(android.R.drawable.list_selector_background)
    }
    content.addView(
        close,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ),
    )

    dialog = AlertDialog.Builder(context)
        .setView(content)
        .create()
    close.setOnClickListener { dialog.dismiss() }
    dialog.setOnShowListener {
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = (context.resources.displayMetrics.widthPixels * 0.92f).toInt()
            val height = (context.resources.displayMetrics.heightPixels * 0.88f).toInt()
            setLayout(width, height)
        }
    }
    dialog.show()
}
