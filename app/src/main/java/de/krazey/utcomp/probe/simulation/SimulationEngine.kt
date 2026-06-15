package de.krazey.utcomp.probe.simulation

import de.krazey.utcomp.probe.utcomp.UtcompDataSnapshot
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

object SimulationEngine {
    fun update(s: UtcompDataSnapshot, tick: Long) {
        val t = tick / 2.0f
        val phase = ((sin(t / 8.0f * PI).toFloat() + 1.0f) / 2.0f).coerceIn(0.0f, 1.0f)
        val pull = if ((tick / 18) % 4 == 1L) phase else 0.0f
        val idleWave = sin(t / 3.0f * PI).toFloat()

        s.firmware = "SIM"
        s.utcompPro = true

        s.rpm = if (pull > 0f) (1800 + pull * 4200).toInt() else (850 + idleWave * 35).toInt()
        s.bar1 = if (pull > 0f) -0.15f + pull * 1.25f else -0.55f + idleWave * 0.04f
        s.afr1 = if (pull > 0f) 12.0f + (1.0f - pull) * 0.8f else 14.6f + idleWave * 0.25f
        s.bar2 = if (s.rpm > 1200) 2.4f + pull * 2.8f else 1.4f + idleWave * 0.12f

        s.temperatureNtc1 = 72.0f + pull * 14.0f + sin(t / 20.0f * PI).toFloat() * 2.0f
        s.temperatureDsA = 23.0f + sin(t / 25.0f * PI).toFloat() * 1.2f
        s.temperatureDsB = 28.0f + sin(t / 30.0f * PI).toFloat() * 0.8f
        s.temperatureEngine = 86.0f + pull * 3.0f
        s.temperatureOil = s.temperatureNtc1

        s.adcInValCh0 = if (s.rpm > 0) 13.9f + idleWave * 0.15f else 12.1f
        s.vssSpeed1s = if (pull > 0f) (60 + pull * 90).toInt() else 0
        s.vssSpeed200ms = s.vssSpeed1s
        s.fuelLeftPb = 59.0f - (tick % 2000) / 2000.0f
        s.consumptionAvg = 8.8f
        s.consumptionCur = if (pull > 0f) 18.0f + pull * 18.0f else 1.2f
        s.tripDist = max(0.0f, (tick / 10.0f))
        s.tripCost = s.tripDist * 0.85f
        s.tripQty = s.tripDist * 0.09f
        s.tripCons = 8.8f
        s.tripVavg = if (tick > 0) 42.0f else 0.0f
        s.vmax = max(s.vmax, s.vssSpeed1s)
    }
}
