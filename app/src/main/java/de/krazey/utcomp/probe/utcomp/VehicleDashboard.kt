package de.krazey.utcomp.probe.utcomp

/**
 * User-specific UTCOMP sensor mapping for Mathias' car.
 *
 * Values shown here intentionally prefer UTCOMP's already scaled channels:
 * - afr1/bar1/bar2 come from GENERAL_DATA2
 * - DS/NTC temperatures come from TEMPERATURES_DATA
 *
 * Raw ADC voltages stay useful for debugging, but they are not the primary
 * dashboard values once UTCOMP scaling is configured.
 */
object VehicleDashboard {
    fun summary(s: UtcompDataSnapshot): String {
        return buildString {
            append("vehicle ")
            append("AFR=${fmt(s.afr1)}; ")
            append("Boost=${fmt(s.bar1, " bar")}; ")
            append("Oil pressure=${fmt(s.bar2, " bar")}; ")
            append("Oil/NTC temp=${fmt(s.temperatureNtc1, " °C")}; ")
            append("Outside DS-A=${fmt(s.temperatureDsA, " °C")}; ")
            append("Inside DS-B=${fmt(s.temperatureDsB, " °C")}; ")
            append("RPM=${s.rpm}; ")
            append("Battery=${fmt(s.adcInValCh0, " V")}")
        }
    }

    private fun fmt(v: Float, suffix: String = ""): String =
        if (v.isNaN() || v.isInfinite()) "—" else v.pretty() + suffix
}
