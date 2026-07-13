package de.krazey.utcomp.dashboard.dashboard

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import de.krazey.utcomp.dashboard.view.DarkActionDialog
import de.krazey.utcomp.dashboard.view.DarkActionItem

internal data class DashboardDiagnosticsState(
    val usbConnected: Boolean,
    val automaticPolling: Boolean,
    val firmware: String,
    val simulationMode: String,
)

internal class DashboardControlsController(
    private val activity: Activity,
    private val connectUsb: () -> Unit,
    private val cycleStyle: () -> Unit,
    private val managePages: () -> Unit,
    private val resetMinMax: () -> Unit,
    private val toggleMinMax: () -> Unit,
    private val toggleSubtitles: () -> Unit,
    private val showDataLog: () -> Unit,
    private val diagnosticsState: () -> DashboardDiagnosticsState,
    private val toggleAutomaticPolling: () -> Unit,
    private val requestLiveSnapshot: () -> Unit,
    private val refreshDeviceInformation: () -> Unit,
    private val clearProtocolLog: () -> Unit,
) {
    private companion object {
        val borderColor: Int = Color.rgb(38, 78, 104)
        val panelColor: Int = Color.rgb(15, 18, 24)
    }

    fun createPanel(): View {
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        controls.addView(row(
            actionButton("USB connect", connectUsb),
            actionButton("Style", cycleStyle),
            actionButton("Pages / Grid", managePages),
        ))
        controls.addView(row(
            actionButton("Min/max", toggleMinMax),
            actionButton("Reset min/max", resetMinMax),
            actionButton("Subtitles", toggleSubtitles),
        ))
        controls.addView(row(
            actionButton("Data log", showDataLog),
            actionButton("Diagnostics", ::showDiagnosticsMenu),
        ))

        return MaxHeightScrollView(activity).apply {
            visibility = View.GONE
            isFillViewport = true
            maxHeightFraction = 0.68f
            background = roundedBackground(
                color = panelColor,
                radius = dp(18f).toFloat(),
                strokeColor = borderColor,
                strokeWidth = dp(2f),
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
    }

    private fun showDiagnosticsMenu() {
        val state = diagnosticsState()
        val firmware = state.firmware.takeUnless { it.isBlank() || it == "?" } ?: "unknown"
        val status = buildString {
            append("USB ")
            append(if (state.usbConnected) "connected" else "disconnected")
            append(" · automatic polling ")
            append(if (state.automaticPolling) "on" else "off")
            append(" · firmware ")
            append(firmware)
            if (state.simulationMode != "off") {
                append(" · simulation ")
                append(state.simulationMode)
            }
        }

        DarkActionDialog.show(
            activity = activity,
            title = "Diagnostics",
            subtitle = status,
            items = listOf(
                DarkActionItem(
                    title = if (state.automaticPolling) {
                        "Disable automatic polling"
                    } else {
                        "Enable automatic polling"
                    },
                    description =
                        "Continuously requests live data and automatically reconnects USB. " +
                            "Normally this should stay enabled.",
                    onClick = toggleAutomaticPolling,
                ),
                DarkActionItem(
                    title = "Request live snapshot",
                    description =
                        "Send one complete live-data request set. Useful only for protocol " +
                            "testing; automatic polling already does this continuously.",
                    onClick = requestLiveSnapshot,
                ),
                DarkActionItem(
                    title = "Refresh device information",
                    description =
                        "Re-read firmware, channel mappings and input settings used by the " +
                            "simple-page subtitles.",
                    onClick = refreshDeviceInformation,
                ),
                DarkActionItem(
                    title = "Clear protocol log",
                    description = "Clear the bounded on-screen diagnostics log.",
                    onClick = clearProtocolLog,
                ),
            ),
        )
    }

    private fun row(vararg buttons: Button): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach(::addView)
        }

    private fun actionButton(label: String, onClick: () -> Unit): Button =
        Button(activity).apply {
            text = label
            textSize = 16f
            isAllCaps = false
            minimumHeight = dp(64f)
            setTextColor(Color.WHITE)
            background = roundedBackground(
                color = Color.BLACK,
                radius = dp(14f).toFloat(),
                strokeColor = borderColor,
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

    private fun dp(value: Float): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    private class MaxHeightScrollView(activity: Activity) : ScrollView(activity) {
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
}
