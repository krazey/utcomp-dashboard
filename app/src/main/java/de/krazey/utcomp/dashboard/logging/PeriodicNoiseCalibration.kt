package de.krazey.utcomp.dashboard.logging

import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A single timestamped decoded value used by the engine-off calibration. */
data class PeriodicNoiseSample(
    val timeMs: Long,
    val value: Float,
)

data class HarmonicFit(
    val frequencyHz: Float,
    val amplitude: Float,
    val phaseDegrees: Float,
    val offset: Float,
    val driftPerSecond: Float,
    val confidence: Float,
    val standardDeviation: Float,
    val sampleRateHz: Float,
    val originTimeMs: Long,
    val sineCoefficient: Float,
    val cosineCoefficient: Float,
) {
    fun componentAt(timeMs: Long): Float {
        if (!frequencyHz.isFinite() || !sineCoefficient.isFinite() ||
            !cosineCoefficient.isFinite()
        ) {
            return Float.NaN
        }
        val seconds = (timeMs - originTimeMs) / 1_000.0
        val angle = 2.0 * PI * frequencyHz * seconds
        return (
            sineCoefficient * sin(angle) +
                cosineCoefficient * cos(angle)
            ).toFloat()
    }
}

data class CalibratedSignalNoise(
    val signalId: String,
    val amplitude: Float,
    /** Target phase minus reference phase at the shared calibration origin. */
    val phaseOffsetDegrees: Float,
    val confidence: Float,
    val sampleRateHz: Float,
)

data class PeriodicNoiseCalibrationProfile(
    val version: Int = 1,
    val createdAtWallTimeMs: Long,
    val durationMs: Long,
    val frequencyHz: Float,
    val referenceSignalId: String,
    val referenceAmplitude: Float,
    val referenceConfidence: Float,
    val signals: Map<String, CalibratedSignalNoise>,
) {
    fun calibrationFor(signalId: String): CalibratedSignalNoise? = signals[signalId]
}

data class PeriodicCalibrationBuildResult(
    val profile: PeriodicNoiseCalibrationProfile?,
    val reason: String,
    val acceptedSignals: Int,
    val rejectedSignals: Int,
)

data class PeriodicCorrection(
    val active: Boolean,
    val component: Float,
    val frequencyHz: Float,
    val targetAmplitude: Float,
    val referenceConfidence: Float,
) {
    companion object {
        val INACTIVE = PeriodicCorrection(
            active = false,
            component = 0f,
            frequencyHz = Float.NaN,
            targetAmplitude = Float.NaN,
            referenceConfidence = Float.NaN,
        )
    }
}

/**
 * Shared least-squares harmonic fitter used by calibration and live phase
 * tracking. The baseline and linear drift are removed before the sine/cosine
 * coefficients are fitted.
 */
object HarmonicRegression {
    private const val EPSILON = 1e-12

    fun estimateFrequency(
        samples: List<PeriodicNoiseSample>,
        originTimeMs: Long = samples.firstOrNull()?.timeMs ?: 0L,
        minimumHz: Float = 0.25f,
        maximumHz: Float = 0.55f,
        stepHz: Float = 0.0025f,
    ): HarmonicFit? {
        if (samples.size < 12) return null
        val sampleRate = sampleRate(samples)
        if (!sampleRate.isFinite() || sampleRate <= 0f) return null
        val upper = min(maximumHz, sampleRate * 0.45f)
        if (upper < minimumHz) return null

        var best: HarmonicFit? = null
        var frequency = minimumHz
        while (frequency <= upper + 0.0001f) {
            val candidate = fit(samples, frequency, originTimeMs)
            if (candidate != null &&
                (best == null || candidate.confidence > best.confidence)
            ) {
                best = candidate
            }
            frequency += stepHz
        }
        return best
    }

