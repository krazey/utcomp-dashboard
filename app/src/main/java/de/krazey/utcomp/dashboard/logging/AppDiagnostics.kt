package de.krazey.utcomp.dashboard.logging

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal object AppDiagnostics {
    private const val TAG = "UTCOMPDiagnostics"
    private const val MAX_FILE_BYTES = 1_500_000L
    private const val SNAPSHOT_TIMEOUT_MS = 2_000L

    private val initialized = AtomicBoolean(false)
    private val entryCount = AtomicLong(0L)
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val formatterLock = Any()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "utcomp-app-diagnostics").apply { isDaemon = true }
    }

    private lateinit var currentFile: File
    private lateinit var previousFile: File
    private var writer: BufferedWriter? = null
    private var currentBytes = 0L

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        val dir = File(appContext.filesDir, "diagnostics").apply { mkdirs() }
        currentFile = File(dir, "utcomp-app.log")
        previousFile = File(dir, "utcomp-app.previous.log")
        currentBytes = currentFile.length()

        installCrashHandler()

        val packageInfo = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrNull()
        val versionName = packageInfo?.versionName ?: "unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode?.toString() ?: "unknown"
        } else {
            @Suppress("DEPRECATION")
            packageInfo?.versionCode?.toString() ?: "unknown"
        }

        info("APP", "=== UTCOMP Dashboard session started ===")
        info(
            "APP",
            "version=$versionName ($versionCode) package=${appContext.packageName}",
        )
        info(
            "DEVICE",
            "${Build.MANUFACTURER} ${Build.MODEL} device=${Build.DEVICE} " +
                "sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}",
        )
    }

    fun info(category: String, message: String) {
        record("I", category, message, null, mirrorToLogcat = true)
    }

    fun capture(category: String, message: String) {
        record("I", category, message, null, mirrorToLogcat = false)
    }

    fun warning(category: String, message: String) {
        record("W", category, message, null, mirrorToLogcat = true)
    }

    fun error(category: String, message: String, throwable: Throwable? = null) {
        record("E", category, message, throwable, mirrorToLogcat = true)
    }

    fun statusText(): String {
        if (!initialized.get()) return "App diagnostics unavailable"
        val bytes = currentFile.length() + previousFile.length()
        return "${entryCount.get()} entries · ${formatBytes(bytes)} · stored in app-private storage"
    }

    fun snapshotText(): String {
        if (!initialized.get()) return "App diagnostics unavailable."
        flushPending()

        return buildString {
            if (previousFile.isFile && previousFile.length() > 0L) {
                appendLine("===== PREVIOUS APP LOG =====")
                append(previousFile.readText())
                if (!endsWith('\n')) appendLine()
            }
            appendLine("===== CURRENT APP LOG =====")
            if (currentFile.isFile && currentFile.length() > 0L) {
                append(currentFile.readText())
            } else {
                appendLine("No entries recorded.")
            }
        }
    }

    fun clear() {
        if (!initialized.get()) return
        val future = executor.submit {
            closeWriter()
            currentFile.delete()
            previousFile.delete()
            currentBytes = 0L
            entryCount.set(1L)
            openWriter()
            appendLineInternal(formatLine("I", "APP", "App diagnostics log cleared", null))
        }
        runCatching { future.get(SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            .onFailure { error -> Log.e(TAG, "Could not clear app diagnostics", error) }
    }

    private fun record(
        level: String,
        category: String,
        message: String,
        throwable: Throwable?,
        mirrorToLogcat: Boolean,
    ) {
        if (!initialized.get()) return

        val safeCategory = category.trim().ifBlank { "APP" }.uppercase(Locale.US)
        val line = formatLine(level, safeCategory, message, throwable)
        if (mirrorToLogcat) {
            when (level) {
                "E" -> Log.e(TAG, "[$safeCategory] $message", throwable)
                "W" -> Log.w(TAG, "[$safeCategory] $message", throwable)
                else -> Log.i(TAG, "[$safeCategory] $message")
            }
        }

        entryCount.incrementAndGet()
        executor.execute {
            runCatching { appendLineInternal(line) }
                .onFailure { error -> Log.e(TAG, "Could not append app diagnostics", error) }
        }
    }

    private fun formatLine(
        level: String,
        category: String,
        message: String,
        throwable: Throwable?,
    ): String = buildString(message.length + 160) {
        val wallTime = synchronized(formatterLock) { formatter.format(Date()) }
        append(wallTime)
        append("  +")
        append(SystemClock.elapsedRealtime())
        append("ms  ")
        append(level)
        append('/')
        append(category)
        append("  [")
        append(Thread.currentThread().name)
        append("]  ")
        append(message.replace('\u0000', '?'))
        append('\n')
        if (throwable != null) {
            append(throwable.stackTraceToString())
            if (!endsWith('\n')) append('\n')
        }
    }

    private fun appendLineInternal(line: String) {
        val encodedBytes = line.toByteArray(Charsets.UTF_8).size.toLong()
        if (currentBytes + encodedBytes > MAX_FILE_BYTES) rotateFiles()

        val out = writer ?: openWriter()
        out.write(line)
        out.flush()
        currentBytes += encodedBytes
    }

    private fun openWriter(): BufferedWriter {
        val opened = BufferedWriter(
            OutputStreamWriter(FileOutputStream(currentFile, true), Charsets.UTF_8),
        )
        writer = opened
        return opened
    }

    private fun rotateFiles() {
        closeWriter()
        previousFile.delete()
        if (currentFile.exists() && !currentFile.renameTo(previousFile)) {
            runCatching {
                currentFile.copyTo(previousFile, overwrite = true)
                currentFile.delete()
            }
        }
        currentBytes = 0L
    }

    private fun closeWriter() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
    }

    private fun flushPending() {
        val future = executor.submit { writer?.flush() }
        runCatching { future.get(SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
    }

    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val line = formatLine(
                level = "E",
                category = "CRASH",
                message = "Uncaught exception on ${thread.name}",
                throwable = throwable,
            )
            Log.e(TAG, "[CRASH] Uncaught exception on ${thread.name}", throwable)
            entryCount.incrementAndGet()
            if (thread.name == "utcomp-app-diagnostics") {
                runCatching { appendLineInternal(line) }
            } else {
                executor.execute { appendLineInternal(line) }
                flushPending()
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_048_576L -> "%.1f MiB".format(Locale.US, bytes / 1_048_576.0)
        bytes >= 1_024L -> "%.1f KiB".format(Locale.US, bytes / 1_024.0)
        else -> "$bytes B"
    }
}
