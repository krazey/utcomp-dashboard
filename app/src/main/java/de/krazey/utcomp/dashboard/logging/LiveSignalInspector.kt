package de.krazey.utcomp.dashboard.logging

import de.krazey.utcomp.dashboard.protocol.TransmitterConstants
import de.krazey.utcomp.dashboard.utcomp.UtcompDataSnapshot
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class LiveSignalGroup(val label: String) {
    CORE("Core signals"),
    TEMPERATURE("Temperatures"),
    ANALOG("Analog inputs"),
    EXHAUST("Exhaust temperatures"),
    TRIP("Trip and fuel"),
}

data class LiveSignalDefinition(
    val id: String,
    val label: String,
    val unit: String,
    val decimals: Int,
    val group: LiveSignalGroup,
    val description: String,
    val sourcePid: Int,
    val read: (UtcompDataSnapshot) -> Float,
)

object LiveSignalCatalog {
    private fun signal(
        id: String,
        label: String,
        unit: String = "",
        decimals: Int = 2,
        group: LiveSignalGroup,
        description: String,
        sourcePid: Int,
        read: (UtcompDataSnapshot) -> Float,
    ) = LiveSignalDefinition(id, label, unit, decimals, group, description, sourcePid, read)

    private val general1 = TransmitterConstants.UtcompPid.GENERAL_DATA1
    private val general2 = TransmitterConstants.UtcompPid.GENERAL_DATA2
    private val temperatures = TransmitterConstants.UtcompPid.TEMPERATURES_DATA
    private val speed = TransmitterConstants.UtcompPid.VSS_DATA
    private val consumption = TransmitterConstants.UtcompPid.CONSUMPTION_DATA
    private val trip = TransmitterConstants.UtcompPid.TRIP_DATA

