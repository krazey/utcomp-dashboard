package de.krazey.utcomp.probe.logging

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import de.krazey.utcomp.probe.utcomp.UtcompDataSnapshot
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
) : Closeable {
    companion object {
        private const val QUEUE_CAPACITY = 10_000

        private val HEADER = listOf(
            "seq",
            "wall_time_ms",
            "wall_time_iso",
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

        running = false
        writerThread?.join(1200)
        writerThread = null

        val stoppedTarget = currentTarget
        currentTarget = "off"
        uiLog("CSV data logging stopped: rows=$writtenRows dropped=$droppedRows target=$stoppedTarget")
    }

    override fun close() {
        stop()
    }

    private fun runWriter(writer: BufferedWriter, target: String) {
        try {
            writer.use { out ->
                out.write(HEADER)
                out.newLine()

                var rowsSinceFlush = 0
                while (running || queue.isNotEmpty()) {
                    val sample = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    out.write(rowFor(sample.seq, sample.wallTimeMs, sample.source, sample.snapshot))
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
            running = false
        }
    }

    private fun rowFor(seq: Long, wallTimeMs: Long, source: String, s: UtcompDataSnapshot): String =
        listOf(
            seq.toString(),
            wallTimeMs.toString(),
            csv(isoTime(wallTimeMs)),
            csv(source),
            csv(s.firmware),
            s.utcompPro.toString(),
            f(s.temperatureDsA),
            f(s.temperatureDsB),
            f(s.temperatureDsC),
            f(s.temperatureDsD),
            f(s.temperatureNtc1),
            f(s.temperatureNtc2),
            f(s.temperatureNtc3),
            f(s.temperatureOutside),
            f(s.temperatureInside),
            f(s.temperatureEngine),
            f(s.temperatureOil),
            f(s.temperatureUser1),
            f(s.temperatureUser2),
            s.vssSpeed1s.toString(),
            s.vssSpeed200ms.toString(),
            f(s.distance),
            f(s.distanceDt),
            s.vssImp1s.toString(),
            s.vssImp200ms.toString(),
            s.vssImp180s.toString(),
            s.vssImpPbTotal.toString(),
            s.vssImpLpgTotal.toString(),
            f(s.consumptionAvg),
            f(s.consumptionCur),
            f(s.fuelLeftPb),
            f(s.fuelLeftLpg),
            s.fuelTypeCurrent.toString(),
            f(s.tripCplPb),
            f(s.tripCplLpg),
            f(s.tripCpkm),
            f(s.injectionTime1s),
            f(s.injectionTime180s),
            f(s.injectionTimePbTotal),
            f(s.injectionTimeLpgTotal),
            f(s.adcInValCh0),
            f(s.adcInValCh1),
            f(s.adcInValCh2),
            f(s.adcInValCh3),
            f(s.adcInValCh4),
            f(s.adcInValCh5),
            f(s.adcInValCh6),
            f(s.adcInValCh7),
            s.vref.toString(),
            s.loggerFlashAddrCur.toString(),
            s.digOutUserState.toString(),
            s.gearNo.toString(),
            f(s.bar1),
            f(s.bar2),
            f(s.bar3),
            f(s.afr1),
            f(s.afr2),
            s.egt1.toString(),
            s.egt2.toString(),
            s.egt3.toString(),
            s.egt4.toString(),
            s.egt5.toString(),
            s.egt6.toString(),
            s.rpm.toString(),
            s.tripTravelTime1s.toString(),
            s.ignitionTime1s.toString(),
            f(s.tripCons),
            f(s.tripDist),
            f(s.tripQty),
            f(s.tripCost),
            f(s.tripVavg),
            s.vmax.toString(),
        ).joinToString(",")

    private fun makeFileName(): String =
        "utcomp_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"

    private fun isoTime(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(ms))

    private fun f(value: Float): String =
        if (value.isNaN()) "NaN" else java.lang.Float.toString(value)

    private fun csv(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
