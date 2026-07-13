package de.krazey.utcomp.dashboard.logging

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import de.krazey.utcomp.dashboard.view.DarkActionDialog
import de.krazey.utcomp.dashboard.view.DarkActionItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

internal class AppDiagnosticsController(
    private val activity: Activity,
) {
    private companion object {
        private const val EXPORT_APP_LOG_REQUEST = 42_102
        private const val MAX_VIEW_CHARS = 220_000
        private val borderColor = Color.rgb(38, 78, 104)
        private val panelColor = Color.rgb(15, 18, 24)
    }

    fun showMenu() {
        DarkActionDialog.show(
            activity = activity,
            title = "App diagnostics",
            subtitle = AppDiagnostics.statusText(),
            items = listOf(
                DarkActionItem(
                    title = "View app log",
                    description =
                        "Show lifecycle, UI performance, USB recovery, CSV and error events " +
                            "recorded by UTCOMP Dashboard.",
                    onClick = ::showLogViewer,
                ),
                DarkActionItem(
                    title = "Export app log",
                    description =
                        "Save the complete current and previous app log as a text file. " +
                            "This replaces most app-focused logcat captures.",
                    onClick = ::exportLog,
                ),
                DarkActionItem(
                    title = "Clear app log",
                    description = "Delete the current and previous app diagnostic logs.",
                    accentColor = Color.rgb(255, 180, 150),
                    onClick = {
                        AppDiagnostics.clear()
                        Toast.makeText(activity, "App diagnostics cleared", Toast.LENGTH_SHORT)
                            .show()
                    },
                ),
            ),
        )
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != EXPORT_APP_LOG_REQUEST) return false
        if (resultCode != Activity.RESULT_OK) return true

        val uri = data?.data ?: run {
            AppDiagnostics.warning("EXPORT", "App diagnostics export returned no URI")
            return true
        }

        thread(name = "utcomp-app-log-export", isDaemon = true) {
            val result = runCatching {
                activity.contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use { writer ->
                    writer.write(AppDiagnostics.snapshotText())
                } ?: error("Could not open selected output file")
            }
            activity.runOnUiThread {
                result.onSuccess {
                    AppDiagnostics.info("EXPORT", "App diagnostics exported to $uri")
                    Toast.makeText(activity, "App diagnostics exported", Toast.LENGTH_SHORT)
                        .show()
                }.onFailure { error ->
                    AppDiagnostics.error("EXPORT", "App diagnostics export failed", error)
                    Toast.makeText(
                        activity,
                        "Export failed: ${error.message ?: error.javaClass.simpleName}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        return true
    }

    private fun exportLog() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "utcomp_app_diagnostics_$timestamp.log")
        }
        activity.startActivityForResult(intent, EXPORT_APP_LOG_REQUEST)
    }

    private fun showLogViewer() {
        thread(name = "utcomp-app-log-view", isDaemon = true) {
            val result = runCatching {
                val fullText = AppDiagnostics.snapshotText()
                val clipped = fullText.length > MAX_VIEW_CHARS
                if (clipped) {
                    "… older entries hidden in viewer; export for the complete log …\n\n" +
                        fullText.takeLast(MAX_VIEW_CHARS)
                } else {
                    fullText
                }
            }
            activity.runOnUiThread {
                result.onSuccess(::showLogViewerDialog)
                    .onFailure { error ->
                        AppDiagnostics.error("VIEW", "Could not open app diagnostics viewer", error)
                        Toast.makeText(
                            activity,
                            "Could not open app log: ${error.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
            }
        }
    }

    private fun showLogViewerDialog(visibleText: String) {
        val dialog = Dialog(activity)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            background = roundedBackground(panelColor, dp(18f).toFloat(), borderColor, dp(2f))
        }

        content.addView(
            TextView(activity).apply {
                text = "App diagnostics log"
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(dp(4f), 0, dp(4f), dp(8f))
            },
        )

        val logText = TextView(activity).apply {
            text = visibleText
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(220, 226, 234))
            setTextIsSelectable(true)
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        }
        content.addView(
            ScrollView(activity).apply {
                isFillViewport = true
                setBackgroundColor(Color.BLACK)
                addView(
                    logText,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        content.addView(
            Button(activity).apply {
                text = "Close"
                textSize = 16f
                isAllCaps = false
                gravity = Gravity.CENTER
                minimumHeight = dp(58f)
                setTextColor(Color.WHITE)
                background = roundedBackground(
                    Color.BLACK,
                    dp(14f).toFloat(),
                    borderColor,
                    dp(2f),
                )
                setOnClickListener { dialog.dismiss() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10f) },
        )

        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.72f }
        }
        dialog.show()
        val metrics = activity.resources.displayMetrics
        dialog.window?.setLayout(
            (metrics.widthPixels * 0.94f).toInt(),
            (metrics.heightPixels * 0.9f).toInt(),
        )
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
}
