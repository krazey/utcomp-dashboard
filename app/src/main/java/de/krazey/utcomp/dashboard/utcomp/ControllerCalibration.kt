package de.krazey.utcomp.dashboard.utcomp

import de.krazey.utcomp.dashboard.protocol.TransmitterConstants

internal data class SettingChoice(
    val value: Int,
    val label: String,
)

internal data class LinearCalibration(
    val a: Float,
    val b: Float,
)

internal data class NtcCalibration(
    val role: Int,
    val r25Ohms: Float,
    val betaKelvin: Float,
    val correctionCelsius: Float,
)

internal data class AnalogAveraging(
    val egtAfr: Int,
    val oilPressure: Int,
    val fuelPressure: Int,
    val boostPressure: Int,
    val ntc: Int,
)

internal data class ControllerCalibration(
    val afr: LinearCalibration,
    val afrLambda: Boolean,
    val boost: LinearCalibration,
    val oilPressure: LinearCalibration,
    val fuelPressure: LinearCalibration,
    val egt1: LinearCalibration,
    val egt2: LinearCalibration,
    val fuelLevel: LinearCalibration,
    val adcVoltage1: LinearCalibration,
    val adcVoltage2: LinearCalibration,
    val averaging: AnalogAveraging,
    val rpmMode: Int,
    val rpmMultiplierCode: Int,
    val digitalTemperatureRoles: List<Int>,
    val ntc: List<NtcCalibration>,
    val analogInputModes: List<Int>,
) {
    init {
        require(digitalTemperatureRoles.size == DIGITAL_TEMPERATURE_INPUTS.size)
        require(ntc.size == NTC_INPUTS.size)
        require(analogInputModes.size == PHYSICAL_ANALOG_INPUTS.size)
    }

    val temperatureRoles: List<Int>
        get() = digitalTemperatureRoles + ntc.map(NtcCalibration::role)

    fun inputForAnalogMode(mode: Int): String? =
        analogInputModes.indexOfFirst { it == mode }
            .takeIf { it >= 0 }
            ?.let(PHYSICAL_ANALOG_INPUTS::get)

    fun adcChannelForAnalogMode(mode: Int): Int? =
        analogInputModes.indexOfFirst { it == mode }
            .takeIf { it >= 0 }
            ?.plus(1)

    fun oilTemperatureNtcIndex(): Int? =
        ntc.indexOfFirst { it.role == TEMPERATURE_ROLE_OIL }
            .takeIf { it >= 0 }

    fun physicalInputForNtc(index: Int): String? =
        inputForAnalogMode(ANALOG_MODE_NTC1 + index)

    fun withTemperatureRoles(roles: List<Int>): ControllerCalibration {
        require(roles.size == TEMPERATURE_INPUTS.size)
        return copy(
            digitalTemperatureRoles = roles.take(DIGITAL_TEMPERATURE_INPUTS.size),
            ntc = ntc.mapIndexed { index, profile ->
                profile.copy(role = roles[DIGITAL_TEMPERATURE_INPUTS.size + index])
            },
        )
    }

    companion object {
        const val TEMPERATURE_ROLE_DISABLED = 0
        const val TEMPERATURE_ROLE_OUTSIDE = 1
        const val TEMPERATURE_ROLE_INSIDE = 2
        const val TEMPERATURE_ROLE_ENGINE = 3
        const val TEMPERATURE_ROLE_OIL = 4
        const val TEMPERATURE_ROLE_USER1 = 5
        const val TEMPERATURE_ROLE_USER2 = 6

        const val ANALOG_MODE_DISABLED = 0
        const val ANALOG_MODE_OSCILLOSCOPE = 1
        const val ANALOG_MODE_LAMBDA_O2 = 2
        const val ANALOG_MODE_AFR = 3
        const val ANALOG_MODE_AFR2 = 4
        const val ANALOG_MODE_BOOST = 5
        const val ANALOG_MODE_OIL_PRESSURE = 6
        const val ANALOG_MODE_FUEL_PRESSURE = 7
        const val ANALOG_MODE_EGT1 = 8
        const val ANALOG_MODE_EGT2 = 9
        const val ANALOG_MODE_EGT3 = 10
        const val ANALOG_MODE_EGT4 = 11
        const val ANALOG_MODE_NTC1 = 12
        const val ANALOG_MODE_NTC2 = 13
        const val ANALOG_MODE_NTC3 = 14
        const val ANALOG_MODE_FUEL_LEVEL = 15
        const val ANALOG_MODE_GEAR = 16
        const val ANALOG_MODE_EGT5 = 17
        const val ANALOG_MODE_EGT6 = 18
        const val ANALOG_MODE_BATTERY = 19

        const val RPM_MODE_INJECTION = 0
        const val RPM_MODE_HALL = 1

        val AFR_UNIT_CHOICES = listOf(
            SettingChoice(0, "AFR"),
            SettingChoice(1, "Lambda"),
        )

        val RPM_MODE_CHOICES = listOf(
            SettingChoice(RPM_MODE_INJECTION, "Injection signal"),
            SettingChoice(RPM_MODE_HALL, "RPM Hall sensor"),
        )

        // These two desktop-program values are verified by the supplied profiles.
        // Unknown controller codes are preserved unless the user explicitly selects one.
        val RPM_MULTIPLIER_CHOICES = listOf(
            SettingChoice(2, "2.0×"),
            SettingChoice(6, "6.5×"),
        )

        val PHYSICAL_ANALOG_INPUTS = listOf(
            "ADC1",
            "ADC2",
            "ADC3",
            "ADC4",
            "ADC5",
            "ADCVCC1",
            "ADCVCC2",
        )

        val DIGITAL_TEMPERATURE_INPUTS = listOf("DS-A", "DS-B", "DS-C", "DS-D")
        val NTC_INPUTS = listOf("NTC1", "NTC2", "NTC3")
        val TEMPERATURE_INPUTS = DIGITAL_TEMPERATURE_INPUTS + NTC_INPUTS

        val ANALOG_MODE_CHOICES = listOf(
            SettingChoice(ANALOG_MODE_DISABLED, "Disabled"),
            SettingChoice(ANALOG_MODE_OSCILLOSCOPE, "Oscilloscope / raw voltage"),
            SettingChoice(ANALOG_MODE_LAMBDA_O2, "Lambda O2 sensor"),
            SettingChoice(ANALOG_MODE_AFR, "AFR 1"),
            SettingChoice(ANALOG_MODE_AFR2, "AFR 2"),
            SettingChoice(ANALOG_MODE_BOOST, "Boost pressure"),
            SettingChoice(ANALOG_MODE_OIL_PRESSURE, "Oil pressure"),
            SettingChoice(ANALOG_MODE_FUEL_PRESSURE, "Fuel pressure"),
            SettingChoice(ANALOG_MODE_EGT1, "EGT 1"),
            SettingChoice(ANALOG_MODE_EGT2, "EGT 2"),
            SettingChoice(ANALOG_MODE_EGT3, "EGT 3"),
            SettingChoice(ANALOG_MODE_EGT4, "EGT 4"),
            SettingChoice(ANALOG_MODE_NTC1, "NTC 1"),
            SettingChoice(ANALOG_MODE_NTC2, "NTC 2"),
            SettingChoice(ANALOG_MODE_NTC3, "NTC 3"),
            SettingChoice(ANALOG_MODE_FUEL_LEVEL, "Fuel level"),
            SettingChoice(ANALOG_MODE_GEAR, "Gear-position sensor"),
            SettingChoice(ANALOG_MODE_EGT5, "EGT 5"),
            SettingChoice(ANALOG_MODE_EGT6, "EGT 6"),
            SettingChoice(ANALOG_MODE_BATTERY, "Battery voltage"),
        )

        val TEMPERATURE_ROLE_CHOICES = listOf(
            SettingChoice(TEMPERATURE_ROLE_DISABLED, "Disabled"),
            SettingChoice(TEMPERATURE_ROLE_OUTSIDE, "Outside temperature"),
            SettingChoice(TEMPERATURE_ROLE_INSIDE, "Inside temperature"),
            SettingChoice(TEMPERATURE_ROLE_ENGINE, "Engine / coolant temperature"),
            SettingChoice(TEMPERATURE_ROLE_OIL, "Oil temperature"),
            SettingChoice(TEMPERATURE_ROLE_USER1, "User temperature 1"),
            SettingChoice(TEMPERATURE_ROLE_USER2, "User temperature 2"),
        )

        fun analogModeLabel(mode: Int): String =
            ANALOG_MODE_CHOICES.firstOrNull { it.value == mode }?.label ?: "Unknown ($mode)"

        fun temperatureRoleLabel(role: Int): String =
            TEMPERATURE_ROLE_CHOICES.firstOrNull { it.value == role }?.label
                ?: "Unknown ($role)"
    }
}

