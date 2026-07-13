package de.krazey.utcomp.dashboard.logging

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import de.krazey.utcomp.dashboard.utcomp.UtcompDataSnapshot
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class UtcompCsvLogger(
    private val context: Context,
    private val uiLog: (String) -> Unit,
    private val onStateChanged: (Boolean) -> Unit = {},
) : Closeable {
    companion object {
        private const val QUEUE_CAPACITY = 10_000

        private val HEADER = listOf(
            "seq",
            "wall_time_ms",
            "wall_time_iso",
            "elapsed_realtime_ms",
            "elapsed_realtime_nanos",
            "source",
            "firmware",
            "utcomp_pro",
            "temperature_ds_a_c",
            "temperature_ds_b_c",
            "temperature_ds_c_c",
            "temperature_ds_d_c",
            "temperature_ntc1_c",
            "temperature_ntc2_c",
            "temperature_ntc3_c",
            "temperature_outside_c",
            "temperature_inside_c",
            "temperature_engine_c",
            "temperature_oil_c",
            "temperature_user1_c",
            "temperature_user2_c",
            "vss_speed_1s",
            "vss_speed_200ms",
            "distance",
            "distance_dt",
            "vss_imp_1s",
            "vss_imp_200ms",
            "vss_imp_180s",
            "vss_imp_pb_total",
            "vss_imp_lpg_total",
            "consumption_avg",
            "consumption_cur",
            "fuel_left_pb",
            "fuel_left_lpg",
            "fuel_type_current",
            "trip_cpl_pb",
            "trip_cpl_lpg",
            "trip_cpkm",
            "injection_time_1s",
            "injection_time_180s",
            "injection_time_pb_total",
            "injection_time_lpg_total",
            "adc_in_ch0",
            "adc_in_ch1",
            "adc_in_ch2",
            "adc_in_ch3",
            "adc_in_ch4",
            "adc_in_ch5",
            "adc_in_ch6",
            "adc_in_ch7",
            "vref",
            "logger_flash_addr_cur",
            "dig_out_user_state",
            "gear_no",
            "bar1",
            "bar2",
            "bar3",
            "afr1",
            "afr2",
            "egt1",
            "egt2",
            "egt3",
            "egt4",
            "egt5",
            "egt6",
            "rpm",
            "trip_travel_time_1s",
            "ignition_time_1s",
            "trip_cons",
            "trip_dist",
            "trip_qty",
            "trip_cost",
            "trip_vavg",
            "vmax",
        ).joinToString(",")
    }

    private data class CsvSample(
        val seq: Long,
        val wallTimeMs: Long,
        val elapsedRealtimeMs: Long,
        val elapsedRealtimeNanos: Long,
        val source: String,
        val snapshot: UtcompDataSnapshot,
    )

    private val queue = LinkedBlockingQueue<CsvSample>(QUEUE_CAPACITY)

    @Volatile private var running = false
    @Volatile private var droppedRows = 0L
    @Volatile private var writtenRows = 0L

    private var writerThread: Thread? = null
    private var currentTarget: String = "off"
    private var sequence = 0L

    val isRunning: Boolean
        get() = running

    fun startInternal() {
        val dir = File(context.filesDir, "utcomp-logs").apply { mkdirs() }
        val file = File(dir, makeFileName())
        start(file.bufferedWriter(), "internal:${file.absolutePath}")
    }

    fun startAppExternal() {
        val dir = File(context.getExternalFilesDir("utcomp-logs") ?: context.filesDir, "csv").apply { mkdirs() }
        val file = File(dir, makeFileName())
        start(file.bufferedWriter(), "app-external:${file.absolutePath}")
    }

    fun startTree(treeUri: Uri) {
        val resolver = context.contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        val fileUri = DocumentsContract.createDocument(
            resolver,
            parentUri,
            "text/csv",
            makeFileName(),
        ) ?: error("Could not create CSV file in selected folder")

        val stream = resolver.openOutputStream(fileUri, "w")
            ?: error("Could not open CSV output stream")
        start(BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8)), "folder:$fileUri")
    }

    private fun start(writer: BufferedWriter, target: String) {
        stop()

        queue.clear()
        droppedRows = 0L
        writtenRows = 0L
        sequence = 0L
        currentTarget = target
        running = true
        notifyStateChanged(true)

        writerThread = thread(name = "utcomp-csv-logger", isDaemon = true) {
            runWriter(writer, target)
        }

        uiLog("CSV data logging started: $target")
    }

    fun offer(snapshot: UtcompDataSnapshot, source: String = "usb") {
        if (!running) return

        val sample = CsvSample(
            seq = ++sequence,
            wallTimeMs = System.currentTimeMillis(),
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
            source = source,
            snapshot = snapshot.copy(),
        )

        if (!queue.offer(sample)) {
            queue.poll()
            droppedRows++
            queue.offer(sample)
        }
    }

    fun statusText(): String =
        if (running) {
            "CSV logging ON: written=$writtenRows queued=${queue.size} dropped=$droppedRows target=$currentTarget"
        } else {
            "CSV logging OFF"
        }

    fun stop() {
        if (!running && writerThread == null) return

        val stoppedTarget = currentTarget
        running = false
        writerThread?.join(1200)
        writerThread = null

        currentTarget = "off"
        notifyStateChanged(false)
        uiLog("CSV data logging stopped: rows=$writtenRows dropped=$droppedRows target=$stoppedTarget")
    }

    override fun close() {
        stop()
    }

    private fun runWriter(writer: BufferedWriter, target: String) {
        try {
            writer.use { out ->
                val isoTimeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
                out.write(HEADER)
                out.newLine()

                var rowsSinceFlush = 0
                while (running || queue.isNotEmpty()) {
                    val sample = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    out.write(rowFor(
                        seq = sample.seq,
                        wallTimeMs = sample.wallTimeMs,
                        elapsedRealtimeMs = sample.elapsedRealtimeMs,
                        elapsedRealtimeNanos = sample.elapsedRealtimeNanos,
                        source = sample.source,
                        s = sample.snapshot,
                        isoTimeFormat = isoTimeFormat,
                    ))
                    out.newLine()
                    writtenRows++
                    rowsSinceFlush++

                    if (rowsSinceFlush >= 64) {
                        out.flush()
                        rowsSinceFlush = 0
                    }
                }
                out.flush()
            }
        } catch (t: Throwable) {
            uiLog("CSV logging failed for $target: ${t.message}")
        } finally {
            val shouldNotify = running || currentTarget == target
            running = false
            if (currentTarget == target) currentTarget = "off"
            if (shouldNotify) notifyStateChanged(false)
        }
    }

    private fun rowFor(
        seq: Long,
        wallTimeMs: Long,
        elapsedRealtimeMs: Long,
        elapsedRealtimeNanos: Long,
        source: String,
        s: UtcompDataSnapshot,
        isoTimeFormat: SimpleDateFormat,
    ): String = buildString(768) {
        csvField(seq)
        csvField(wallTimeMs)
        csvQuotedField(isoTimeFormat.format(Date(wallTimeMs)))
        csvField(elapsedRealtimeMs)
        csvField(elapsedRealtimeNanos)
        csvQuotedField(source)
        csvQuotedField(s.firmware)
        csvField(s.utcompPro)
        csvField(s.temperatureDsA)
        csvField(s.temperatureDsB)
        csvField(s.temperatureDsC)
        csvField(s.temperatureDsD)
        csvField(s.temperatureNtc1)
        csvField(s.temperatureNtc2)
        csvField(s.temperatureNtc3)
        csvField(s.temperatureOutside)
        csvField(s.temperatureInside)
        csvField(s.temperatureEngine)
        csvField(s.temperatureOil)
        csvField(s.temperatureUser1)
        csvField(s.temperatureUser2)
        csvField(s.vssSpeed1s)
        csvField(s.vssSpeed200ms)
        csvField(s.distance)
        csvField(s.distanceDt)
        csvField(s.vssImp1s)
        csvField(s.vssImp200ms)
        csvField(s.vssImp180s)
        csvField(s.vssImpPbTotal)
        csvField(s.vssImpLpgTotal)
        csvField(s.consumptionAvg)
        csvField(s.consumptionCur)
        csvField(s.fuelLeftPb)
        csvField(s.fuelLeftLpg)
        csvField(s.fuelTypeCurrent)
        csvField(s.tripCplPb)
        csvField(s.tripCplLpg)
        csvField(s.tripCpkm)
        csvField(s.injectionTime1s)
        csvField(s.injectionTime180s)
        csvField(s.injectionTimePbTotal)
        csvField(s.injectionTimeLpgTotal)
        csvField(s.adcInValCh0)
        csvField(s.adcInValCh1)
        csvField(s.adcInValCh2)
        csvField(s.adcInValCh3)
        csvField(s.adcInValCh4)
        csvField(s.adcInValCh5)
        csvField(s.adcInValCh6)
        csvField(s.adcInValCh7)
        csvField(s.vref)
        csvField(s.loggerFlashAddrCur)
        csvField(s.digOutUserState)
        csvField(s.gearNo)
        csvField(s.bar1)
        csvField(s.bar2)
        csvField(s.bar3)
        csvField(s.afr1)
        csvField(s.afr2)
        csvField(s.egt1)
        csvField(s.egt2)
        csvField(s.egt3)
        csvField(s.egt4)
        csvField(s.egt5)
        csvField(s.egt6)
        csvField(s.rpm)
        csvField(s.tripTravelTime1s)
        csvField(s.ignitionTime1s)
        csvField(s.tripCons)
        csvField(s.tripDist)
        csvField(s.tripQty)
        csvField(s.tripCost)
        csvField(s.tripVavg)
        csvField(s.vmax)
    }

    private fun StringBuilder.csvSeparator() {
        if (isNotEmpty()) append(',')
    }

    private fun StringBuilder.csvField(value: Long) {
        csvSeparator()
        append(value)
    }

    private fun StringBuilder.csvField(value: Int) {
        csvSeparator()
        append(value)
    }

    private fun StringBuilder.csvField(value: Float) {
        csvSeparator()
        append(value)
    }

    private fun StringBuilder.csvField(value: Boolean) {
        csvSeparator()
        append(value)
    }

    private fun StringBuilder.csvQuotedField(value: String) {
        csvSeparator()
        append('"')
        value.forEach { character ->
            if (character == '"') append('"')
            append(character)
        }
        append('"')
    }

    private fun notifyStateChanged(isRunning: Boolean) {
        runCatching { onStateChanged(isRunning) }
            .onFailure { error ->
                uiLog("CSV state callback failed: ${error.message}")
            }
    }

    private fun makeFileName(): String =
        "utcomp_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"

}
