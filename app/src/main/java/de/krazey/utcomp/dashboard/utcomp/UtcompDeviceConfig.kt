package de.krazey.utcomp.dashboard.utcomp

import de.krazey.utcomp.dashboard.dashboard.DashboardSensor

/**
 * Runtime configuration read from UTCOMP settings packets.
 *
 * Important distinction:
 * - DashboardSensor is a logical data source: Boost, AFR, Oil pressure, ...
 * - ADC/DS/NTC are physical inputs and are user-specific wiring.
 *
 * UTCOMP stores analog input type per ADC channel in GPIO_SETTINGS:
 *   ADCx -> ANALOG_IN_*
 *
 * So we can show the real physical input, for example ADC1/ADC3/ADCVCC1,
 * without hardcoding one user's wiring.
 */
object UtcompDeviceConfig {
    private val loadedPackets = linkedSetOf<String>()

    private val adcInputMode = linkedMapOf<String, Int>()
    private val adcDisplayName = linkedMapOf<String, String>()
    private val temperatureInputMode = linkedMapOf<String, Int>()

    fun updateGpioSettings(b: ByteArray) {
        if (b.size < 11) return

        loadedPackets += "GPIO_SETTINGS"
        adcInputMode.clear()
        adcInputMode["ADC1"] = b.u8(4)
        adcInputMode["ADC2"] = b.u8(5)
        adcInputMode["ADC3"] = b.u8(6)
        adcInputMode["ADC4"] = b.u8(7)
        adcInputMode["ADC5"] = b.u8(8)
        adcInputMode["ADCVCC1"] = b.u8(9)
        adcInputMode["ADCVCC2"] = b.u8(10)
    }

    fun updateTemperatureSettings(b: ByteArray) {
        if (b.size < 45) return

        loadedPackets += "TEMPERATURES_SETTINGS"
        temperatureInputMode.clear()
        temperatureInputMode["DS-A"] = b.u8(0)
        temperatureInputMode["DS-B"] = b.u8(1)
        temperatureInputMode["DS-C"] = b.u8(2)
        temperatureInputMode["DS-D"] = b.u8(3)
        temperatureInputMode["NTC1"] = b.u8(4)
        temperatureInputMode["NTC2"] = b.u8(5)
        temperatureInputMode["NTC3"] = b.u8(30)
    }

    fun updateAnalogOscSettings1(b: ByteArray) {
        if (b.size < 43) return

        loadedPackets += "ANALOG_OSC_SETTINGS1"
        adcDisplayName["ADC1"] = b.ascii9(7)
        adcDisplayName["ADC2"] = b.ascii9(16)
        adcDisplayName["ADC3"] = b.ascii9(25)
        adcDisplayName["ADC4"] = b.ascii9(34)
    }

    fun updateAnalogOscSettings2(b: ByteArray) {
        if (b.size < 27) return

        loadedPackets += "ANALOG_OSC_SETTINGS2"
        adcDisplayName["ADC5"] = b.ascii9(0)
        adcDisplayName["ADCVCC1"] = b.ascii9(9)
        adcDisplayName["ADCVCC2"] = b.ascii9(18)
    }

    fun subtitleFor(sensor: DashboardSensor): String =
        physicalInputFor(sensor)?.subtitle ?: genericSubtitle(sensor)

    fun debugSummary(): String =
        buildString {
            append("loaded=${loadedPackets.joinToString("+").ifBlank { "none" }}")
            if (adcInputMode.isNotEmpty()) {
                append(" adcModes=")
                append(
                    adcInputMode.entries.joinToString(",") { (input, mode) ->
                        "$input:${analogModeName(mode)}($mode)"
                    },
                )
            }
            if (adcDisplayName.values.any { it.isNotBlank() }) {
                append(" adcNames=")
                append(
                    adcDisplayName.entries
                        .filter { it.value.isNotBlank() }
                        .joinToString(",") { "${it.key}:${it.value}" },
                )
            }
            if (temperatureInputMode.isNotEmpty()) {
                append(" tempInputs=")
                append(temperatureInputMode.entries.joinToString(",") { "${it.key}:${it.value}" })
            }
        }

    private data class PhysicalInput(
        val input: String,
        val name: String,
        val mode: Int,
    ) {
        val subtitle: String
            get() = if (name.isNotBlank()) "$input · $name" else input
    }

    private fun physicalInputFor(sensor: DashboardSensor): PhysicalInput? {
        val modes = analogModesFor(sensor)
        if (modes.isNotEmpty()) {
            val adc = adcInputMode.entries.firstOrNull { (_, mode) -> mode in modes }
            if (adc != null) {
                val input = adc.key
                return PhysicalInput(
                    input = input,
                    name = adcDisplayName[input].orEmpty().sanitizeAdcName(input),
                    mode = adc.value,
                )
            }
        }

        return when (sensor) {
            DashboardSensor.OUTSIDE_TEMP -> temperatureInputForRole(1, "outside temperature")
            DashboardSensor.INSIDE_TEMP -> temperatureInputForRole(2, "inside temperature")
            DashboardSensor.OIL_TEMP -> temperatureInputForRole(4, "oil temperature")
            else -> null
        }
    }