/**
 * Reads and updates only fields verified against the desktop UTCOMP application.
 * Encoders always start from controller payload copies so unrelated and
 * not-yet-understood bytes are preserved exactly.
 */
internal object ControllerCalibrationCodec {
    private const val PAYLOAD_SIZE = 48
    private val TEMPERATURE_ROLE_OFFSETS = listOf(0, 1, 2, 3, 4, 5, 30)
    private val NTC_FLOAT_OFFSETS = listOf(6, 18, 31)
    private val ANALOG_INPUT_MODE_OFFSETS = (4..10).toList()
    private val AVERAGING_OFFSETS = listOf(11, 12, 13, 14, 15)

    val requiredPids: List<Int> = listOf(
        TransmitterConstants.UtcompPid.TEMPERATURES_SETTINGS,
        TransmitterConstants.UtcompPid.GPIO_SETTINGS,
        TransmitterConstants.UtcompPid.ANALOG_SETTINGS1,
        TransmitterConstants.UtcompPid.ANALOG_SETTINGS2,
        TransmitterConstants.UtcompPid.GENERAL_SETTINGS1,
    )

    val writablePids: Set<Int> = setOf(
        TransmitterConstants.UtcompPid.TEMPERATURES_SETTINGS,
        TransmitterConstants.UtcompPid.GPIO_SETTINGS,
        TransmitterConstants.UtcompPid.ANALOG_SETTINGS1,
        TransmitterConstants.UtcompPid.ANALOG_SETTINGS2,
        TransmitterConstants.UtcompPid.GENERAL_SETTINGS1,
    )

