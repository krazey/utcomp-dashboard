package de.krazey.utcomp.dashboard.logging

import java.io.BufferedReader
import java.io.StringReader
import java.util.concurrent.CancellationException

fun main() {
    check(
        CsvLogPreviewReader.parseCsvLine("a,\"b,c\",\"d\"\"e\"") ==
            listOf("a", "b,c", "d\"e"),
    )

    val selected = CsvLogPreviewReader.selectCsvFields(
        "zero,\"one,still\",two,three",
        intArrayOf(3, 1, -1, 1),
    )
    check(
        selected.contentEquals(
            arrayOf("three", "one,still", null, "one,still"),
        ),
    )

    val csv = buildString {
        appendLine(
            "wall_time_ms,wall_time_iso,elapsed_realtime_ms," +
                "afr1,bar1,bar2,temperature_ntc1_c,unused",
        )
        for (index in 0 until 5_000) {
            append(1_000L + index * 20L)
            append(",2026-07-12T12:00:")
            append((index / 50).toString().padStart(2, '0'))
            append("Z,")
            append(index * 20L)
            append(",14.7,0.5,3.2,95.0,\"ignored,")
            append(index)
            appendLine("\"")
        }
    }

    val preview = CsvLogPreviewReader.read(
        BufferedReader(StringReader(csv)),
        maxGraphPoints = 1_600,
    )
    check(preview.totalRows == 5_000L)
    check(preview.graphRows.size <= 1_600)
    check(preview.stats.afr.min == 14.7f)
    check(preview.stats.boost.max == 0.5f)
    check(preview.sampleRateHz in 49.9f..50.1f)


    var progressRows = 0L
    val largeCsv = buildString {
        appendLine(
            "wall_time_ms,wall_time_iso,elapsed_realtime_ms," +
                "afr1,bar1,bar2,temperature_ntc1_c",
        )
        for (index in 0 until 100_000) {
            append(1_000L + index * 20L)
            append(",2026-07-12T12:00:00.000Z,")
            append(index * 20L)
            append(",14.7,0.5,3.2,95.0")
            appendLine()
        }
    }
    val largePreview = CsvLogPreviewReader.read(
        BufferedReader(StringReader(largeCsv)),
        maxGraphPoints = 1_600,
        onProgress = { progressRows = it },
    )
    check(largePreview.totalRows == 100_000L)
    check(largePreview.graphRows.size <= 1_600)
    check(progressRows == 100_000L)

    var cancelRequested = false
    var cancelled = false
    try {
        CsvLogPreviewReader.read(
            BufferedReader(StringReader(largeCsv)),
            maxGraphPoints = 1_600,
            isCancelled = { cancelRequested },
            onProgress = { cancelRequested = it >= 16_384L },
        )
    } catch (_: CancellationException) {
        cancelled = true
    }
    check(cancelled)

    println(
        "CSV preview tests passed: rows=${preview.totalRows}, " +
            "graph=${preview.graphRows.size}",
    )
}
