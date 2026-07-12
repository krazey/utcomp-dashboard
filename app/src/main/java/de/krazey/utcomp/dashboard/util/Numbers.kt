package de.krazey.utcomp.dashboard.util

import kotlin.math.abs
import kotlin.math.roundToLong

/** Formats small live-data values without constructing a java.util.Formatter. */
fun Float.fixed(decimals: Int, invalid: String = "--"): String {
    if (!isFinite()) return invalid

    val places = decimals.coerceIn(0, 3)
    val scale = when (places) {
        0 -> 1L
        1 -> 10L
        2 -> 100L
        else -> 1000L
    }
    val scaled = (this * scale).roundToLong()
    if (places == 0) return scaled.toString()

    val magnitude = abs(scaled)
    val whole = magnitude / scale
    val fraction = (magnitude % scale).toString().padStart(places, '0')
    return buildString(whole.toString().length + places + 2) {
        if (scaled < 0L) append('-')
        append(whole)
        append('.')
        append(fraction)
    }
}