    fun accepts(pid: Int): Boolean = pid in requiredPids

    fun decode(payloads: Map<Int, ByteArray>): ControllerCalibration? {
        if (!requiredPids.all { payloads[it]?.size == PAYLOAD_SIZE }) return null

        val temperatures = payloads.getValue(
            TransmitterConstants.UtcompPid.TEMPERATURES_SETTINGS,
        )
        val gpio = payloads.getValue(TransmitterConstants.UtcompPid.GPIO_SETTINGS)
        val analog = payloads.getValue(TransmitterConstants.UtcompPid.ANALOG_SETTINGS1)
        val analog2 = payloads.getValue(TransmitterConstants.UtcompPid.ANALOG_SETTINGS2)
        val general = payloads.getValue(TransmitterConstants.UtcompPid.GENERAL_SETTINGS1)

        val roles = TEMPERATURE_ROLE_OFFSETS.map(temperatures::u8)
        return ControllerCalibration(
            afr = LinearCalibration(analog.f32le(4), analog.f32le(8)),
            afrLambda = analog.u8(44) != 0,
            boost = LinearCalibration(analog.f32le(12), analog.f32le(16)),
            oilPressure = LinearCalibration(analog.f32le(20), analog.f32le(24)),
            fuelPressure = LinearCalibration(analog2.f32le(24), analog2.f32le(28)),
            egt1 = LinearCalibration(analog.f32le(28), analog.f32le(32)),
            egt2 = LinearCalibration(analog.f32le(36), analog.f32le(40)),
            fuelLevel = LinearCalibration(analog2.f32le(0), analog2.f32le(4)),
            adcVoltage1 = LinearCalibration(analog2.f32le(8), analog2.f32le(12)),
            adcVoltage2 = LinearCalibration(analog2.f32le(16), analog2.f32le(20)),
            averaging = AnalogAveraging(
                egtAfr = gpio.u8(AVERAGING_OFFSETS[0]),
                oilPressure = gpio.u8(AVERAGING_OFFSETS[1]),
                fuelPressure = gpio.u8(AVERAGING_OFFSETS[2]),
                boostPressure = gpio.u8(AVERAGING_OFFSETS[3]),
                ntc = gpio.u8(AVERAGING_OFFSETS[4]),
            ),
            rpmMode = general.u8(6),
            rpmMultiplierCode = general.u8(7),
            digitalTemperatureRoles = roles.take(4),
            ntc = NTC_FLOAT_OFFSETS.mapIndexed { index, offset ->
                NtcCalibration(
                    role = roles[4 + index],
                    r25Ohms = temperatures.f32le(offset),
                    betaKelvin = temperatures.f32le(offset + 4),
                    correctionCelsius = temperatures.f32le(offset + 8),
                )
            },
            analogInputModes = ANALOG_INPUT_MODE_OFFSETS.map(gpio::u8),
        )
    }