    fun fit(
        samples: List<PeriodicNoiseSample>,
        frequencyHz: Float,
        originTimeMs: Long = samples.firstOrNull()?.timeMs ?: 0L,
    ): HarmonicFit? {
        val clean = samples.filter { it.value.isFinite() }
        if (clean.size < 4 || frequencyHz <= 0f) return null
        val durationMs = clean.last().timeMs - clean.first().timeMs
        if (durationMs <= 0L) return null

        var meanTime = 0.0
        var meanValue = 0.0
        clean.forEach { sample ->
            meanTime += (sample.timeMs - originTimeMs) / 1_000.0
            meanValue += sample.value
        }
        meanTime /= clean.size
        meanValue /= clean.size

        var timeVariance = 0.0
        var timeValueCovariance = 0.0
        clean.forEach { sample ->
            val time = (sample.timeMs - originTimeMs) / 1_000.0
            val dt = time - meanTime
            timeVariance += dt * dt
            timeValueCovariance += dt * (sample.value - meanValue)
        }
        val slope = if (timeVariance > EPSILON) {
            timeValueCovariance / timeVariance
        } else {
            0.0
        }
        val offsetAtOrigin = meanValue - slope * meanTime

        var ss = 0.0
        var cc = 0.0
        var sc = 0.0
        var ys = 0.0
        var yc = 0.0
        var residualPower = 0.0
        val angularFrequency = 2.0 * PI * frequencyHz

        clean.forEach { sample ->
            val time = (sample.timeMs - originTimeMs) / 1_000.0
            val residual = sample.value - (offsetAtOrigin + slope * time)
            val sine = sin(angularFrequency * time)
            val cosine = cos(angularFrequency * time)
            ss += sine * sine
            cc += cosine * cosine
            sc += sine * cosine
            ys += residual * sine
            yc += residual * cosine
            residualPower += residual * residual
        }

        val determinant = ss * cc - sc * sc
        if (abs(determinant) < EPSILON || residualPower < EPSILON) return null

        val sineCoefficient = (ys * cc - yc * sc) / determinant
        val cosineCoefficient = (yc * ss - ys * sc) / determinant
        var fittedPower = 0.0
        clean.forEach { sample ->
            val seconds = (sample.timeMs - originTimeMs) / 1_000.0
            val fitted =
                sineCoefficient * sin(angularFrequency * seconds) +
                    cosineCoefficient * cos(angularFrequency * seconds)
            fittedPower += fitted * fitted
        }

        val amplitude = sqrt(
            sineCoefficient * sineCoefficient + cosineCoefficient * cosineCoefficient,
        )
        val phase = normalizeDegrees(Math.toDegrees(atan2(cosineCoefficient, sineCoefficient)))
        return HarmonicFit(
            frequencyHz = frequencyHz,
            amplitude = amplitude.toFloat(),
            phaseDegrees = phase,
            offset = offsetAtOrigin.toFloat(),
            driftPerSecond = slope.toFloat(),
            confidence = (fittedPower / residualPower).coerceIn(0.0, 1.0).toFloat(),
            standardDeviation = sqrt(residualPower / clean.size).toFloat(),
            sampleRateHz = sampleRate(clean),
            originTimeMs = originTimeMs,
            sineCoefficient = sineCoefficient.toFloat(),
            cosineCoefficient = cosineCoefficient.toFloat(),
        )
    }

    fun sampleRate(samples: List<PeriodicNoiseSample>): Float {
        if (samples.size < 2) return Float.NaN
        val durationMs = samples.last().timeMs - samples.first().timeMs
        return if (durationMs > 0L) {
            (samples.size - 1) * 1_000f / durationMs
        } else {
            Float.NaN
        }
    }

    fun normalizeDegrees(value: Double): Float {
        var degrees = value % 360.0
        if (degrees > 180.0) degrees -= 360.0
        if (degrees <= -180.0) degrees += 360.0
        return degrees.toFloat()
    }
}

