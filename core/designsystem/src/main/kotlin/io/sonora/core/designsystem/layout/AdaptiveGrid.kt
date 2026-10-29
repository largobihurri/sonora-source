package io.sonora.core.designsystem.layout

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * Pure decisions for the album grids (Library Home's "Recently Added" and Albums): phones keep two columns; a large
 * window ([WindowClass.isLarge]) fits as many tiles of at least [MIN_TILE_DP] as its width allows.
 *
 * Every tile stays the same width: the edge margins belong to the first and last columns of a row (see [cellWidths]
 * and [atStartEdge]), not to the tiles, so a middle column is never wider than an edge one.
 */
object AdaptiveGrid {
    /** Columns of a phone, whatever its orientation. */
    const val PHONE_COLUMNS = 2

    /** The narrowest a tile gets on a large window: about 5-6 columns on a tablet held sideways, 3-4 upright. */
    const val MIN_TILE_DP = 170

    /** The gap between tiles, in both directions. */
    const val SPACING_DP = 16

    /** The margin at both ends of a row. */
    const val MARGIN_DP = 20

    /** The minimum tile width of a large window, `null` for a phone (which keeps [PHONE_COLUMNS] fixed columns). */
    fun minTileDpFor(windowClass: WindowClass): Int? = MIN_TILE_DP.takeIf { windowClass.isLarge }

    /** How many tiles of at least [minTileDp] fit [availableDp], counting the [marginDp] at both ends and the gaps; at least one. */
    fun columns(
        availableDp: Float,
        minTileDp: Int = MIN_TILE_DP,
        spacingDp: Int = SPACING_DP,
        marginDp: Int = MARGIN_DP,
    ): Int = ((availableDp - 2 * marginDp + spacingDp) / (minTileDp + spacingDp)).toInt().coerceAtLeast(1)

    /** The columns of a grid [availableDp] wide in [windowClass]: [PHONE_COLUMNS] on a phone, else as many as fit. */
    fun columnsFor(windowClass: WindowClass, availableDp: Float): Int =
        if (windowClass.isLarge) columns(availableDp) else PHONE_COLUMNS

    /**
     * The widths of [columns] cells filling [availablePx] with [spacingPx] between them: equal tiles, plus [marginPx]
     * on the first cell and on the last (a single cell takes both). The cells and the gaps add up to [availablePx]
     * exactly; a pixel or two left over go to the first tiles.
     */
    fun cellWidths(availablePx: Int, spacingPx: Int, marginPx: Int, columns: Int): List<Int> {
        val count = columns.coerceAtLeast(1)
        val tiles = (availablePx - (count - 1) * spacingPx - 2 * marginPx).coerceAtLeast(0)
        val base = tiles / count
        val extra = tiles % count
        return List(count) { index ->
            val edge = (if (index == 0) marginPx else 0) + (if (index == count - 1) marginPx else 0)
            base + (if (index < extra) 1 else 0) + edge
        }
    }

    /** Whether the tile at [index] opens a row of [columns]: it carries the start margin. */
    fun atStartEdge(index: Int, columns: Int): Boolean = index % columns.coerceAtLeast(1) == 0

    /** Whether the tile at [index] closes a row of [columns]: it carries the end margin. */
    fun atEndEdge(index: Int, columns: Int): Boolean = index % columns.coerceAtLeast(1) == columns.coerceAtLeast(1) - 1
}

/**
 * [columns] equal tiles with the [marginDp] at both ends of a row inside the first and last cells, for a grid whose
 * spanning items (headers) draw their own margins: give each tile `padding(start = margin)` at [AdaptiveGrid.atStartEdge]
 * and `padding(end = margin)` at [AdaptiveGrid.atEndEdge].
 */
@Immutable
class EdgeMarginCells(private val columns: Int, private val marginDp: Int = AdaptiveGrid.MARGIN_DP) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> =
        AdaptiveGrid.cellWidths(availableSize, spacing, marginDp.dp.roundToPx(), columns)

    override fun equals(other: Any?): Boolean = other is EdgeMarginCells && other.columns == columns && other.marginDp == marginDp

    override fun hashCode(): Int = 31 * columns + marginDp
}
