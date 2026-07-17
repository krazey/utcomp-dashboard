package de.krazey.utcomp.dashboard.utcomp

import de.krazey.utcomp.dashboard.protocol.TransmitterConstants
import de.krazey.utcomp.dashboard.protocol.TransmitterPacket
import de.krazey.utcomp.dashboard.protocol.TransmitterPacketParser
import de.krazey.utcomp.dashboard.protocol.UsbPacket
import de.krazey.utcomp.dashboard.transport.UsbRecoveryPolicy
import kotlin.math.abs

private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.0001f) {
    check(abs(expected - actual) <= tolerance) { "expected=$expected actual=$actual" }
}

private fun settingsPayloads(): Map<Int, ByteArray> {
    val temperatures = ByteArray(48) { (0x40 + it).toByte() }
    temperatures[4] = 4
    temperatures[5] = 0
    temperatures[30] = 0
    temperatures[0] = 1
    temperatures[1] = 2
    temperatures[2] = 3
    temperatures[3] = 0
    temperatures.putF32le(6, 10_250f)
    temperatures.putF32le(10, 3_512f)
    temperatures.putF32le(14, 0f)
    temperatures.putF32le(18, 9_900f)
    temperatures.putF32le(22, 3_400f)
    temperatures.putF32le(26, 1f)
    temperatures.putF32le(31, 10_000f)
    temperatures.putF32le(35, 3_500f)
    temperatures.putF32le(39, -1f)

    val gpio = ByteArray(48) { (0x20 + it).toByte() }
    listOf(3, 0, 5, 6, 0, 12, 0).forEachIndexed { index, mode ->
        gpio[4 + index] = mode.toByte()
    }

    val analog = ByteArray(48) { (0x60 + it).toByte() }
    analog.putF32le(4, 2f)
    analog.putF32le(8, 10f)
    analog.putF32le(12, 2f)
    analog.putF32le(16, -1f)
    analog.putF32le(20, 2.5f)
    analog.putF32le(24, -1.25f)

    return mapOf(
        TransmitterConstants.UtcompPid.TEMPERATURES_SETTINGS to temperatures,
        TransmitterConstants.UtcompPid.GPIO_SETTINGS to gpio,
        TransmitterConstants.UtcompPid.ANALOG_SETTINGS1 to analog,
    )
}

