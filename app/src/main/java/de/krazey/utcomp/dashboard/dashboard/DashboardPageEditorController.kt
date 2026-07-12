package de.krazey.utcomp.dashboard.dashboard

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText

internal class DashboardPageEditorController(
    private val context: Context,
    private val pages: () -> List<DashboardPageConfig>,
    private val currentPageIndex: () -> Int,
    private val onPagesChanged: (List<DashboardPageConfig>, Int) -> Unit,
) {
    private data class GridPreset(
        val title: String,
        val rows: Int,
        val columns: Int,
    )

    fun showCurrentPageGridEditor() {
        val pageList = pages().ifEmpty { DefaultDashboardPages.all }
        val selectedIndex = currentPageIndex().coerceIn(pageList.indices)
        showGridPicker(pageList, selectedIndex)
    }

    fun showPageManager() {
        val pageList = pages().ifEmpty { DefaultDashboardPages.all }
        val selectedIndex = currentPageIndex().coerceIn(pageList.indices)
        val selected = pageList[selectedIndex]

        showEditorMenu(
            context = context,
            title = "Dashboard pages",
            sections = listOf(
                EditorMenuSection(
                    "Select page",
                    pageList.mapIndexed { index, page ->
                        EditorMenuRow(
                            label = page.title,
                            value = buildString {
                                append(gridSummary(page.rows, page.columns))
                                append(" • ${boxCountSummary(page.boxes.size)}")
                                if (index == selectedIndex) append(" • current")
                            },
                        ) {
                            onPagesChanged(pageList, index)
                        }
                    },
                ),
                EditorMenuSection(
                    "Current page",
                    listOf(
                        EditorMenuRow("Rename", selected.title) {
                            showRenameEditor(pageList, selectedIndex)
                        },
                        EditorMenuRow(
                            "Change grid",
                            gridSummary(selected.rows, selected.columns),
                        ) {
                            showGridPicker(pageList, selectedIndex)
                        },
                        EditorMenuRow("Duplicate page") {
                            duplicatePage(pageList, selectedIndex)
                        },
                        EditorMenuRow(
                            "Move page left",
                            enabled = selectedIndex > 0,
                        ) {
                            movePage(pageList, selectedIndex, selectedIndex - 1)
                        },
                        EditorMenuRow(
                            "Move page right",
                            enabled = selectedIndex < pageList.lastIndex,
                        ) {
                            movePage(pageList, selectedIndex, selectedIndex + 1)
                        },
                        EditorMenuRow(
                            "Delete page",
                            "At least one page is always kept",
                            enabled = pageList.size > 1,
                        ) {
                            confirmDelete(pageList, selectedIndex)
                        },
                    ),
                ),
                EditorMenuSection(
                    "Add page",
                    GRID_PRESETS.map { preset ->
                        EditorMenuRow(
                            preset.title,
                            gridSummary(preset.rows, preset.columns),
                            enabled = pageList.size < MAX_PAGE_COUNT,
                        ) {
                            addPage(pageList, preset)
                        }
                    },
                ),
            ),
        )
    }

    private fun showRenameEditor(
        pageList: List<DashboardPageConfig>,
        selectedIndex: Int,
    ) {
        val selected = pageList[selectedIndex]
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            setText(selected.title)
            selectAll()
        }
        AlertDialog.Builder(context)
            .setTitle("Rename page")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val title = input.text.toString().trim().take(40)
                if (title.isNotEmpty()) {
                    onPagesChanged(
                        pageList.mapIndexed { index, page ->
                            if (index == selectedIndex) page.copy(title = title) else page
                        },
                        selectedIndex,
                    )
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGridPicker(
        pageList: List<DashboardPageConfig>,
        selectedIndex: Int,
    ) {
        val selected = pageList[selectedIndex]
        val labels = GRID_PRESETS.map { preset ->
            buildString {
                append(preset.title)
                append(" • ")
                append(gridSummary(preset.rows, preset.columns))
                if (preset.rows == selected.rows && preset.columns == selected.columns) {
                    append(" ✓")
                }
            }
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("Change page grid")
            .setMessage(
                "Changing the grid rebuilds one box per cell. Existing box styles " +
                    "and sensors are reused in reading order. Merge boxes afterwards " +
                    "for wider or taller values.",
            )
            .setItems(labels) { _, which ->
                val preset = GRID_PRESETS[which]
                val updated = selected.rebuiltGrid(preset.rows, preset.columns)
                onPagesChanged(
                    pageList.mapIndexed { index, page ->
                        if (index == selectedIndex) updated else page
                    },
                    selectedIndex,
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun duplicatePage(
        pageList: List<DashboardPageConfig>,
        selectedIndex: Int,
    ) {
        if (pageList.size >= MAX_PAGE_COUNT) return
        val selected = pageList[selectedIndex]
        val copy = selected.copy(
            id = newPageId(),
            title = uniqueTitle("${selected.title} copy", pageList),
        )
        val insertionIndex = selectedIndex + 1
        onPagesChanged(
            pageList.toMutableList().apply { add(insertionIndex, copy) },
            insertionIndex,
        )
    }

    private fun movePage(
        pageList: List<DashboardPageConfig>,
        fromIndex: Int,
        toIndex: Int,
    ) {
        if (fromIndex !in pageList.indices || toIndex !in pageList.indices) return
        val updated = pageList.toMutableList()
        val page = updated.removeAt(fromIndex)
        updated.add(toIndex, page)
        onPagesChanged(updated, toIndex)
    }

    private fun confirmDelete(
        pageList: List<DashboardPageConfig>,
        selectedIndex: Int,
    ) {
        if (pageList.size <= 1) return
        AlertDialog.Builder(context)
            .setTitle("Delete ${pageList[selectedIndex].title}?")
            .setMessage("This page and its box configuration will be removed.")
            .setPositiveButton("Delete") { _, _ ->
                val updated = pageList.filterIndexed { index, _ -> index != selectedIndex }
                onPagesChanged(updated, selectedIndex.coerceAtMost(updated.lastIndex))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addPage(
        pageList: List<DashboardPageConfig>,
        preset: GridPreset,
    ) {
        if (pageList.size >= MAX_PAGE_COUNT) return
        val pageNumber = pageList.size + 1
        val newPage = DashboardPageConfig(
            id = newPageId(),
            title = uniqueTitle("Page $pageNumber", pageList),
            rows = preset.rows,
            columns = preset.columns,
            boxes = emptyList(),
        ).rebuiltGrid(preset.rows, preset.columns)
        onPagesChanged(pageList + newPage, pageList.size)
    }


    private fun gridSummary(rows: Int, columns: Int): String =
        "$rows ${if (rows == 1) "row" else "rows"} × " +
            "$columns ${if (columns == 1) "column" else "columns"}"

    private fun boxCountSummary(count: Int): String =
        "$count ${if (count == 1) "box" else "boxes"}"

    private fun uniqueTitle(
        requested: String,
        pageList: List<DashboardPageConfig>,
    ): String {
        val used = pageList.mapTo(HashSet()) { it.title }
        if (requested !in used) return requested
        var suffix = 2
        while ("$requested $suffix" in used) suffix++
        return "$requested $suffix"
    }

    private fun newPageId(): String =
        "custom_${System.currentTimeMillis().toString(36)}"

    private companion object {
        const val MAX_PAGE_COUNT = 12

        val GRID_PRESETS = buildList {
            for (rows in 1..MAX_DASHBOARD_GRID_SIZE) {
                for (columns in 1..MAX_DASHBOARD_GRID_SIZE) {
                    val count = rows * columns
                    val title = when {
                        rows == 1 && columns == 1 -> "Single value"
                        rows == 1 -> "One row • $count values"
                        columns == 1 -> "One column • $count values"
                        else -> "$count values"
                    }
                    add(GridPreset(title, rows, columns))
                }
            }
        }
    }
}