    fun encode(
        originalPayloads: Map<Int, ByteArray>,
        calibration: ControllerCalibration,
    ): Map<Int, ByteArray> {
        val original = requireNotNull(decode(originalPayloads)) {
            "complete controller calibration payloads are required"
        }

        val temperatures = originalPayload(
            originalPayloads,
            TransmitterConstants.UtcompPid.TEMPERATURES_SETTINGS,
        )
        TEMPERATURE_ROLE_OFFSETS.forEachIndexed { index, offset ->
            temperatures.putChangedByte(
                offset,
                original.temperatureRoles[index],
                calibration.temperatureRoles[index],
            )
        }
        calibration.ntc.forEachIndexed { index, profile ->
            profile.writeChangesFrom(original.ntc[index], temperatures, NTC_FLOAT_OFFSETS[index])
        }

        val gpio = originalPayload(
            originalPayloads,
            TransmitterConstants.UtcompPid.GPIO_SETTINGS,
        )
        ANALOG_INPUT_MODE_OFFSETS.forEachIndexed { index, offset ->
            gpio.putChangedByte(
                offset,
                original.analogInputModes[index],
                calibration.analogInputModes[index],
            )
        }
        val averagingBefore = listOf(
            original.averaging.egtAfr,
            original.averaging.oilPressure,
            original.averaging.fuelPressure,
            original.averaging.boostPressure,
            original.averaging.ntc,
        )
        val averagingAfter = listOf(
            calibration.averaging.egtAfr,
            calibration.averaging.oilPressure,
            calibration.averaging.fuelPressure,
            calibration.averaging.boostPressure,
            calibration.averaging.ntc,
        )
        AVERAGING_OFFSETS.forEachIndexed { index, offset ->
            gpio.putChangedByte(offset, averagingBefore[index], averagingAfter[index])
        }

        val analog = originalPayload(
            originalPayloads,
            TransmitterConstants.UtcompPid.ANALOG_SETTINGS1,
        )
        analog.putChangedFloat(4, original.afr.a, calibration.afr.a)
        analog.putChangedFloat(8, original.afr.b, calibration.afr.b)
        analog.putChangedFloat(12, original.boost.a, calibration.boost.a)
        analog.putChangedFloat(16, original.boost.b, calibration.boost.b)
        analog.putChangedFloat(20, original.oilPressure.a, calibration.oilPressure.a)
        analog.putChangedFloat(24, original.oilPressure.b, calibration.oilPressure.b)
        analog.putChangedFloat(28, original.egt1.a, calibration.egt1.a)
        analog.putChangedFloat(32, original.egt1.b, calibration.egt1.b)
        analog.putChangedFloat(36, original.egt2.a, calibration.egt2.a)
        analog.putChangedFloat(40, original.egt2.b, calibration.egt2.b)
        analog.putChangedByte(44, if (original.afrLambda) 1 else 0, if (calibration.afrLambda) 1 else 0)

        val analog2 = originalPayload(
            originalPayloads,
            TransmitterConstants.UtcompPid.ANALOG_SETTINGS2,
        )
        analog2.putChangedFloat(0, original.fuelLevel.a, calibration.fuelLevel.a)
        analog2.putChangedFloat(4, original.fuelLevel.b, calibration.fuelLevel.b)
        analog2.putChangedFloat(8, original.adcVoltage1.a, calibration.adcVoltage1.a)
        analog2.putChangedFloat(12, original.adcVoltage1.b, calibration.adcVoltage1.b)
        analog2.putChangedFloat(16, original.adcVoltage2.a, calibration.adcVoltage2.a)
        analog2.putChangedFloat(20, original.adcVoltage2.b, calibration.adcVoltage2.b)
        analog2.putChangedFloat(24, original.fuelPressure.a, calibration.fuelPressure.a)
        analog2.putChangedFloat(28, original.fuelPressure.b, calibration.fuelPressure.b)

        val general = originalPayload(
            originalPayloads,
            TransmitterConstants.UtcompPid.GENERAL_SETTINGS1,
        )
        general.putChangedByte(6, original.rpmMode, calibration.rpmMode)
        general.putChangedByte(7, original.rpmMultiplierCode, calibration.rpmMultiplierCode)

        return linkedMapOf(
            TransmitterConstants.UtcompPid.TEMPERATURES_SETTINGS to temperatures,
            TransmitterConstants.UtcompPid.GPIO_SETTINGS to gpio,
            TransmitterConstants.UtcompPid.ANALOG_SETTINGS1 to analog,
            TransmitterConstants.UtcompPid.ANALOG_SETTINGS2 to analog2,
            TransmitterConstants.UtcompPid.GENERAL_SETTINGS1 to general,
        )
    }

