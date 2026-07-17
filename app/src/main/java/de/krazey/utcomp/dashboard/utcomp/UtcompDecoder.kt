package de.krazey.utcomp.dashboard.utcomp

import de.krazey.utcomp.dashboard.protocol.TransmitterConstants
import de.krazey.utcomp.dashboard.protocol.TransmitterPacket

/** Decodes live UTCOMP protocol packets into the shared dashboard snapshot. */
object UtcompDecoder {
    val snapshot = UtcompDataSnapshot()

    /**
     * Applies [packet] and returns true when it updated decoded state.
     *
     * Debug strings are built only when [debugLog] is supplied. This keeps the
     * normal 20 Hz live-data path free from formatting and temporary lists.
     */
    fun apply(
        packet: TransmitterPacket,
        debugLog: ((String) -> Unit)? = null,
    ): Boolean {
        if (packet.cmd != TransmitterConstants.Command.TRANSFER_DATA) return false
        if (packet.source != TransmitterConstants.Source.DEVICE) return false

        val b = packet.data
        var addDashboardSummary = false

        when (packet.pid) {
            TransmitterConstants.UtcompPid.FIRMWARE -> {
                snapshot.firmware = "${b.u8(0)}.${b.u8(1)}.${b.u8(2)}"
                snapshot.utcompPro = b.u8(10) == 1
                if (debugLog != null) {
                    debugLog("firmware=${snapshot.firmware} utcompPro=${snapshot.utcompPro}")
                }
            }

            TransmitterConstants.UtcompPid.TEMPERATURES_DATA -> {
                snapshot.temperatureDsA = b.f32le(0)
                snapshot.temperatureDsB = b.f32le(4)
                snapshot.temperatureDsC = b.f32le(8)
                snapshot.temperatureDsD = b.f32le(12)
                snapshot.temperatureNtc1 = b.f32le(16)
                snapshot.temperatureNtc2 = b.f32le(20)
                snapshot.temperatureOutside = b.f32le(24)
                snapshot.temperatureInside = b.f32le(28)
                snapshot.temperatureEngine = b.f32le(32)
                snapshot.temperatureOil = b.f32le(36)
                snapshot.temperatureUser1 = b.f32le(40)
                snapshot.temperatureUser2 = b.f32le(44)
                addDashboardSummary = true
                if (debugLog != null) {
                    debugLog(
                        "temperatures dsA=${snapshot.temperatureDsA.pretty()} " +
                            "dsB=${snapshot.temperatureDsB.pretty()} " +
                            "outside=${snapshot.temperatureOutside.pretty()} " +
                            "inside=${snapshot.temperatureInside.pretty()} " +
                            "engine=${snapshot.temperatureEngine.pretty()} " +
                            "oil=${snapshot.temperatureOil.pretty()}",
                    )
                }
            }

            TransmitterConstants.UtcompPid.VSS_DATA -> {
                snapshot.vssSpeed1s = b.u16le(0)
                snapshot.vssSpeed200ms = b.u16le(2)
                snapshot.distance = b.f32le(4)
                snapshot.distanceDt = b.f32le(8)
                snapshot.vssImp1s = b.u16le(32)
                snapshot.vssImp200ms = b.u16le(34)
                snapshot.vssImp180s = b.u32le(36)
                snapshot.vssImpPbTotal = b.u32le(40)
                snapshot.vssImpLpgTotal = b.u32le(44)
                addDashboardSummary = true
                if (debugLog != null) {
                    debugLog(
                        "vss speed1s=${snapshot.vssSpeed1s} " +
                            "speed200ms=${snapshot.vssSpeed200ms} " +
                            "distance=${snapshot.distance.pretty()} " +
                            "distanceDt=${snapshot.distanceDt.pretty()} " +
                            "imp1s=${snapshot.vssImp1s}",
                    )
                }
            }

            TransmitterConstants.UtcompPid.CONSUMPTION_DATA -> {
                snapshot.consumptionAvg = b.f32le(0)
                snapshot.consumptionCur = b.f32le(4)
                snapshot.fuelLeftPb = b.f32le(8)
                snapshot.fuelLeftLpg = b.f32le(12)
                snapshot.fuelTypeCurrent = b.u8(16)
                snapshot.tripCplPb = b.f32le(17)
                snapshot.tripCplLpg = b.f32le(21)
                snapshot.tripCpkm = b.f32le(25)
                snapshot.injectionTime1s = b.f32le(32)
                snapshot.injectionTime180s = b.f32le(36)
                snapshot.injectionTimePbTotal = b.f32le(40)
                snapshot.injectionTimeLpgTotal = b.f32le(44)
                if (debugLog != null) {
                    debugLog(
                        "cons avg=${snapshot.consumptionAvg.pretty()} " +
                            "cur=${snapshot.consumptionCur.pretty()} " +
                            "fuelPb=${snapshot.fuelLeftPb.pretty()} " +
                            "fuelLpg=${snapshot.fuelLeftLpg.pretty()} " +
                            "fuelType=${snapshot.fuelTypeCurrent} " +
                            "tripCplPb=${snapshot.tripCplPb.pretty()} " +
                            "tripCplLpg=${snapshot.tripCplLpg.pretty()}",
                    )
                }
            }

            TransmitterConstants.UtcompPid.GENERAL_DATA1 -> {
                snapshot.adcInValCh0 = b.f32le(4)
                snapshot.adcInValCh1 = b.f32le(8)
                snapshot.adcInValCh2 = b.f32le(12)
                snapshot.adcInValCh3 = b.f32le(16)
                snapshot.adcInValCh4 = b.f32le(20)
                snapshot.adcInValCh5 = b.f32le(24)
                snapshot.adcInValCh6 = b.f32le(28)
                snapshot.adcInValCh7 = b.f32le(32)
                snapshot.vref = b.u16le(36)
                snapshot.loggerFlashAddrCur = b.u32le(38)
                snapshot.digOutUserState = b.u8(42)
                snapshot.gearNo = b.u8(43)
                addDashboardSummary = true
                if (debugLog != null) {
                    debugLog(
                        "general1 adc=[${snapshot.adcInValCh0.pretty()}, " +
                            "${snapshot.adcInValCh1.pretty()}, ${snapshot.adcInValCh2.pretty()}, " +
                            "${snapshot.adcInValCh3.pretty()}, ${snapshot.adcInValCh4.pretty()}, " +
                            "${snapshot.adcInValCh5.pretty()}, ${snapshot.adcInValCh6.pretty()}, " +
                            "${snapshot.adcInValCh7.pretty()}] vref=${snapshot.vref} " +
                            "gear=${snapshot.gearNo}",
                    )
                }
            }

            TransmitterConstants.UtcompPid.GENERAL_DATA2 -> {
                snapshot.temperatureNtc3 = b.f32le(0)
                snapshot.bar1 = b.f32le(4)
                snapshot.bar2 = b.f32le(8)
                snapshot.bar3 = b.f32le(12)
                snapshot.afr1 = b.f32le(16)
                snapshot.afr2 = b.f32le(20)
                snapshot.egt1 = b.u16le(24)
                snapshot.egt2 = b.u16le(26)
                snapshot.egt3 = b.u16le(28)
                snapshot.egt4 = b.u16le(30)
                snapshot.egt5 = b.u16le(32)
                snapshot.egt6 = b.u16le(34)
                snapshot.rpm = b.u16le(36)
                addDashboardSummary = true
                if (debugLog != null) {
                    debugLog(
                        "general2 ntc3=${snapshot.temperatureNtc3.pretty()} " +
                            "bar=[${snapshot.bar1.pretty()}, ${snapshot.bar2.pretty()}, " +
                            "${snapshot.bar3.pretty()}] afr=[${snapshot.afr1.pretty()}, " +
                            "${snapshot.afr2.pretty()}] egt=[${snapshot.egt1}, ${snapshot.egt2}, " +
                            "${snapshot.egt3}, ${snapshot.egt4}, ${snapshot.egt5}, " +
                            "${snapshot.egt6}] rpm=${snapshot.rpm}",
                    )
                }
            }

            TransmitterConstants.UtcompPid.TRIP_DATA -> {
                snapshot.tripTravelTime1s = b.u32le(0)
                snapshot.ignitionTime1s = b.u32le(4)
                snapshot.tripCons = b.f32le(8)
                snapshot.tripDist = b.f32le(12)
                snapshot.tripQty = b.f32le(16)
                snapshot.tripCost = b.f32le(20)
                snapshot.tripVavg = b.f32le(24)
                snapshot.vmax = b.u8(28)
                if (debugLog != null) {
                    debugLog(
                        "trip travel=${snapshot.tripTravelTime1s}s " +
                            "ignition=${snapshot.ignitionTime1s}s " +
                            "cons=${snapshot.tripCons.pretty()} " +
                            "dist=${snapshot.tripDist.pretty()} " +
                            "qty=${snapshot.tripQty.pretty()} " +
                            "cost=${snapshot.tripCost.pretty()} " +
                            "vavg=${snapshot.tripVavg.pretty()} vmax=${snapshot.vmax}",
                    )
                }
            }

            TransmitterConstants.UtcompPid.TEMPERATURES_SETTINGS -> {
                UtcompDeviceConfig.updateTemperatureSettings(b)
                if (debugLog != null) {
                    debugLog("temperatures_settings ${UtcompDeviceConfig.debugSummary()}")
                }
            }

            TransmitterConstants.UtcompPid.GPIO_SETTINGS -> {
                UtcompDeviceConfig.updateGpioSettings(b)
                if (debugLog != null) {
                    debugLog("gpio_settings ${UtcompDeviceConfig.debugSummary()}")
                }
            }

            TransmitterConstants.UtcompPid.ANALOG_OSC_SETTINGS1 -> {
                UtcompDeviceConfig.updateAnalogOscSettings1(b)
                if (debugLog != null) {
                    debugLog("analog_osc_settings1 ${UtcompDeviceConfig.debugSummary()}")
                }
            }

            TransmitterConstants.UtcompPid.ANALOG_OSC_SETTINGS2 -> {
                UtcompDeviceConfig.updateAnalogOscSettings2(b)
                if (debugLog != null) {
                    debugLog("analog_osc_settings2 ${UtcompDeviceConfig.debugSummary()}")
                }
            }

            TransmitterConstants.UtcompPid.GENERAL_SETTINGS1 -> {
                if (debugLog != null) {
                    debugLog(
                        "settings1 buzzer=${b.u8(0)} sleep=${b.u8(1)} " +
                            "unitsTacho=${b.u8(3)} unitsTemp=${b.u8(4)} " +
                            "rpmMode=${b.u8(6)} brightness=${b.u8(11)} " +
                            "defaultScreen=${b.u8(13)} lang=${b.u8(16)} " +
                            "pressureUnit=${b.u8(31)} distanceUnit=${b.u8(32)} " +
                            "consumptionUnit=${b.u8(33)} " +
                            "orientation=${b.u8(34)}",
                    )
                }
            }

            else -> {
                if (debugLog != null) {
                    debugLog("pid=0x%04X not decoded yet".format(packet.pid))
                }
                return false
            }
        }

        if (addDashboardSummary && debugLog != null) {
            debugLog(VehicleDashboard.summary(snapshot))
        }
        return true
    }
}
