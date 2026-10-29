package io.sonora.player.ui

/**
 * Size of the translucent tab bar, in dp: the bar's width and the width every tab's cell gets (equal cells, so any tab count
 * fits). Where each cell lands is left to layout: the selection pill and the scrub hit-testing use the cells as
 * measured ([tabIndexAt]), so right-to-left bars and pixel rounding need no second model here.
 */
data class TabBarGeometry(
    val barWidth: Float,
    val cellWidth: Float,
    /** Cells are too narrow for the regular label size, so labels use a smaller one. */
    val compactLabels: Boolean,
) {
    companion object {
        /** A cell's width when there is room: three tabs keep the Apple Music look. */
        const val PREFERRED_CELL = 88f

        /** Space between cells. */
        const val GAP = 4f

        /** Space between the bar's edge and the cells. */
        const val INSET = 4f

        const val MAX_WIDTH = 420f

        /** Below this cell width the labels shrink a step. */
        const val COMPACT_CELL = 72f
    }
}

/**
 * The bar for [tabCount] tabs in [availableWidth]: cells of [preferredCell] when they fit, otherwise the space shared
 * out. The phone's bar keeps the default; the tablet's top pill asks for a narrower cell.
 */
fun tabBarGeometry(tabCount: Int, availableWidth: Float, preferredCell: Float = TabBarGeometry.PREFERRED_CELL): TabBarGeometry {
    if (tabCount <= 0) return TabBarGeometry(0f, 0f, compactLabels = false)
    val chrome = 2 * TabBarGeometry.INSET + (tabCount - 1) * TabBarGeometry.GAP
    val preferred = tabCount * preferredCell + chrome
    val barWidth = minOf(preferred, availableWidth, TabBarGeometry.MAX_WIDTH).coerceAtLeast(chrome)
    val cell = (barWidth - chrome) / tabCount
    return TabBarGeometry(barWidth, cell, compactLabels = cell < TabBarGeometry.COMPACT_CELL)
}