    private fun temperatureInputForRole(role: Int, fallbackName: String): PhysicalInput? {
        val entry = temperatureInputMode.entries.firstOrNull { (_, value) -> value == role }
            ?: return null

        val ntcIndex = when (entry.key) {
            "NTC1" -> 0
            "NTC2" -> 1
            "NTC3" -> 2
            else -> null
        }
        if (ntcIndex != null) {
            val adc = adcInputMode.entries.firstOrNull { (_, mode) ->
                mode == ANALOG_IN_NTC1 + ntcIndex
            }
            if (adc != null) {
                val input = adc.key
                val customName = adcDisplayName[input].orEmpty().sanitizeAdcName(input)
                return PhysicalInput(
                    input = input,
                    name = listOf(entry.key, customName)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    mode = adc.value,
                )
            }
        }

        return PhysicalInput(
            input = entry.key,
            name = fallbackName,
            mode = entry.value,
        )
    }

    private fun analogModesFor(sensor: DashboardSensor): Set<Int> =
        when (sensor) {
            DashboardSensor.BOOST -> setOf(ANALOG_IN_PRESSURE_BOOST)
            DashboardSensor.AFR -> setOf(ANALOG_IN_LAMBDAO2SENSOR, ANALOG_IN_AFR)
            DashboardSensor.OIL_PRESSURE -> setOf(ANALOG_IN_PRESSURE_OIL)
            DashboardSensor.OIL_TEMP -> setOf(ANALOG_IN_NTC1, ANALOG_IN_NTC2, ANALOG_IN_NTC3)
            DashboardSensor.BATTERY -> setOf(ANALOG_IN_BATTERY_VOLTAGE)
            DashboardSensor.OUTSIDE_TEMP,
            DashboardSensor.INSIDE_TEMP,
            DashboardSensor.TIME -> emptySet()
        }

    private fun genericSubtitle(sensor: DashboardSensor): String =
        when (sensor) {
            DashboardSensor.BOOST -> "boost pressure"
            DashboardSensor.AFR -> "AFR / wideband"
            DashboardSensor.OIL_TEMP -> "oil temperature"
            DashboardSensor.OIL_PRESSURE -> "oil pressure"
            DashboardSensor.BATTERY -> "battery voltage"
            DashboardSensor.OUTSIDE_TEMP -> "outside temperature"
            DashboardSensor.INSIDE_TEMP -> "inside temperature"
            DashboardSensor.TIME -> "system time"
        }

    private fun analogModeName(mode: Int): String =
        when (mode) {
            ANALOG_IN_DISABLED -> "DISABLED"
            ANALOG_IN_OSCILLOSCOPE -> "OSCILLOSCOPE"
            ANALOG_IN_LAMBDAO2SENSOR -> "LAMBDAO2"
            ANALOG_IN_AFR -> "AFR"
            ANALOG_IN_AFR2 -> "AFR2"
            ANALOG_IN_PRESSURE_BOOST -> "PRESSURE_BOOST"
            ANALOG_IN_PRESSURE_OIL -> "PRESSURE_OIL"
            ANALOG_IN_PRESSURE_CR -> "PRESSURE_CR"
            ANALOG_IN_EGT -> "EGT1"
            ANALOG_IN_EGT2 -> "EGT2"
            ANALOG_IN_EGT3 -> "EGT3"
            ANALOG_IN_EGT4 -> "EGT4"
            ANALOG_IN_NTC1 -> "NTC1"
            ANALOG_IN_NTC2 -> "NTC2"
            ANALOG_IN_NTC3 -> "NTC3"
            ANALOG_IN_FUEL_LEVEL -> "FUEL_LEVEL"
            ANALOG_IN_GEAR_SENSOR -> "GEAR_SENSOR"
            ANALOG_IN_EGT5 -> "EGT5"
            ANALOG_IN_EGT6 -> "EGT6"
            ANALOG_IN_BATTERY_VOLTAGE -> "BATTERY_VOLTAGE"
            else -> "UNKNOWN"
        }

    private fun String.sanitizeAdcName(input: String): String {
        val trimmed = trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.equals(input, ignoreCase = true)) return ""
        return trimmed
    }

    private fun ByteArray.ascii9(offset: Int): String {
        if (offset !in indices) return ""
        val len = minOf(9, size - offset)
        return copyOfRange(offset, offset + len)
            .takeWhile { it.toInt() != 0 }
            .toByteArray()
            .toString(Charsets.US_ASCII)
            .trim()
    }

    private const val ANALOG_IN_DISABLED = 0
    private const val ANALOG_IN_OSCILLOSCOPE = 1
    private const val ANALOG_IN_LAMBDAO2SENSOR = 2
    private const val ANALOG_IN_AFR = 3
    private const val ANALOG_IN_AFR2 = 4
    private const val ANALOG_IN_PRESSURE_BOOST = 5
    private const val ANALOG_IN_PRESSURE_OIL = 6
    private const val ANALOG_IN_PRESSURE_CR = 7
    private const val ANALOG_IN_EGT = 8
    private const val ANALOG_IN_EGT2 = 9
    private const val ANALOG_IN_EGT3 = 10
    private const val ANALOG_IN_EGT4 = 11
    private const val ANALOG_IN_NTC1 = 12
    private const val ANALOG_IN_NTC2 = 13
    private const val ANALOG_IN_NTC3 = 14
    private const val ANALOG_IN_FUEL_LEVEL = 15
    private const val ANALOG_IN_GEAR_SENSOR = 16
    private const val ANALOG_IN_EGT5 = 17
    private const val ANALOG_IN_EGT6 = 18
    private const val ANALOG_IN_BATTERY_VOLTAGE = 19
}
