package io.sonora.core.designsystem.layout

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AdaptiveGridTest {

    private val phone = WindowClass.Phone

    @Test
    fun `a phone keeps two columns whatever its width`() {
        assertThat(AdaptiveGrid.columnsFor(phone, availableDp = 320f)).isEqualTo(2)
        // A phone held sideways is wider than a small tablet but is not large: still two columns.
        assertThat(AdaptiveGrid.columnsFor(WindowClass.of(704, 320), availableDp = 704f)).isEqualTo(2)
    }

    @Test
    fun `a phone has no minimum tile width`() {
        assertThat(AdaptiveGrid.minTileDpFor(phone)).isNull()
        assertThat(AdaptiveGrid.minTileDpFor(WindowClass.of(704, 320))).isNull()
        assertThat(AdaptiveGrid.minTileDpFor(WindowClass.of(1152, 720))).isEqualTo(AdaptiveGrid.MIN_TILE_DP)
    }

    @Test
    fun `the Pad 6 shows six columns in landscape and three in portrait with no sidebar`() {
        assertThat(AdaptiveGrid.columnsFor(WindowClass.of(1152, 720), availableDp = 1152f)).isEqualTo(6)
        assertThat(AdaptiveGrid.columnsFor(WindowClass.of(720, 1152), availableDp = 720f)).isEqualTo(3)
    }

    @Test
    fun `a sidebar beside the grid takes columns away`() {
        // 1152 dp window with a 320 dp sidebar leaves 832 dp.
        assertThat(AdaptiveGrid.columnsFor(WindowClass.of(1152, 720), availableDp = 832f)).isEqualTo(4)
    }

    @Test
    fun `a tile is never narrower than the minimum`() {
        for (available in 560..1400 step 7) {
            val columns = AdaptiveGrid.columns(available.toFloat())
            val tile = (available - 2 * AdaptiveGrid.MARGIN_DP - (columns - 1) * AdaptiveGrid.SPACING_DP) / columns.toFloat()
            if (columns > 1) assertThat(tile).isAtLeast(AdaptiveGrid.MIN_TILE_DP.toFloat())
        }
    }

    @Test
    fun `there is always at least one column`() {
        assertThat(AdaptiveGrid.columns(0f)).isEqualTo(1)
        assertThat(AdaptiveGrid.columns(100f)).isEqualTo(1)
    }

    @Test
    fun `the edge tiles take the margins and every tile has the same width`() {
        val cells = AdaptiveGrid.cellWidths(availablePx = 1000, spacingPx = 10, marginPx = 20, columns = 4)
        // 1000 - 3 gaps of 10 - 2 margins of 20 = 930 px for four tiles: 232 each, two left over.
        val tiles = listOf(cells[0] - 20, cells[1], cells[2], cells[3] - 20)
        assertThat(tiles.max() - tiles.min()).isAtMost(1)
        assertThat(cells.sum() + 3 * 10).isEqualTo(1000)
    }

    @Test
    fun `one column takes both margins`() {
        assertThat(AdaptiveGrid.cellWidths(availablePx = 500, spacingPx = 10, marginPx = 20, columns = 1)).containsExactly(500)
    }

    @Test
    fun `cells always fill the width exactly`() {
        for (columns in 1..8) {
            for (available in listOf(777, 1000, 1234, 2880)) {
                val cells = AdaptiveGrid.cellWidths(available, spacingPx = 16, marginPx = 40, columns = columns)
                assertThat(cells).hasSize(columns)
                assertThat(cells.sum() + (columns - 1) * 16).isEqualTo(available)
            }
        }
    }

    @Test
    fun `the first column of a row carries the start margin and the last one the end margin`() {
        // Two columns: the phone pattern, even tiles at the start edge and odd ones at the end edge.
        assertThat(AdaptiveGrid.atStartEdge(0, 2)).isTrue()
        assertThat(AdaptiveGrid.atEndEdge(0, 2)).isFalse()
        assertThat(AdaptiveGrid.atStartEdge(1, 2)).isFalse()
        assertThat(AdaptiveGrid.atEndEdge(1, 2)).isTrue()
        assertThat(AdaptiveGrid.atStartEdge(2, 2)).isTrue()
        // Five columns: only the ends of each row.
        assertThat((0..9).filter { AdaptiveGrid.atStartEdge(it, 5) }).containsExactly(0, 5)
        assertThat((0..9).filter { AdaptiveGrid.atEndEdge(it, 5) }).containsExactly(4, 9)
        // A single column is both edges.
        assertThat(AdaptiveGrid.atStartEdge(3, 1)).isTrue()
        assertThat(AdaptiveGrid.atEndEdge(3, 1)).isTrue()
    }
}
