package de.krazey.utcomp.dashboard.dashboard

private fun box(
    sensor: DashboardSensor,
    row: Int,
    column: Int,
    rowSpan: Int = 1,
    columnSpan: Int = 1,
): DashboardBoxConfig = DashboardBoxConfig(
    sensor = sensor,
    row = row,
    column = column,
    rowSpan = rowSpan,
    columnSpan = columnSpan,
)

fun main() {
    val overlapping = DashboardPageConfig(
        id = "overlap",
        title = "Overlap",
        rows = 9,
        columns = 0,
        boxes = listOf(
            box(DashboardSensor.BOOST, 0, 0, rowSpan = 8, columnSpan = 3),
            box(DashboardSensor.AFR, 0, 0),
        ),
    ).normalized()
    check(overlapping.rows == 4)
    check(overlapping.columns == 1)
    check(overlapping.boxes.size == 1)
    check(overlapping.boxes.single().rowSpan == 4)
    check(overlapping.boxes.single().columnSpan == 1)

    val twoByTwo = DashboardPageConfig(
        id = "merge",
        title = "Merge",
        rows = 2,
        columns = 2,
        boxes = listOf(
            box(DashboardSensor.BOOST, 0, 0),
            box(DashboardSensor.AFR, 0, 1),
            box(DashboardSensor.OIL_TEMP, 1, 0),
            box(DashboardSensor.OIL_PRESSURE, 1, 1),
        ),
    )
    check(twoByTwo.mergeCandidates(0) == listOf(1, 2))
    check(twoByTwo.mergeTargetCells(0) == setOf(1, 2))

    val merged = twoByTwo.mergeBoxes(0, 1)
    check(merged.boxes.size == 3)
    check(merged.boxes[0].row == 0)
    check(merged.boxes[0].column == 0)
    check(merged.boxes[0].rowSpan == 1)
    check(merged.boxes[0].columnSpan == 2)
    check(merged.occupiedCells().size == 4)

    val split = merged.splitBox(0)
    check(split.boxes.size == 4)
    check(split.boxes.take(2).all { it.sensor == DashboardSensor.BOOST })
    check(split.boxes.take(2).map { it.column } == listOf(0, 1))
    check(split.occupiedCells().size == 4)

    val removed = split.removeBox(1)
    check(removed.boxes.size == 3)
    check(1 !in removed.occupiedCells())
    val restored = removed.addBoxAt(0, 1)
    check(restored.boxes.size == 4)
    check(1 in restored.occupiedCells())

    val mergeIntoEmpty = removed.mergeBoxIntoCell(0, 0, 1)
    check(mergeIntoEmpty.boxes.size == 3)
    check(mergeIntoEmpty.boxes[0].rowSpan == 1)
    check(mergeIntoEmpty.boxes[0].columnSpan == 2)
    check(mergeIntoEmpty.occupiedCells().size == 4)

    val mergeIntoOccupied = twoByTwo.mergeBoxIntoCell(0, 0, 1)
    check(mergeIntoOccupied == merged)

    val wideSource = DashboardPageConfig(
        id = "wide-source",
        title = "Wide source",
        rows = 2,
        columns = 2,
        boxes = listOf(
            DashboardBoxConfig(DashboardSensor.BOOST, 0, 0, columnSpan = 2),
            DashboardBoxConfig(DashboardSensor.AFR, 1, 0),
            DashboardBoxConfig(DashboardSensor.OIL_TEMP, 1, 1),
        ),
    )
    check(wideSource.mergeTargetCells(0) == setOf(2, 3))
    val expandedWide = wideSource.mergeBoxIntoCell(0, 1, 0)
    check(expandedWide.boxes.size == 1)
    check(expandedWide.boxes.single().rowSpan == 2)
    check(expandedWide.boxes.single().columnSpan == 2)

    val styled = twoByTwo.copy(
        boxes = twoByTwo.boxes.mapIndexed { index, current ->
            current.copy(
                valueScale = 1.0f + index * 0.1f,
                borderColor = 0x11000000 + index,
            )
        },
    )
    val sixteen = styled.rebuiltGrid(4, 4)
    check(sixteen.rows == 4)
    check(sixteen.columns == 4)
    check(sixteen.boxes.size == 16)
    check(sixteen.occupiedCells().size == 16)
    check(sixteen.boxes[0].sensor == DashboardSensor.BOOST)
    check(sixteen.boxes[0].valueScale == 1.0f)
    check(sixteen.boxes[0].borderColor == 0x11000000)
    check(sixteen.boxes.last().row == 3)
    check(sixteen.boxes.last().column == 3)

    val single = sixteen.rebuiltGrid(1, 1)
    check(single.boxes.size == 1)
    check(single.boxes.single().row == 0)
    check(single.boxes.single().column == 0)

    val customizedSimple = twoByTwo.copy(
        minMaxAlwaysVisible = true,
        showSourceLine = false,
        boxes = twoByTwo.boxes.map { current ->
            when (current.sensor) {
                DashboardSensor.BOOST -> current.copy(
                    valueScale = 1.35f,
                    minMaxScale = 1.6f,
                    minColor = 0x11223344,
                    showMinMax = false,
                )
                DashboardSensor.OIL_TEMP -> current.copy(
                    warningHigh = 118.0f,
                    criticalHigh = 127.0f,
                )
                else -> current
            }
        },
    )
    val migratedRalliart = DefaultDashboardPages.ralliart
        .withSensorSettingsFrom(customizedSimple)
    val migratedBoost = migratedRalliart.boxes.first {
        it.sensor == DashboardSensor.BOOST
    }
    val defaultBoost = DefaultDashboardPages.ralliart.boxes.first {
        it.sensor == DashboardSensor.BOOST
    }
    check(migratedRalliart.id == "ralliart")
    check(migratedRalliart.minMaxAlwaysVisible)
    check(!migratedRalliart.showSourceLine)
    check(migratedBoost.valueScale == 1.35f)
    check(migratedBoost.minMaxScale == 1.6f)
    check(migratedBoost.minColor == 0x11223344)
    check(!migratedBoost.showMinMax)
    check(migratedBoost.row == defaultBoost.row)
    check(migratedBoost.column == defaultBoost.column)

    val simpleAfterRalliartEdit = customizedSimple
    val editedRalliart = migratedRalliart.copy(
        minMaxAlwaysVisible = false,
        boxes = migratedRalliart.boxes.map { current ->
            if (current.sensor == DashboardSensor.BOOST) {
                current.copy(valueScale = 0.8f, showMinMax = true)
            } else {
                current
            }
        },
    )
    check(simpleAfterRalliartEdit.minMaxAlwaysVisible)
    check(!simpleAfterRalliartEdit.showSourceLine)
    check(simpleAfterRalliartEdit.boxes.first().valueScale == 1.35f)
    check(!simpleAfterRalliartEdit.boxes.first().showMinMax)
    check(!editedRalliart.minMaxAlwaysVisible)
    check(editedRalliart.boxes.first().valueScale == 0.8f)
    check(editedRalliart.boxes.first().showMinMax)

    val customizedHeader = DefaultDashboardPages.ralliart.copy(
        ralliartHeaderTextScale = 1.5f,
        ralliartHeaderShowOutside = false,
        ralliartHeaderShowInside = true,
        ralliartHeaderShowBattery = false,
        ralliartHeaderShowClock = true,
    ).normalized()
    check(customizedHeader.ralliartHeaderTextScale == 1.5f)
    check(!customizedHeader.ralliartHeaderShowOutside)
    check(customizedHeader.ralliartHeaderShowInside)
    check(!customizedHeader.ralliartHeaderShowBattery)
    check(customizedHeader.ralliartHeaderShowClock)

    val clampedHeader = customizedHeader.copy(
        ralliartHeaderTextScale = 8.0f,
    ).normalized()
    check(clampedHeader.ralliartHeaderTextScale == 2.0f)

    val migratedHeader = DefaultDashboardPages.ralliart.copy(
        ralliartHeaderTextScale = 1.3f,
        ralliartHeaderShowClock = false,
    ).withSensorSettingsFrom(customizedSimple)
    check(migratedHeader.ralliartHeaderTextScale == 1.3f)
    check(!migratedHeader.ralliartHeaderShowClock)

    println("Dashboard layout tests passed")
}
