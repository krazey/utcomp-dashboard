package de.krazey.utcomp.dashboard.logging

import de.krazey.utcomp.dashboard.dashboard.smoothingAlphaForInterval
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private fun assertNear(expected: Float, actual: Float, tolerance: Float) {
    check(abs(expected - actual) <= tolerance) {
        "expected=$expected actual=$actual tolerance=$tolerance"
    }
}

private fun wave(
    timeMs: Long,
    originMs: Long,
    frequencyHz: Double,
    amplitude: Double,
    phaseRadians: Double,
): Float {
    val seconds = (timeMs - originMs) / 1_000.0
    return (amplitude * sin(2.0 * PI * frequencyHz * seconds + phaseRadians)).toFloat()
}

fun main() {
    val frequency = 0.394
    val origin = 10_000L
    val referencePhase = 0.28
    val afrPhaseOffset = Math.toRadians(42.0)
    val session = PeriodicNoiseCalibrationSession(
        startedAtElapsedMs = origin,
        durationMs = 35_000L,
    )

    // The reference and a temperature channel arrive at 2 Hz during explicit
    // calibration; AFR arrives at 20 Hz. A 1 Hz channel must be rejected.
    repeat(71) { index ->
        val time = origin + index * 500L
        session.add(
            "adc0",
            time,
            12.2f + wave(time, origin, frequency, 0.14, referencePhase),
        )
        session.add(
            "temperature_ntc1",
            time,
            72.0f + wave(time, origin, frequency, 0.42, referencePhase - 0.35),
        )
    }
    repeat(701) { index ->
        val time = origin + index * 50L
        val seconds = (time - origin) / 1_000.0
        session.add(
            "afr1",
            time,
            (14.7 + 0.0008 * seconds).toFloat() +
                wave(time, origin, frequency, 0.12, referencePhase + afrPhaseOffset),
        )
    }
    repeat(36) { index ->
        val time = origin + index * 1_000L
        session.add(
            "slow_value",
            time,
            50f + wave(time, origin, frequency, 1.0, referencePhase),
        )
    }

    val result = session.build(wallTimeMs = 123456789L)
    val profile = checkNotNull(result.profile) { result.reason }
    assertNear(frequency.toFloat(), profile.frequencyHz, 0.01f)
    check(profile.calibrationFor("afr1") != null)
    check(profile.calibrationFor("temperature_ntc1") != null)
    check(profile.calibrationFor("slow_value") == null) {
        "1 Hz signal should not be calibrated near a 0.4 Hz wave"
    }
    val afr = checkNotNull(profile.calibrationFor("afr1"))
    assertNear(0.12f, afr.amplitude, 0.02f)
    assertNear(42f, afr.phaseOffsetDegrees, 8f)

    // A later ignition cycle starts at another phase and with 1.5x reference
    // amplitude. The tracker must follow the live reference without relearning
    // the saved AFR phase relationship.
    val tracker = PeriodicNoiseReferenceTracker(windowMs = 14_000L)
    tracker.setProfile(profile)
    val liveOrigin = 200_000L
    val liveReferencePhase = -0.72
    val liveFrequency = 0.365
    repeat(31) { index ->
        val time = liveOrigin + index * 500L
        tracker.addReference(
            time,
            12.4f + wave(time, liveOrigin, liveFrequency, 0.21, liveReferencePhase),
        )
    }
    val targetTime = liveOrigin + 15_000L
    val correction = tracker.correction("afr1", targetTime)
    check(correction.active)
    assertNear(liveFrequency.toFloat(), correction.frequencyHz, 0.012f)
    assertNear(0.18f, correction.targetAmplitude, 0.035f)
    val expectedComponent = wave(
        targetTime,
        liveOrigin,
        liveFrequency,
        0.18,
        liveReferencePhase + afrPhaseOffset,
    )
    assertNear(expectedComponent, correction.component, 0.035f)

    // Time-based smoothing remains stable across packet rates.
    assertNear(0.3408f, smoothingAlphaForInterval(50L, 0.12f), 0.005f)
    assertNear(0.3408f, smoothingAlphaForInterval(500L, 1.20f), 0.005f)
    check(smoothingAlphaForInterval(50L, 0f) == 1f)

    println("PeriodicNoiseCalibrationTest: OK")
}
