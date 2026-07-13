package de.krazey.utcomp.dashboard.dashboard

import kotlin.math.exp

/** Converts a time constant into a sample-interval-specific EMA coefficient. */
fun smoothingAlphaForInterval(
    elapsedMs: Long,
    timeConstantSeconds: Float,
): Float {
    if (elapsedMs <= 0L || timeConstantSeconds <= 0f) return 1f
    val elapsedSeconds = elapsedMs / 1_000f
    return (1.0 - exp(-elapsedSeconds / timeConstantSeconds)).toFloat()
        .coerceIn(0.01f, 1f)
}