    val all: List<LiveSignalDefinition> = listOf(
        signal("boost", "Boost", "bar", 2, LiveSignalGroup.CORE,
            "Decoded BAR1 boost/vacuum value.", general2) { it.bar1 },
        signal("oil_pressure", "Oil pressure", "bar", 2, LiveSignalGroup.CORE,
            "Decoded BAR2 pressure value.", general2) { it.bar2 },
        signal("bar3", "Bar 3", "bar", 2, LiveSignalGroup.CORE,
            "Decoded third pressure channel.", general2) { it.bar3 },
        signal("afr1", "AFR 1", "", 2, LiveSignalGroup.CORE,
            "Primary decoded air/fuel ratio.", general2) { it.afr1 },
        signal("afr2", "AFR 2", "", 2, LiveSignalGroup.CORE,
            "Secondary decoded air/fuel ratio.", general2) { it.afr2 },
        signal("rpm", "RPM", "rpm", 0, LiveSignalGroup.CORE,
            "Decoded engine speed.", general2) { it.rpm.toFloat() },
        signal("speed_fast", "Speed 200 ms", "km/h", 1, LiveSignalGroup.CORE,
            "Fast vehicle-speed value.", speed) { it.vssSpeed200ms.toFloat() },
        signal("speed_slow", "Speed 1 s", "km/h", 1, LiveSignalGroup.CORE,
            "One-second vehicle-speed value.", speed) { it.vssSpeed1s.toFloat() },
        signal("gear", "Gear", "", 0, LiveSignalGroup.CORE,
            "Detected gear number.", general1) { it.gearNo.toFloat() },

        signal("temperature_ntc1", "NTC 1 / oil temp", "°C", 1,
            LiveSignalGroup.TEMPERATURE, "Primary NTC temperature channel.", temperatures) { it.temperatureNtc1 },
        signal("temperature_ntc2", "NTC 2", "°C", 1,
            LiveSignalGroup.TEMPERATURE, "Second NTC temperature channel.", temperatures) { it.temperatureNtc2 },
        signal("temperature_ntc3", "NTC 3", "°C", 1,
            LiveSignalGroup.TEMPERATURE, "Third NTC temperature channel.", general2) { it.temperatureNtc3 },
        signal("temperature_ds_a", "DS A / outside", "°C", 1,
            LiveSignalGroup.TEMPERATURE, "Digital temperature sensor A.", temperatures) { it.temperatureDsA },
        signal("temperature_ds_b", "DS B / inside", "°C", 1,
            LiveSignalGroup.TEMPERATURE, "Digital temperature sensor B.", temperatures) { it.temperatureDsB },
        signal("temperature_ds_c", "DS C", "°C", 1,
            LiveSignalGroup.TEMPERATURE, "Digital temperature sensor C.", temperatures) { it.temperatureDsC },
        signal("temperature_ds_d", "DS D", "°C", 1,
            LiveSignalGroup.TEMPERATURE, "Digital temperature sensor D.", temperatures) { it.temperatureDsD },
        signal("temperature_outside", "Outside temperature", "°C", 1,
            LiveSignalGroup.TEMPERATURE, "Configured outside-temperature value.", temperatures) { it.temperatureOutside },
        signal("temperature_inside", "Inside temperature", "°C", 1,
            LiveSignalGroup.TEMPERATURE, "Configured inside-temperature value.", temperatures) { it.temperatureInside },
        signal("temperature_engine", "Engine temperature", "°C", 1,
            LiveSignalGroup.TEMPERATURE, "Configured engine-temperature value.", temperatures) { it.temperatureEngine },
        signal("temperature_oil", "Oil temperature", "°C", 1,
            LiveSignalGroup.TEMPERATURE, "Configured oil-temperature value.", temperatures) { it.temperatureOil },
        signal("temperature_user1", "User temperature 1", "°C", 1,
            LiveSignalGroup.TEMPERATURE, "First configured user-temperature value.", temperatures) { it.temperatureUser1 },
        signal("temperature_user2", "User temperature 2", "°C", 1,
            LiveSignalGroup.TEMPERATURE, "Second configured user-temperature value.", temperatures) { it.temperatureUser2 },

        signal("adc0", "ADC 0 / battery", "V", 3, LiveSignalGroup.ANALOG,
            "Raw decoded analog input channel 0.", general1) { it.adcInValCh0 },
        signal("adc1", "ADC 1", "V", 3, LiveSignalGroup.ANALOG,
            "Raw decoded analog input channel 1.", general1) { it.adcInValCh1 },
        signal("adc2", "ADC 2", "V", 3, LiveSignalGroup.ANALOG,
            "Raw decoded analog input channel 2.", general1) { it.adcInValCh2 },
        signal("adc3", "ADC 3", "V", 3, LiveSignalGroup.ANALOG,
            "Raw decoded analog input channel 3.", general1) { it.adcInValCh3 },
        signal("adc4", "ADC 4", "V", 3, LiveSignalGroup.ANALOG,
            "Raw decoded analog input channel 4.", general1) { it.adcInValCh4 },
        signal("adc5", "ADC 5", "V", 3, LiveSignalGroup.ANALOG,
            "Raw decoded analog input channel 5.", general1) { it.adcInValCh5 },
        signal("adc6", "ADC 6", "V", 3, LiveSignalGroup.ANALOG,
            "Raw decoded analog input channel 6.", general1) { it.adcInValCh6 },
        signal("adc7", "ADC 7", "V", 3, LiveSignalGroup.ANALOG,
            "Raw decoded analog input channel 7.", general1) { it.adcInValCh7 },
        signal("vref", "Vref", "", 0, LiveSignalGroup.ANALOG,
            "Decoded ADC reference value.", general1) { it.vref.toFloat() },

        signal("egt1", "EGT 1", "°C", 0, LiveSignalGroup.EXHAUST,
            "Exhaust-gas temperature channel 1.", general2) { it.egt1.toFloat() },
        signal("egt2", "EGT 2", "°C", 0, LiveSignalGroup.EXHAUST,
            "Exhaust-gas temperature channel 2.", general2) { it.egt2.toFloat() },
        signal("egt3", "EGT 3", "°C", 0, LiveSignalGroup.EXHAUST,
            "Exhaust-gas temperature channel 3.", general2) { it.egt3.toFloat() },
        signal("egt4", "EGT 4", "°C", 0, LiveSignalGroup.EXHAUST,
            "Exhaust-gas temperature channel 4.", general2) { it.egt4.toFloat() },
        signal("egt5", "EGT 5", "°C", 0, LiveSignalGroup.EXHAUST,
            "Exhaust-gas temperature channel 5.", general2) { it.egt5.toFloat() },
        signal("egt6", "EGT 6", "°C", 0, LiveSignalGroup.EXHAUST,
            "Exhaust-gas temperature channel 6.", general2) { it.egt6.toFloat() },

        signal("consumption_current", "Consumption current", "", 2,
            LiveSignalGroup.TRIP, "Current fuel-consumption value.", consumption) { it.consumptionCur },
        signal("consumption_average", "Consumption average", "", 2,
            LiveSignalGroup.TRIP, "Average fuel-consumption value.", consumption) { it.consumptionAvg },
        signal("fuel_pb", "Fuel PB", "", 2,
            LiveSignalGroup.TRIP, "Remaining petrol quantity.", consumption) { it.fuelLeftPb },
        signal("fuel_lpg", "Fuel LPG", "", 2,
            LiveSignalGroup.TRIP, "Remaining LPG quantity.", consumption) { it.fuelLeftLpg },
        signal("injection_time", "Injection time", "ms", 2,
            LiveSignalGroup.TRIP, "One-second injection-time value.", consumption) { it.injectionTime1s },
        signal("trip_distance", "Trip distance", "km", 2,
            LiveSignalGroup.TRIP, "Current trip distance.", trip) { it.tripDist },
        signal("trip_consumption", "Trip consumption", "", 2,
            LiveSignalGroup.TRIP, "Current trip-consumption value.", trip) { it.tripCons },
        signal("trip_average_speed", "Trip average speed", "km/h", 1,
            LiveSignalGroup.TRIP, "Current trip average speed.", trip) { it.tripVavg },
        signal("vmax", "Maximum speed", "km/h", 0,
            LiveSignalGroup.TRIP, "Stored maximum speed.", trip) { it.vmax.toFloat() },
    )

