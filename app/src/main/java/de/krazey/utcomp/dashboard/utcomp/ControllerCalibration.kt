package de.krazey.utcomp.dashboard.utcomp

import de.krazey.utcomp.dashboard.protocol.TransmitterConstants

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

internal data class ControllerCalibration(
    val afr: LinearCalibration,
    val boost: LinearCalibration,
    val oilPressure: LinearCalibration,
    val ntc: List<NtcCalibration>,
    val analogInputModes: List<Int>,
    val vrefMillivolts: Int,
) {
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

    companion object {
        const val TEMPERATURE_ROLE_OIL = 4
        const val ANALOG_MODE_AFR = 3
        const val ANALOG_MODE_BOOST = 5
        const val ANALOG_MODE_OIL_PRESSURE = 6
        const val ANALOG_MODE_NTC1 = 12

        private val PHYSICAL_ANALOG_INPUTS = listOf(
            "ADC1",
            "ADC2",
            "ADC3",
            "ADC4",
            "ADC5",
            "ADCVCC1",
            "ADCVCC2",
        )
    }
}

/**
 * Reads and updates only the calibration fields verified against the desktop
 * UTCOMP application. Encoders always start from controller payload copies so
 * unrelated and not-yet-understood bytes are preserved exactly.
 */
internal object ControllerCalibrationCodec {
    private const val PAYLOAD_SIZE = 48

    val requiredPids: List<Int> = listOf(
        TransmitterConstants.UtcompPid.TEMPERATURES_SETTINGS,
        TransmitterConstants.UtcompPid.GPIO_SETTINGS,
        TransmitterConstants.UtcompPid.ANALOG_SETTINGS1,
        TransmitterConstants.UtcompPid.VREF_SETTINGS,
    )

    val writablePids: Set<Int> = setOf(
        TransmitterConstants.UtcompPid.TEMPERATURES_SETTINGS,
        TransmitterConstants.UtcompPid.ANALOG_SETTINGS1,
        TransmitterConstants.UtcompPid.VREF_SETTINGS,
    )

    fun accepts(pid: Int): Boolean = pid in requiredPids

    fun decode(payloads: Map<Int, ByteArray>): ControllerCalibration? {
        if (!requiredPids.all { payloads[it]?.size == PAYLOAD_SIZE }) return null

        val temperatures = payloads.getValue(
            TransmitterConstants.UtcompPid.TEMPERATURES_SETTINGS,
        )
        val gpio = payloads.getValue(TransmitterConstants.UtcompPid.GPIO_SETTINGS)
        val analog = payloads.getValue(TransmitterConstants.UtcompPid.ANALOG_SETTINGS1)
        val vref = payloads.getValue(TransmitterConstants.UtcompPid.VREF_SETTINGS)

        return ControllerCalibration(
            afr = LinearCalibration(analog.f32le(4), analog.f32le(8)),
            boost = LinearCalibration(analog.f32le(12), analog.f32le(16)),
            oilPressure = LinearCalibration(analog.f32le(20), analog.f32le(24)),
            ntc = listOf(
                NtcCalibration(
                    role = temperatures.u8(4),
                    r25Ohms = temperatures.f32le(6),
                    betaKelvin = temperatures.f32le(10),
                    correctionCelsius = temperatures.f32le(14),
                ),
                NtcCalibration(
                    role = temperatures.u8(5),
                    r25Ohms = temperatures.f32le(18),
                    betaKelvin = temperatures.f32le(22),
                    correctionCelsius = temperatures.f32le(26),
                ),
                NtcCalibration(
                    role = temperatures.u8(30),
                    r25Ohms = temperatures.f32le(31),
                    betaKelvin = temperatures.f32le(35),
                    correctionCelsius = temperatures.f32le(39),
                ),
            ),
            analogInputModes = (4..10).map(gpio::u8),
            vrefMillivolts = vref.u16le(0),
        )
    }

    fun encode(
        originalPayloads: Map<Int, ByteArray>,
        calibration: ControllerCalibration,
    ): Map<Int, ByteArray> {
        require(calibration.ntc.size == 3) { "exactly three NTC profiles are required" }
        val original = requireNotNull(decode(originalPayloads)) {
            "complete controller calibration payloads are required"
        }

        val temperatures = originalPayload(
            originalPayloads,
            TransmitterConstants.UtcompPid.TEMPERATURES_SETTINGS,
        )
        calibration.ntc[0].writeChangesFrom(original.ntc[0], temperatures, 6)
        calibration.ntc[1].writeChangesFrom(original.ntc[1], temperatures, 18)
        calibration.ntc[2].writeChangesFrom(original.ntc[2], temperatures, 31)

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

        val vref = originalPayload(
            originalPayloads,
            TransmitterConstants.UtcompPid.VREF_SETTINGS,
        )
        if (original.vrefMillivolts != calibration.vrefMillivolts) {
            vref.putU16le(0, calibration.vrefMillivolts)
        }

        return linkedMapOf(
            TransmitterConstants.UtcompPid.TEMPERATURES_SETTINGS to temperatures,
            TransmitterConstants.UtcompPid.ANALOG_SETTINGS1 to analog,
            TransmitterConstants.UtcompPid.VREF_SETTINGS to vref,
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

    private fun ByteArray.putChangedFloat(offset: Int, original: Float, updated: Float) {
        if (original.toRawBits() != updated.toRawBits()) putF32le(offset, updated)
    }
}