/** Collects every decoded signal during an explicit engine-off session. */
class PeriodicNoiseCalibrationSession(
    val startedAtElapsedMs: Long,
    val durationMs: Long = DEFAULT_DURATION_MS,
) {
    companion object {
        const val DEFAULT_DURATION_MS = 35_000L
        const val MINIMUM_DURATION_MS = 20_000L
        private const val REFERENCE_SIGNAL_ID = "adc0"
        private const val MIN_REFERENCE_CONFIDENCE = 0.06f
        private const val MIN_SIGNAL_CONFIDENCE = 0.04f
        private const val MIN_SIGNAL_AMPLITUDE_FRACTION = 0.08f
    }

    private val samplesBySignal = LinkedHashMap<String, MutableList<PeriodicNoiseSample>>()

    fun add(signalId: String, timeMs: Long, value: Float) {
        if (!value.isFinite()) return
        samplesBySignal.getOrPut(signalId) { ArrayList() }
            .add(PeriodicNoiseSample(timeMs, value))
    }

    fun elapsedMs(nowMs: Long): Long = (nowMs - startedAtElapsedMs).coerceAtLeast(0L)

    fun progress(nowMs: Long): Float =
        (elapsedMs(nowMs).toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)

    fun isComplete(nowMs: Long): Boolean = elapsedMs(nowMs) >= durationMs

    fun canFinish(nowMs: Long): Boolean = elapsedMs(nowMs) >= MINIMUM_DURATION_MS

    fun sampleCount(signalId: String): Int = samplesBySignal[signalId]?.size ?: 0

    fun build(wallTimeMs: Long): PeriodicCalibrationBuildResult {
        val referenceSamples = samplesBySignal[REFERENCE_SIGNAL_ID].orEmpty()
        if (referenceSamples.size < 12) {
            return PeriodicCalibrationBuildResult(
                profile = null,
                reason = "Not enough ADC 0 / battery samples",
                acceptedSignals = 0,
                rejectedSignals = samplesBySignal.size,
            )
        }

        val originTimeMs = referenceSamples.first().timeMs
        val reference = HarmonicRegression.estimateFrequency(
            samples = referenceSamples,
            originTimeMs = originTimeMs,
        ) ?: return PeriodicCalibrationBuildResult(
            profile = null,
            reason = "No stable 0.25-0.55 Hz reference wave found",
            acceptedSignals = 0,
            rejectedSignals = samplesBySignal.size,
        )

        if (reference.confidence < MIN_REFERENCE_CONFIDENCE ||
            reference.amplitude <= 0f
        ) {
            return PeriodicCalibrationBuildResult(
                profile = null,
                reason = "ADC 0 reference confidence is too low",
                acceptedSignals = 0,
                rejectedSignals = samplesBySignal.size,
            )
        }

        val accepted = LinkedHashMap<String, CalibratedSignalNoise>()
        var rejected = 0
        samplesBySignal.forEach { (signalId, samples) ->
            val fit = HarmonicRegression.fit(
                samples = samples,
                frequencyHz = reference.frequencyHz,
                originTimeMs = originTimeMs,
            )
            val amplitudeThreshold = if (fit != null) {
                max(0.0001f, fit.standardDeviation * MIN_SIGNAL_AMPLITUDE_FRACTION)
            } else {
                Float.POSITIVE_INFINITY
            }
            if (fit == null || fit.sampleRateHz < reference.frequencyHz * 4.0f ||
                fit.confidence < MIN_SIGNAL_CONFIDENCE || fit.amplitude < amplitudeThreshold
            ) {
                rejected++
                return@forEach
            }

            accepted[signalId] = CalibratedSignalNoise(
                signalId = signalId,
                amplitude = fit.amplitude,
                phaseOffsetDegrees = HarmonicRegression.normalizeDegrees(
                    (fit.phaseDegrees - reference.phaseDegrees).toDouble(),
                ),
                confidence = fit.confidence,
                sampleRateHz = fit.sampleRateHz,
            )
        }

        if (accepted[REFERENCE_SIGNAL_ID] == null) {
            accepted[REFERENCE_SIGNAL_ID] = CalibratedSignalNoise(
                signalId = REFERENCE_SIGNAL_ID,
                amplitude = reference.amplitude,
                phaseOffsetDegrees = 0f,
                confidence = reference.confidence,
                sampleRateHz = reference.sampleRateHz,
            )
        }

        return PeriodicCalibrationBuildResult(
            profile = PeriodicNoiseCalibrationProfile(
                createdAtWallTimeMs = wallTimeMs,
                durationMs = referenceSamples.last().timeMs - referenceSamples.first().timeMs,
                frequencyHz = reference.frequencyHz,
                referenceSignalId = REFERENCE_SIGNAL_ID,
                referenceAmplitude = reference.amplitude,
                referenceConfidence = reference.confidence,
                signals = accepted,
            ),
            reason = "Calibration saved",
            acceptedSignals = accepted.size,
            rejectedSignals = rejected,
        )
    }
}