    val default: LiveSignalDefinition = all.first()

    fun find(id: String?): LiveSignalDefinition = all.firstOrNull { it.id == id } ?: default

    fun byGroup(group: LiveSignalGroup): List<LiveSignalDefinition> = all.filter { it.group == group }
}

enum class PeriodicNoiseFilterMode {
    OFF,
    AUTO,
    MANUAL,
}

data class PeriodicNoiseEstimate(
    val mode: PeriodicNoiseFilterMode,
    val active: Boolean,
    val frequencyHz: Float,
    val amplitude: Float,
    val confidence: Float,
    val sampleRateHz: Float,
)

data class LiveSignalStats(
    val count: Int,
    val rawCurrent: Float,
    val smoothedCurrent: Float,
    val rawMin: Float,
    val rawMax: Float,
    val rawAverage: Float,
    val rawStdDev: Float,
    val outputMin: Float,
    val outputMax: Float,
    val outputAverage: Float,
    val outputStdDev: Float,
    val sampleRateHz: Float,
) {
    val peakToPeak: Float
        get() = if (rawMin.isFinite() && rawMax.isFinite()) rawMax - rawMin else Float.NaN

    val outputPeakToPeak: Float
        get() = if (outputMin.isFinite() && outputMax.isFinite()) {
            outputMax - outputMin
        } else {
            Float.NaN
        }
}

/**
 * Bounded live-signal history with optional periodic-noise rejection followed
 * by the same exponential smoothing used by dashboard cards.
 *
 * AUTO searches only the 0.25-0.55 Hz band observed in the supplied captures.
 * It remains disabled until a stable sinusoidal component explains enough of
 * the recent variance. MANUAL always applies the selected notch frequency.
 */