    fun changedPayloads(
        originalPayloads: Map<Int, ByteArray>,
        encodedPayloads: Map<Int, ByteArray>,
    ): Map<Int, ByteArray> =
        encodedPayloads.filter { (pid, encoded) ->
            val original = originalPayloads[pid]
            original == null || !encoded.contentEquals(original)
        }

    fun changedByteOffsets(original: ByteArray, updated: ByteArray): Set<Int> {
        require(original.size == updated.size) { "payload sizes differ" }
        return original.indices.filterTo(linkedSetOf()) { original[it] != updated[it] }
    }

    private fun originalPayload(payloads: Map<Int, ByteArray>, pid: Int): ByteArray {
        val payload = requireNotNull(payloads[pid]) {
            "missing controller payload for pid=0x%04X".format(pid)
        }
        require(payload.size == PAYLOAD_SIZE) {
            "missing 48-byte controller payload for pid=0x%04X".format(pid)
        }
        return payload.copyOf()
    }

    private fun NtcCalibration.writeChangesFrom(
        original: NtcCalibration,
        payload: ByteArray,
        offset: Int,
    ) {
        payload.putChangedFloat(offset, original.r25Ohms, r25Ohms)
        payload.putChangedFloat(offset + 4, original.betaKelvin, betaKelvin)
        payload.putChangedFloat(
            offset + 8,
            original.correctionCelsius,
            correctionCelsius,
        )
    }

    private fun ByteArray.putChangedByte(offset: Int, original: Int, updated: Int) {
        require(updated in 0..255) { "byte value out of range" }
        if (original != updated) this[offset] = updated.toByte()
    }

    private fun ByteArray.putChangedFloat(offset: Int, original: Float, updated: Float) {
        if (original.toRawBits() != updated.toRawBits()) putF32le(offset, updated)
    }
}