/**
 * Tracks only the saved reference channel while driving. Signal coefficients
 * are never relearned here; the rolling fit supplies the current phase only.
 */
class PeriodicNoiseReferenceTracker(
    private val windowMs: Long = 14_000L,
) {
    private val samples = ArrayDeque<PeriodicNoiseSample>()
    private var profile: PeriodicNoiseCalibrationProfile? = null
    private var lastFitAtMs = Long.MIN_VALUE
    private var referenceFit: HarmonicFit? = null
    private var referenceActiveSinceMs = Long.MIN_VALUE

    fun setProfile(profile: PeriodicNoiseCalibrationProfile?) {
        this.profile = profile
        samples.clear()
        lastFitAtMs = Long.MIN_VALUE
        referenceFit = null
        referenceActiveSinceMs = Long.MIN_VALUE
    }

    fun addReference(timeMs: Long, value: Float) {
        val activeProfile = profile ?: return
        if (!value.isFinite()) return
        samples.addLast(PeriodicNoiseSample(timeMs, value))
        val cutoff = timeMs - windowMs
        while (samples.isNotEmpty() && samples.first().timeMs < cutoff) {
            samples.removeFirst()
        }
        if (lastFitAtMs == Long.MIN_VALUE || timeMs - lastFitAtMs >= 1_000L) {
            lastFitAtMs = timeMs
            val snapshot = samples.toList()
            val duration = if (snapshot.size > 1) {
                snapshot.last().timeMs - snapshot.first().timeMs
            } else {
                0L
            }
            val nextFit = if (snapshot.size >= 8 && duration >= 6_000L) {
                HarmonicRegression.estimateFrequency(
                    samples = snapshot,
                    originTimeMs = timeMs,
                    minimumHz = (activeProfile.frequencyHz - 0.07f).coerceAtLeast(0.20f),
                    maximumHz = (activeProfile.frequencyHz + 0.07f).coerceAtMost(0.65f),
                    stepHz = 0.0025f,
                )
            } else {
                null
            }
            val wasActive = referenceFit?.confidence?.let { it >= 0.035f } == true
            val isActive = nextFit?.confidence?.let { it >= 0.035f } == true
            if (isActive && !wasActive) referenceActiveSinceMs = timeMs
            if (!isActive) referenceActiveSinceMs = Long.MIN_VALUE
            referenceFit = nextFit
        }
    }

    fun correction(signalId: String, timeMs: Long): PeriodicCorrection {
        val activeProfile = profile ?: return PeriodicCorrection.INACTIVE
        val signal = activeProfile.calibrationFor(signalId)
            ?: return PeriodicCorrection.INACTIVE
        val reference = referenceFit ?: return PeriodicCorrection.INACTIVE
        if (reference.confidence < 0.035f || !reference.phaseDegrees.isFinite()) {
            return PeriodicCorrection.INACTIVE
        }

        val phase = Math.toRadians(
            (reference.phaseDegrees + signal.phaseOffsetDegrees).toDouble(),
        ) + 2.0 * PI * reference.frequencyHz *
            (timeMs - reference.originTimeMs) / 1_000.0
        val liveReferenceScale = if (
            activeProfile.referenceAmplitude > 0f && reference.amplitude.isFinite()
        ) {
            (reference.amplitude / activeProfile.referenceAmplitude).coerceIn(0.25f, 2.0f)
        } else {
            1f
        }
        val targetAmplitude = signal.amplitude * liveReferenceScale
        val fadeGain = if (referenceActiveSinceMs == Long.MIN_VALUE) {
            0f
        } else {
            ((timeMs - referenceActiveSinceMs) / 2_000f).coerceIn(0f, 1f)
        }
        return PeriodicCorrection(
            active = fadeGain > 0f,
            component = (targetAmplitude * fadeGain * sin(phase)).toFloat(),
            frequencyHz = reference.frequencyHz,
            targetAmplitude = targetAmplitude,
            referenceConfidence = reference.confidence,
        )
    }

    fun currentReferenceFit(): HarmonicFit? = referenceFit
}