class LiveSignalBuffer(
    private val capacity: Int = 2_500,
) {
    init {
        require(capacity > 1)
    }

    private companion object {
        const val PERIODIC_WINDOW_MS = 20_000L
        const val PERIODIC_REESTIMATE_MS = 1_000L
        const val PERIODIC_MIN_DURATION_MS = 8_000L
        const val PERIODIC_MIN_SAMPLES = 32
        const val PERIODIC_MIN_HZ = 0.25f
        const val PERIODIC_MAX_HZ = 0.55f
        const val PERIODIC_STEP_HZ = 0.005f
        const val PERIODIC_MIN_CONFIDENCE = 0.18f
        const val PERIODIC_MIN_AMPLITUDE_FRACTION = 0.25f
        const val NOTCH_Q = 3.0

        fun smooth(previous: Float, raw: Float, alpha: Float): Float {
            if (!previous.isFinite()) return raw
            val safeAlpha = alpha.coerceIn(0.01f, 1f)
            return previous + (raw - previous) * safeAlpha
        }
    }

    private val elapsedMs = LongArray(capacity)
    private val rawValues = FloatArray(capacity)
    private val outputValues = FloatArray(capacity)
    private var start = 0
    private var count = 0

    private var lastEstimateAtMs = Long.MIN_VALUE

    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0
    private var notchActive = false

    var smoothingAlpha: Float = 1f
        private set

    var periodicMode: PeriodicNoiseFilterMode = PeriodicNoiseFilterMode.OFF
        private set

    var manualFrequencyHz: Float = 0.38f
        private set

    var periodicEstimate: PeriodicNoiseEstimate = inactiveEstimate(PeriodicNoiseFilterMode.OFF)
        private set

    val size: Int
        get() = count

    fun clear() {
        start = 0
        count = 0
        lastEstimateAtMs = Long.MIN_VALUE
        resetNotchState()
        periodicEstimate = inactiveEstimate(periodicMode)
    }

    fun setSmoothingAlpha(alpha: Float) {
        smoothingAlpha = alpha.coerceIn(0.01f, 1f)
        recomputeOutput(forceEstimate = false)
    }

    fun setPeriodicFilter(
        mode: PeriodicNoiseFilterMode,
        manualFrequencyHz: Float = this.manualFrequencyHz,
    ) {
        periodicMode = mode
        this.manualFrequencyHz = manualFrequencyHz.coerceIn(0.20f, 0.55f)
        lastEstimateAtMs = Long.MIN_VALUE
        recomputeOutput(forceEstimate = true)
    }

    fun add(timeMs: Long, rawValue: Float) {
        if (!rawValue.isFinite()) return

        val insertIndex = if (count < capacity) {
            physicalIndex(count).also { count++ }
        } else {
            val index = start
            start = (start + 1) % capacity
            index
        }

        elapsedMs[insertIndex] = timeMs
        rawValues[insertIndex] = rawValue

        val shouldEstimate =
            periodicMode != PeriodicNoiseFilterMode.OFF &&
                (lastEstimateAtMs == Long.MIN_VALUE ||
                    timeMs - lastEstimateAtMs >= PERIODIC_REESTIMATE_MS)

        if (shouldEstimate) {
            recomputeOutput(forceEstimate = true)
            lastEstimateAtMs = timeMs
            return
        }

        val previous = if (count <= 1) {
            Float.NaN
        } else {
            outputValues[physicalIndex(count - 2)]
        }
        outputValues[insertIndex] = processOutput(rawValue, previous)
    }

    fun timeAt(index: Int): Long = elapsedMs[physicalIndex(index)]

    fun rawAt(index: Int): Float = rawValues[physicalIndex(index)]

    /**
     * Kept as smoothedAt() for the existing graph API. The value is the final
     * output after optional periodic filtering and exponential smoothing.
     */
    fun smoothedAt(index: Int): Float = outputValues[physicalIndex(index)]

    fun firstVisibleIndex(windowMs: Long): Int {
        if (count == 0) return 0
        val cutoff = timeAt(count - 1) - windowMs.coerceAtLeast(1L)
        var low = 0
        var high = count
        while (low < high) {
            val mid = (low + high) ushr 1
            if (timeAt(mid) < cutoff) low = mid + 1 else high = mid
        }
        return low.coerceIn(0, count - 1)
    }

    fun stats(windowMs: Long): LiveSignalStats {
        if (count == 0) return emptyStats()
        val first = firstVisibleIndex(windowMs)

        var rawMin = Float.POSITIVE_INFINITY
        var rawMax = Float.NEGATIVE_INFINITY
        var rawSum = 0.0
        var rawSquares = 0.0
        var outputMin = Float.POSITIVE_INFINITY
        var outputMax = Float.NEGATIVE_INFINITY
        var outputSum = 0.0
        var outputSquares = 0.0
        var samples = 0

        for (index in first until count) {
            val raw = rawAt(index)
            val output = smoothedAt(index)
            if (!raw.isFinite() || !output.isFinite()) continue

            rawMin = min(rawMin, raw)
            rawMax = max(rawMax, raw)
            rawSum += raw
            rawSquares += raw.toDouble() * raw.toDouble()

            outputMin = min(outputMin, output)
            outputMax = max(outputMax, output)
            outputSum += output
            outputSquares += output.toDouble() * output.toDouble()
            samples++
        }
        if (samples == 0) return emptyStats()

        val rawAverage = (rawSum / samples).toFloat()
        val outputAverage = (outputSum / samples).toFloat()
        val rawVariance =
            (rawSquares / samples - rawAverage.toDouble() * rawAverage.toDouble())
                .coerceAtLeast(0.0)
        val outputVariance =
            (outputSquares / samples - outputAverage.toDouble() * outputAverage.toDouble())
                .coerceAtLeast(0.0)
        val durationMs = (timeAt(count - 1) - timeAt(first)).coerceAtLeast(0L)
        val rate = if (samples > 1 && durationMs > 0L) {
            (samples - 1) * 1_000f / durationMs
        } else {
            Float.NaN
        }

        return LiveSignalStats(
            count = samples,
            rawCurrent = rawAt(count - 1),
            smoothedCurrent = smoothedAt(count - 1),
            rawMin = rawMin,
            rawMax = rawMax,
            rawAverage = rawAverage,
            rawStdDev = sqrt(rawVariance).toFloat(),
            outputMin = outputMin,
            outputMax = outputMax,
            outputAverage = outputAverage,
            outputStdDev = sqrt(outputVariance).toFloat(),
            sampleRateHz = rate,
        )
    }

    private fun recomputeOutput(forceEstimate: Boolean) {
        if (count == 0) {
            periodicEstimate = inactiveEstimate(periodicMode)
            resetNotchState()
            return
        }

        if (forceEstimate || periodicMode == PeriodicNoiseFilterMode.OFF) {
            periodicEstimate = estimatePeriodicComponent()
        }
        configureNotch(periodicEstimate)
        resetNotchState(keepCoefficients = true)
        if (notchActive) primeNotch(rawAt(0))

        var previous = Float.NaN
        for (index in 0 until count) {
            val raw = rawAt(index)
            previous = processOutput(raw, previous)
            outputValues[physicalIndex(index)] = previous
        }
    }

    private fun processOutput(raw: Float, previous: Float): Float {
        val periodicFiltered = if (notchActive) processNotch(raw) else raw
        return smooth(previous, periodicFiltered, smoothingAlpha)
    }

    private fun estimatePeriodicComponent(): PeriodicNoiseEstimate {
        if (periodicMode == PeriodicNoiseFilterMode.OFF) {
            return inactiveEstimate(periodicMode)
        }

        val sampleRate = estimateSampleRate()
        if (!sampleRate.isFinite() || sampleRate <= 0f) {
            return inactiveEstimate(periodicMode)
        }

        val maxUsableFrequency = min(PERIODIC_MAX_HZ, sampleRate * 0.45f)
        if (maxUsableFrequency <= 0.20f) {
            return inactiveEstimate(periodicMode).copy(sampleRateHz = sampleRate)
        }

        val first = firstVisibleIndex(PERIODIC_WINDOW_MS)
        val durationMs = timeAt(count - 1) - timeAt(first)
        if (count - first < PERIODIC_MIN_SAMPLES || durationMs < PERIODIC_MIN_DURATION_MS) {
            val manualFrequency = manualFrequencyHz.coerceAtMost(maxUsableFrequency)
            return PeriodicNoiseEstimate(
                mode = periodicMode,
                active = periodicMode == PeriodicNoiseFilterMode.MANUAL &&
                    manualFrequency > 0f,
                frequencyHz = if (periodicMode == PeriodicNoiseFilterMode.MANUAL) {
                    manualFrequency
                } else {
                    Float.NaN
                },
                amplitude = Float.NaN,
                confidence = Float.NaN,
                sampleRateHz = sampleRate,
            )
        }

        val lowerFrequency = min(PERIODIC_MIN_HZ, maxUsableFrequency)
        val manualFrequency = manualFrequencyHz.coerceIn(0.20f, maxUsableFrequency)
        val candidate = if (periodicMode == PeriodicNoiseFilterMode.MANUAL) {
            fitFrequency(first, manualFrequency)
        } else {
            var best: FrequencyFit? = null
            var frequency = lowerFrequency
            while (frequency <= maxUsableFrequency + 0.0001f) {
                val fit = fitFrequency(first, frequency)
                if (fit != null && (best == null || fit.confidence > best.confidence)) {
                    best = fit
                }
                frequency += PERIODIC_STEP_HZ
            }
            best
        }

        if (candidate == null) {
            return inactiveEstimate(periodicMode).copy(sampleRateHz = sampleRate)
        }

        val active = if (periodicMode == PeriodicNoiseFilterMode.MANUAL) {
            true
        } else {
            candidate.confidence >= PERIODIC_MIN_CONFIDENCE &&
                candidate.amplitude >= candidate.standardDeviation *
                    PERIODIC_MIN_AMPLITUDE_FRACTION
        }

        return PeriodicNoiseEstimate(
            mode = periodicMode,
            active = active,
            frequencyHz = candidate.frequencyHz,
            amplitude = candidate.amplitude,
            confidence = candidate.confidence,
            sampleRateHz = sampleRate,
        )
    }

    private data class FrequencyFit(
        val frequencyHz: Float,
        val amplitude: Float,
        val confidence: Float,
        val standardDeviation: Float,
    )

    private fun fitFrequency(first: Int, frequencyHz: Float): FrequencyFit? {
        val samples = count - first
        if (samples < 4) return null

        val lastTime = timeAt(count - 1)
        var meanTime = 0.0
        var meanValue = 0.0
        for (index in first until count) {
            meanTime += (timeAt(index) - lastTime) / 1_000.0
            meanValue += rawAt(index)
        }
        meanTime /= samples
        meanValue /= samples

        var timeVariance = 0.0
        var timeValueCovariance = 0.0
        for (index in first until count) {
            val time = (timeAt(index) - lastTime) / 1_000.0
            val dt = time - meanTime
            timeVariance += dt * dt
            timeValueCovariance += dt * (rawAt(index) - meanValue)
        }
        val slope = if (timeVariance > 1e-12) {
            timeValueCovariance / timeVariance
        } else {
            0.0
        }

        var ss = 0.0
        var cc = 0.0
        var sc = 0.0
        var ys = 0.0
        var yc = 0.0
        var residualPower = 0.0
        val angularFrequency = 2.0 * PI * frequencyHz

        for (index in first until count) {
            val time = (timeAt(index) - lastTime) / 1_000.0
            val residual = rawAt(index) - (meanValue + slope * (time - meanTime))
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
        if (abs(determinant) < 1e-12 || residualPower < 1e-12) return null

        val sineCoefficient = (ys * cc - yc * sc) / determinant
        val cosineCoefficient = (yc * ss - ys * sc) / determinant
        var fittedPower = 0.0
        for (index in first until count) {
            val time = (timeAt(index) - lastTime) / 1_000.0
            val fitted =
                sineCoefficient * sin(angularFrequency * time) +
                    cosineCoefficient * cos(angularFrequency * time)
            fittedPower += fitted * fitted
        }

        return FrequencyFit(
            frequencyHz = frequencyHz,
            amplitude = sqrt(
                sineCoefficient * sineCoefficient +
                    cosineCoefficient * cosineCoefficient,
            ).toFloat(),
            confidence = (fittedPower / residualPower).coerceIn(0.0, 1.0).toFloat(),
            standardDeviation = sqrt(residualPower / samples).toFloat(),
        )
    }

    private fun estimateSampleRate(): Float {
        if (count < 2) return Float.NaN
        val first = firstVisibleIndex(PERIODIC_WINDOW_MS)
        val durationMs = timeAt(count - 1) - timeAt(first)
        return if (durationMs > 0L && count - first > 1) {
            (count - first - 1) * 1_000f / durationMs
        } else {
            Float.NaN
        }
    }

    private fun configureNotch(estimate: PeriodicNoiseEstimate) {
        notchActive = false
        b0 = 1.0
        b1 = 0.0
        b2 = 0.0
        a1 = 0.0
        a2 = 0.0

        if (!estimate.active || !estimate.frequencyHz.isFinite() ||
            !estimate.sampleRateHz.isFinite()
        ) {
            return
        }

        val sampleRate = estimate.sampleRateHz.toDouble()
        val frequency = estimate.frequencyHz.toDouble()
        if (sampleRate <= frequency * 2.1) return

        val omega = 2.0 * PI * frequency / sampleRate
        val cosine = cos(omega)
        val alpha = sin(omega) / (2.0 * NOTCH_Q)
        val a0 = 1.0 + alpha
        b0 = 1.0 / a0
        b1 = -2.0 * cosine / a0
        b2 = 1.0 / a0
        a1 = -2.0 * cosine / a0
        a2 = (1.0 - alpha) / a0
        notchActive = true
    }

    private fun primeNotch(value: Float) {
        val initial = value.toDouble()
        x1 = initial
        x2 = initial
        y1 = initial
        y2 = initial
    }

    private fun processNotch(value: Float): Float {
        val input = value.toDouble()
        val output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = input
        y2 = y1
        y1 = output
        return output.toFloat()
    }

    private fun resetNotchState(keepCoefficients: Boolean = false) {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
        if (!keepCoefficients) {
            notchActive = false
            b0 = 1.0
            b1 = 0.0
            b2 = 0.0
            a1 = 0.0
            a2 = 0.0
        }
    }

    private fun physicalIndex(logicalIndex: Int): Int = (start + logicalIndex) % capacity

    private fun emptyStats() = LiveSignalStats(
        count = 0,
        rawCurrent = Float.NaN,
        smoothedCurrent = Float.NaN,
        rawMin = Float.NaN,
        rawMax = Float.NaN,
        rawAverage = Float.NaN,
        rawStdDev = Float.NaN,
        outputMin = Float.NaN,
        outputMax = Float.NaN,
        outputAverage = Float.NaN,
        outputStdDev = Float.NaN,
        sampleRateHz = Float.NaN,
    )

    private fun inactiveEstimate(mode: PeriodicNoiseFilterMode) = PeriodicNoiseEstimate(
        mode = mode,
        active = false,
        frequencyHz = Float.NaN,
        amplitude = Float.NaN,
        confidence = Float.NaN,
        sampleRateHz = Float.NaN,
    )

}
