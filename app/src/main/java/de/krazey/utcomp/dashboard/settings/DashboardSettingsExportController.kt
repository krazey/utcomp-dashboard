package de.krazey.utcomp.dashboard.settings

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import de.krazey.utcomp.dashboard.view.DarkActionDialog
import de.krazey.utcomp.dashboard.view.DarkActionItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

internal class DashboardSettingsExportController(
    private val activity: Activity,
    private val snapshotProvider: () -> DashboardSettingsSnapshot,
    private val prepareImport: (DashboardSettingsSnapshot) -> DashboardSettingsSnapshot,
    private val applyImport: (DashboardSettingsSnapshot) -> Boolean,
    private val appendLog: (String) -> Unit,
) {
    private companion object {
        const val EXPORT_SETTINGS_REQUEST = 42_103
        const val IMPORT_SETTINGS_REQUEST = 42_104
        const val MAX_IMPORT_CHARACTERS = 1_000_000
    }

    private var pendingExportText: String? = null

    fun export() {
        val now = Date()
        val filenameTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
        val isoTimestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(now)

        pendingExportText = runCatching {
            DashboardSettingsExport.encode(snapshotProvider(), isoTimestamp)
        }.getOrElse { error ->
            appendLog(
                "App settings export preparation failed: " +
                    (error.message ?: error.javaClass.simpleName),
            )
            Toast.makeText(
                activity,
                "Could not prepare settings export",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "utcomp_dashboard_settings_$filenameTimestamp.json")
        }
        activity.startActivityForResult(intent, EXPORT_SETTINGS_REQUEST)
    }

    fun importSettings() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        activity.startActivityForResult(intent, IMPORT_SETTINGS_REQUEST)
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == IMPORT_SETTINGS_REQUEST) {
            if (resultCode == Activity.RESULT_OK) {
                data?.data?.let(::readImport)
                    ?: appendLog("App settings import returned no file")
            }
            return true
        }
        if (requestCode != EXPORT_SETTINGS_REQUEST) return false
        val exportText = pendingExportText
        pendingExportText = null
        if (resultCode != Activity.RESULT_OK) return true

        val uri = data?.data
        if (uri == null || exportText == null) {
            appendLog("App settings export returned no destination")
            return true
        }

        thread(name = "utcomp-settings-export", isDaemon = true) {
            val result = runCatching {
                activity.contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use {
                    it.write(exportText)
                } ?: error("Could not open selected output file")
            }
            activity.runOnUiThread {
                result.onSuccess {
                    appendLog("App settings exported")
                    Toast.makeText(activity, "App settings exported", Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    appendLog(
                        "App settings export failed: " +
                            (error.message ?: error.javaClass.simpleName),
                    )
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

    private fun readImport(uri: android.net.Uri) {
        thread(name = "utcomp-settings-import", isDaemon = true) {
            val decoded = runCatching {
                val raw = activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                    val text = StringBuilder()
                    val buffer = CharArray(8_192)
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        require(text.length + count <= MAX_IMPORT_CHARACTERS) {
                            "Settings file is too large"
                        }
                        text.append(buffer, 0, count)
                    }
                    text.toString()
                } ?: error("Could not open selected settings file")
                DashboardSettingsImport.decode(raw)
            }
            activity.runOnUiThread {
                decoded.onSuccess(::prepareAndConfirmImport)
                    .onFailure(::showImportFailure)
            }
        }
    }

    private fun prepareAndConfirmImport(imported: ImportedDashboardSettings) {
        val prepared = runCatching { prepareImport(imported.snapshot) }
            .getOrElse {
                showImportFailure(it)
                return
            }
        val pageCount = runCatching {
            org.json.JSONArray(prepared.simplePagesJson).length()
        }.getOrDefault(0)
        DarkActionDialog.show(
            activity = activity,
            title = "Import app settings?",
            subtitle =
                "Backup from UTCOMP Dashboard ${prepared.appVersion}, " +
                    "exported ${imported.exportedAtUtc}, " +
                    "$pageCount simple page${if (pageCount == 1) "" else "s"}, " +
                    "style ${prepared.dashboardStyle.lowercase(Locale.US)}. " +
                    "This replaces the current app layout, display, Live Data, " +
                    "filter, and logging preferences. Controller settings are not changed.",
            items = listOf(
                DarkActionItem(
                    title = "Import and restart",
                    description =
                        "Apply this validated backup and restart the dashboard once.",
                    onClick = {
                        runCatching {
                            check(applyImport(prepared)) {
                                "Could not save all imported settings"
                            }
                        }.onFailure(::showImportFailure)
                    },
                ),
            ),
            closeLabel = "Cancel",
        )
    }

    private fun showImportFailure(error: Throwable) {
        val message = error.message ?: error.javaClass.simpleName
        appendLog("App settings import failed: $message")
        Toast.makeText(
            activity,
            "Import failed: $message",
            Toast.LENGTH_LONG,
        ).show()
    }
}
