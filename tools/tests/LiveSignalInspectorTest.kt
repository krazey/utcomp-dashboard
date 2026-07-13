package de.krazey.utcomp.dashboard.logging

import de.krazey.utcomp.dashboard.utcomp.UtcompDataSnapshot
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private fun assertNear(expected: Float, actual: Float, tolerance: Float = 0.0001f) {
    check(abs(expected - actual) <= tolerance) {
        "expected=$expected actual=$actual"
    }
}

fun main() {
    check(LiveSignalCatalog.find("boost").read(UtcompDataSnapshot(bar1 = 1.25f)) == 1.25f)
    check(
        LiveSignalCatalog.find("boost").sourcePid ==
            de.krazey.utcomp.dashboard.protocol.TransmitterConstants.UtcompPid.GENERAL_DATA2,
    )
    check(
        LiveSignalCatalog.find("adc4").sourcePid ==
            de.krazey.utcomp.dashboard.protocol.TransmitterConstants.UtcompPid.GENERAL_DATA1,
    )
    check(LiveSignalCatalog.byGroup(LiveSignalGroup.ANALOG).size == 9)
    check(LiveSignalCatalog.all.map { it.id }.distinct().size == LiveSignalCatalog.all.size)

    val buffer = LiveSignalBuffer(capacity = 5)
    buffer.setSmoothingAlpha(0.5f)
    buffer.add(0L, 0f)
    buffer.add(100L, 10f)
    buffer.add(200L, 10f)
    assertNear(0f, buffer.smoothedAt(0))
    assertNear(5f, buffer.smoothedAt(1))
    assertNear(7.5f, buffer.smoothedAt(2))

    buffer.setSmoothingAlpha(1f)
    assertNear(10f, buffer.smoothedAt(1))
    assertNear(10f, buffer.smoothedAt(2))

    buffer.clear()
    repeat(7) { index -> buffer.add(index * 100L, index.toFloat()) }
    check(buffer.size == 5)
    assertNear(2f, buffer.rawAt(0))
    assertNear(6f, buffer.rawAt(4))

    val stats = buffer.stats(windowMs = 1_000L)
    check(stats.count == 5)
    assertNear(2f, stats.rawMin)
    assertNear(6f, stats.rawMax)
    assertNear(4f, stats.rawAverage)
    assertNear(4f, stats.peakToPeak)
    assertNear(10f, stats.sampleRateHz)

    val shortStats = buffer.stats(windowMs = 220L)
    check(shortStats.count == 3)
    assertNear(4f, shortStats.rawMin)
    assertNear(6f, shortStats.rawMax)

    val periodic = LiveSignalBuffer(capacity = 500)
    periodic.setSmoothingAlpha(1f)
    periodic.setPeriodicFilter(PeriodicNoiseFilterMode.AUTO)
    repeat(400) { index ->
        val timeMs = index * 100L
        val seconds = timeMs / 1_000.0
        val raw = 14.7f + (0.30 * sin(2.0 * PI * 0.38 * seconds)).toFloat()
        periodic.add(timeMs, raw)
    }
    val estimate = periodic.periodicEstimate
    check(estimate.active) { "automatic periodic filter did not activate: $estimate" }
    assertNear(0.38f, estimate.frequencyHz, tolerance = 0.02f)
    val periodicStats = periodic.stats(windowMs = 20_000L)
    check(periodicStats.outputStdDev < periodicStats.rawStdDev * 0.65f) {
        "periodic filter did not reduce the sine: raw=${periodicStats.rawStdDev} " +
            "output=${periodicStats.outputStdDev}"
    }

    periodic.setPeriodicFilter(PeriodicNoiseFilterMode.OFF)
    val bypassStats = periodic.stats(windowMs = 20_000L)
    assertNear(bypassStats.rawStdDev, bypassStats.outputStdDev, tolerance = 0.001f)

    val manual = LiveSignalBuffer(capacity = 200)
    manual.setSmoothingAlpha(1f)
    manual.setPeriodicFilter(PeriodicNoiseFilterMode.MANUAL, 0.38f)
    repeat(200) { index ->
        val seconds = index / 10.0
        manual.add(1_000L + index * 100L, (2.0 + sin(2.0 * PI * 0.38 * seconds)).toFloat())
    }
    check(manual.periodicEstimate.active)
    assertNear(0.38f, manual.periodicEstimate.frequencyHz, tolerance = 0.001f)


    val counterWave = LiveSignalBuffer(capacity = 600)
    counterWave.setSmoothingAlpha(1f)
    counterWave.setCounterWaveStrength(1f)
    counterWave.setPeriodicFilter(PeriodicNoiseFilterMode.COUNTER_WAVE_AUTO)
    val counterPhase = 0.72
    repeat(500) { index ->
        val timeMs = index * 100L
        val seconds = timeMs / 1_000.0
        val baseline = 14.2 + 0.003 * seconds
        val raw = baseline + 0.28 * sin(2.0 * PI * 0.38 * seconds + counterPhase)
        counterWave.add(timeMs, raw.toFloat(), engineRpm = 0)
    }
    val counterEstimate = counterWave.periodicEstimate
    check(counterEstimate.active) {
        "adaptive counter-wave did not activate: $counterEstimate"
    }
    check(counterEstimate.stableWindows >= counterEstimate.requiredStableWindows)
    assertNear(0.38f, counterEstimate.frequencyHz, tolerance = 0.02f)
    assertNear(0.28f, counterEstimate.amplitude, tolerance = 0.04f)
    assertNear(1f, counterEstimate.subtractionGain, tolerance = 0.001f)
    check(counterEstimate.offset.isFinite())
    check(counterEstimate.driftPerSecond.isFinite())
    check(counterEstimate.phaseDegrees.isFinite())
    assertNear(
        Math.toDegrees(counterPhase).toFloat(),
        counterEstimate.phaseDegrees,
        tolerance = 15f,
    )

    var rawErrorSquared = 0.0
    var outputErrorSquared = 0.0
    var errorSamples = 0
    for (index in 350 until counterWave.size) {
        val seconds = counterWave.timeAt(index) / 1_000.0
        val baseline = 14.2 + 0.003 * seconds
        val rawError = counterWave.rawAt(index) - baseline
        val outputError = counterWave.smoothedAt(index) - baseline
        rawErrorSquared += rawError * rawError
        outputErrorSquared += outputError * outputError
        errorSamples++
    }
    val rawRmse = sqrt(rawErrorSquared / errorSamples)
    val outputRmse = sqrt(outputErrorSquared / errorSamples)
    check(outputRmse < rawRmse * 0.30) {
        "counter-wave did not cancel the learned sine: raw=$rawRmse output=$outputRmse " +
            "estimate=$counterEstimate"
    }

    counterWave.setCounterWaveStrength(0.5f)
    val halfStrengthStats = counterWave.stats(windowMs = 15_000L)
    check(halfStrengthStats.outputStdDev < halfStrengthStats.rawStdDev)
    check(halfStrengthStats.outputStdDev > outputRmse.toFloat())
    assertNear(0.5f, counterWave.periodicEstimate.subtractionGain, tolerance = 0.001f)

    val fixedCounterWave = LiveSignalBuffer(capacity = 300)
    fixedCounterWave.setSmoothingAlpha(1f)
    fixedCounterWave.setCounterWaveStrength(1f)
    fixedCounterWave.setPeriodicFilter(
        PeriodicNoiseFilterMode.COUNTER_WAVE_MANUAL,
        0.38f,
    )
    repeat(300) { index ->
        val seconds = index / 10.0
        val raw = 12.5 + 0.20 * cos(2.0 * PI * 0.38 * seconds)
        fixedCounterWave.add(index * 100L, raw.toFloat(), engineRpm = 900)
    }
    check(fixedCounterWave.periodicEstimate.active)
    assertNear(0.38f, fixedCounterWave.periodicEstimate.frequencyHz, tolerance = 0.001f)
    check(fixedCounterWave.periodicEstimate.amplitude > 0.17f)

    val runningCounterWave = LiveSignalBuffer(capacity = 600)
    runningCounterWave.setSmoothingAlpha(1f)
    runningCounterWave.setCounterWaveStrength(1f)
    runningCounterWave.setPeriodicFilter(PeriodicNoiseFilterMode.COUNTER_WAVE_AUTO)
    repeat(500) { index ->
        val seconds = index / 10.0
        runningCounterWave.add(
            index * 100L,
            (14.7 + 0.30 * sin(2.0 * PI * 0.38 * seconds)).toFloat(),
            engineRpm = 850,
        )
    }
    check(runningCounterWave.periodicEstimate.requiredStableWindows == 5)
    check(runningCounterWave.periodicEstimate.active)

    println("LiveSignalInspectorTest: OK")
}