fun main() {
    check(TransmitterConstants.UtcompPid.TEMPERATURES_SETTINGS == 0x1002)
    check(TransmitterConstants.UtcompPid.SETTINGS_STOP == 0x1030)
    check(ControllerCalibrationCodec.requiredPids.size == 3)
    check(TransmitterConstants.UtcompPid.VREF_SETTINGS !in ControllerCalibrationCodec.requiredPids)
    check(TransmitterConstants.UtcompPid.VREF_SETTINGS !in ControllerCalibrationCodec.writablePids)

    val originals = settingsPayloads()
    val decoded = checkNotNull(ControllerCalibrationCodec.decode(originals))
    assertClose(2f, decoded.afr.a)
    assertClose(10f, decoded.afr.b)
    assertClose(2f, decoded.boost.a)
    assertClose(-1f, decoded.boost.b)
    assertClose(2.5f, decoded.oilPressure.a)
    assertClose(-1.25f, decoded.oilPressure.b)
    assertClose(10_250f, decoded.ntc[0].r25Ohms)
    assertClose(3_512f, decoded.ntc[0].betaKelvin)
    check(decoded.temperatureRoles == listOf(1, 2, 3, 0, 4, 0, 0))
    check(decoded.oilTemperatureNtcIndex() == 0)
    check(decoded.inputForAnalogMode(ControllerCalibration.ANALOG_MODE_AFR) == "ADC1")
    check(decoded.inputForAnalogMode(ControllerCalibration.ANALOG_MODE_BOOST) == "ADC3")
    check(decoded.inputForAnalogMode(ControllerCalibration.ANALOG_MODE_OIL_PRESSURE) == "ADC4")
    check(decoded.adcChannelForAnalogMode(ControllerCalibration.ANALOG_MODE_AFR) == 1)
    check(decoded.adcChannelForAnalogMode(ControllerCalibration.ANALOG_MODE_BOOST) == 3)
    check(decoded.adcChannelForAnalogMode(ControllerCalibration.ANALOG_MODE_OIL_PRESSURE) == 4)
    check(decoded.physicalInputForNtc(0) == "ADCVCC1")
    check(decoded.adcChannelForAnalogMode(ControllerCalibration.ANALOG_MODE_NTC1) == 6)
    check(decoded.adcChannelForAnalogMode(99) == null)

    val edited = decoded.copy(
        afr = decoded.afr.copy(a = 2.125f),
        analogInputModes = decoded.analogInputModes.toMutableList().apply {
            this[1] = ControllerCalibration.ANALOG_MODE_AFR2
        },
        ntc = decoded.ntc.toMutableList().apply {
            this[0] = this[0].copy(
                r25Ohms = 10_300f,
                betaKelvin = 3_520f,
                correctionCelsius = 0.5f,
            )
        },
    ).withTemperatureRoles(listOf(1, 2, 3, 5, 4, 6, 0))
    val encoded = ControllerCalibrationCodec.encode(originals, edited)
    val changed = ControllerCalibrationCodec.changedPayloads(originals, encoded)
    check(changed.keys == setOf(
        TransmitterConstants.UtcompPid.TEMPERATURES_SETTINGS,
        TransmitterConstants.UtcompPid.GPIO_SETTINGS,
        TransmitterConstants.UtcompPid.ANALOG_SETTINGS1,
    ))

    val roundTripPayloads = originals.toMutableMap().apply { putAll(encoded) }
    val roundTrip = checkNotNull(ControllerCalibrationCodec.decode(roundTripPayloads))
    assertClose(2.125f, roundTrip.afr.a)
    assertClose(10_300f, roundTrip.ntc[0].r25Ohms)
    assertClose(3_520f, roundTrip.ntc[0].betaKelvin)
    assertClose(0.5f, roundTrip.ntc[0].correctionCelsius)
    check(roundTrip.temperatureRoles == listOf(1, 2, 3, 5, 4, 6, 0))
    check(roundTrip.analogInputModes[1] == ControllerCalibration.ANALOG_MODE_AFR2)
    val analogOriginal = originals.getValue(TransmitterConstants.UtcompPid.ANALOG_SETTINGS1)
    val analogUpdated = encoded.getValue(TransmitterConstants.UtcompPid.ANALOG_SETTINGS1)
    val analogChangedOffsets = ControllerCalibrationCodec.changedByteOffsets(
        analogOriginal,
        analogUpdated,
    )
    check(analogChangedOffsets.isNotEmpty())
    check(analogChangedOffsets.all { it in 4..7 }) { analogChangedOffsets }
    analogOriginal.indices
        .filterNot(analogChangedOffsets::contains)
        .forEach { offset ->
            check(analogOriginal[offset] == analogUpdated[offset]) {
                "unrelated analog byte changed at $offset"
            }
        }

    val gpioOriginal = originals.getValue(TransmitterConstants.UtcompPid.GPIO_SETTINGS)
    val gpioUpdated = encoded.getValue(TransmitterConstants.UtcompPid.GPIO_SETTINGS)
    check(ControllerCalibrationCodec.changedByteOffsets(gpioOriginal, gpioUpdated) == setOf(5))

    val temperaturesOriginal = originals.getValue(
        TransmitterConstants.UtcompPid.TEMPERATURES_SETTINGS,
    )
    val temperaturesUpdated = encoded.getValue(
        TransmitterConstants.UtcompPid.TEMPERATURES_SETTINGS,
    )
    val temperatureChanges = ControllerCalibrationCodec.changedByteOffsets(
        temperaturesOriginal,
        temperaturesUpdated,
    )
    check(temperatureChanges.containsAll(setOf(3, 5)))
    check(temperatureChanges.all { it == 3 || it == 5 || it in 6..17 }) {
        temperatureChanges
    }

    val unchanged = ControllerCalibrationCodec.encode(originals, decoded)
    check(ControllerCalibrationCodec.changedPayloads(originals, unchanged).isEmpty())

    val settingsWrite = TransmitterPacket.transfer(
        TransmitterConstants.UtcompPid.ANALOG_SETTINGS1,
        encoded.getValue(TransmitterConstants.UtcompPid.ANALOG_SETTINGS1),
    )
    val settingsReport = TransmitterPacketParser.toUsbPackets(settingsWrite).single().toReport()
    check(settingsReport[0].toInt() and 0xff == UsbPacket.CMD_TRANSFER_DATA)
    check(settingsReport[1].toInt() and 0xff == 0x10)
    check(settingsReport[2].toInt() and 0xff == 0x0A)
    check(settingsReport[3].toInt() and 0xff == TransmitterConstants.Source.HOST.id)
    check(settingsReport[4].toInt() and 0xff == TransmitterConstants.Ack.NACK.id)
    check(settingsReport[8].toInt() and 0xff == 48)
    check(settingsReport[15].toInt() and 0xff == UsbPacket.DataSplit.NONE.id)
    check(UsbRecoveryPolicy.writeAttempts(UsbPacket.CMD_TRANSFER_DATA) == 1)

    val commit = TransmitterPacket.transfer(
        TransmitterConstants.UtcompPid.SETTINGS_STOP,
        ByteArray(48),
    )
    val commitReport = TransmitterPacketParser.toUsbPackets(commit).single().toReport()
    check(commitReport[1].toInt() and 0xff == 0x10)
    check(commitReport[2].toInt() and 0xff == 0x30)
    check(commitReport.copyOfRange(16, 64).all { it == 0.toByte() })

    println("Controller calibration codec tests passed")
}
