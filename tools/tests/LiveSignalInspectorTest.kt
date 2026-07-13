package de.krazey.utcomp.dashboard.logging

import de.krazey.utcomp.dashboard.utcomp.UtcompDataSnapshot
import kotlin.math.abs

private fun assertNear(expected: Float, actual: Float, tolerance: Float = 0.0001f) {
    check(abs(expected - actual) <= tolerance) {
        "expected=$expected actual=$actual"
    }
}

fun main() {
    check(LiveSignalCatalog.find("boost").read(UtcompDataSnapshot(bar1 = 1.25f)) == 1.25f)
    check(
        LiveSignalCatalog.find("boost").sourcePid ==
            de.krazey.utcomp.dashboard.protocol.TransmitterConstants.UtcompPid.GENERAL_DATA2,
    )
    check(
        LiveSignalCatalog.find("adc4").sourcePid ==
            de.krazey.utcomp.dashboard.protocol.TransmitterConstants.UtcompPid.GENERAL_DATA1,
    )
    check(LiveSignalCatalog.byGroup(LiveSignalGroup.ANALOG).size == 9)
    check(LiveSignalCatalog.all.map { it.id }.distinct().size == LiveSignalCatalog.all.size)

    val buffer = LiveSignalBuffer(capacity = 5)
    buffer.setSmoothingAlpha(0.5f)
    buffer.add(0L, 0f)
    buffer.add(100L, 10f)
    buffer.add(200L, 10f)
    assertNear(0f, buffer.smoothedAt(0))
    assertNear(5f, buffer.smoothedAt(1))
    assertNear(7.5f, buffer.smoothedAt(2))

    buffer.setSmoothingAlpha(1f)
    assertNear(10f, buffer.smoothedAt(1))
    assertNear(10f, buffer.smoothedAt(2))

    buffer.clear()
    repeat(7) { index -> buffer.add(index * 100L, index.toFloat()) }
    check(buffer.size == 5)
    assertNear(2f, buffer.rawAt(0))
    assertNear(6f, buffer.rawAt(4))

    val stats = buffer.stats(windowMs = 1_000L)
    check(stats.count == 5)
    assertNear(2f, stats.rawMin)
    assertNear(6f, stats.rawMax)
    assertNear(4f, stats.rawAverage)
    assertNear(4f, stats.peakToPeak)
    assertNear(10f, stats.sampleRateHz)

    val shortStats = buffer.stats(windowMs = 220L)
    check(shortStats.count == 3)
    assertNear(4f, shortStats.rawMin)
    assertNear(6f, shortStats.rawMax)

    println("LiveSignalInspectorTest: OK")
}
