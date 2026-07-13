package de.krazey.utcomp.dashboard.logging

import de.krazey.utcomp.dashboard.protocol.TransmitterConstants
import de.krazey.utcomp.dashboard.utcomp.UtcompDataSnapshot
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

data class LiveSignalStats(
    val count: Int,
    val rawCurrent: Float,
    val smoothedCurrent: Float,
    val rawMin: Float,
    val rawMax: Float,
    val rawAverage: Float,
    val rawStdDev: Float,
    val sampleRateHz: Float,
) {
    val peakToPeak: Float
        get() = if (rawMin.isFinite() && rawMax.isFinite()) rawMax - rawMin else Float.NaN
}

class LiveSignalBuffer(
    private val capacity: Int = 2_500,
) {
    init {
        require(capacity > 1)
    }

    private val elapsedMs = LongArray(capacity)
    private val rawValues = FloatArray(capacity)
    private val smoothedValues = FloatArray(capacity)
    private var start = 0
    private var count = 0

    var smoothingAlpha: Float = 1f
        private set

    val size: Int
        get() = count

    fun clear() {
        start = 0
        count = 0
    }

    fun setSmoothingAlpha(alpha: Float) {
        smoothingAlpha = alpha.coerceIn(0.01f, 1f)
        recomputeSmoothed()
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
        val previous = if (count <= 1) {
            rawValue
        } else {
            smoothedValues[physicalIndex(count - 2)]
        }
        smoothedValues[insertIndex] = smooth(previous, rawValue, smoothingAlpha)
    }

    fun timeAt(index: Int): Long = elapsedMs[physicalIndex(index)]

    fun rawAt(index: Int): Float = rawValues[physicalIndex(index)]

    fun smoothedAt(index: Int): Float = smoothedValues[physicalIndex(index)]

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
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        var sum = 0.0
        var sumSquares = 0.0
        var samples = 0
        for (index in first until count) {
            val value = rawAt(index)
            if (!value.isFinite()) continue
            min = kotlin.math.min(min, value)
            max = kotlin.math.max(max, value)
            sum += value
            sumSquares += value.toDouble() * value.toDouble()
            samples++
        }
        if (samples == 0) return emptyStats()

        val average = (sum / samples).toFloat()
        val variance = (sumSquares / samples - average.toDouble() * average.toDouble())
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
            rawMin = min,
            rawMax = max,
            rawAverage = average,
            rawStdDev = sqrt(variance).toFloat(),
            sampleRateHz = rate,
        )
    }

    private fun recomputeSmoothed() {
        if (count == 0) return
        var previous = rawAt(0)
        smoothedValues[physicalIndex(0)] = previous
        for (index in 1 until count) {
            val raw = rawAt(index)
            previous = smooth(previous, raw, smoothingAlpha)
            smoothedValues[physicalIndex(index)] = previous
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
        sampleRateHz = Float.NaN,
    )

    companion object {
        fun smooth(previous: Float, raw: Float, alpha: Float): Float {
            if (!previous.isFinite()) return raw
            val safeAlpha = alpha.coerceIn(0.01f, 1f)
            return previous + (raw - previous) * safeAlpha
        }
    }
}
