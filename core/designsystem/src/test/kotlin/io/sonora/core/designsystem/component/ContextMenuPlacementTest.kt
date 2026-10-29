package io.sonora.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContextMenuPlacementTest {

    private val screen = PxRect(0f, 100f, 1000f, 2100f) // status bar above 100, navigation bar below 2100
    private val margin = 20f
    private val gap = 10f

    private fun place(anchor: PxRect, touchX: Float = anchor.centerX, menuHeight: Float = 600f) = placeContextMenu(
        anchor = anchor,
        touchX = touchX,
        cardWidth = 900f,
        cardHeight = 200f,
        menuWidth = 600f,
        menuHeight = menuHeight,
        area = screen,
        margin = margin,
        gap = gap,
    )

    @Test
    fun `with room below, the card lifts in place and the menu sits right under it`() {
        val row = PxRect(0f, 400f, 1000f, 520f)
        val placed = place(row, touchX = 500f)
        assertThat(placed.cardTop).isEqualTo(row.centerY - 100f)
        assertThat(placed.cardLeft).isEqualTo(50f)
        assertThat(placed.menuAbove).isFalse()
        assertThat(placed.menuTop).isEqualTo(placed.cardTop + 200f + gap)
    }

    @Test
    fun `the menu follows the finger and stays on screen`() {
        val row = PxRect(0f, 400f, 1000f, 520f)
        assertThat(place(row, touchX = 500f).menuLeft).isEqualTo(200f)
        assertThat(place(row, touchX = 50f).menuLeft).isEqualTo(margin)
        assertThat(place(row, touchX = 990f).menuLeft).isEqualTo(1000f - margin - 600f)
    }

    @Test
    fun `near the bottom the menu flips above the card`() {
        val row = PxRect(0f, 1900f, 1000f, 2020f)
        val placed = place(row)
        assertThat(placed.menuAbove).isTrue()
        assertThat(placed.cardTop).isEqualTo(row.centerY - 100f)
        assertThat(placed.menuTop).isEqualTo(placed.cardTop - gap - 600f)
    }

    @Test
    fun `with no room either way the card moves up so the menu fits below`() {
        val row = PxRect(0f, 900f, 1000f, 1020f)
        val placed = place(row, menuHeight = 1500f)
        assertThat(placed.menuAbove).isFalse()
        assertThat(placed.menuTop + 1500f).isEqualTo(screen.bottom - margin)
        assertThat(placed.cardTop).isEqualTo(placed.menuTop - gap - 200f)
        assertThat(placed.cardTop).isAtLeast(screen.top + margin)
    }

    @Test
    fun `a card pressed under the status bar comes down into the area`() {
        val row = PxRect(0f, 60f, 1000f, 140f)
        val placed = place(row)
        assertThat(placed.cardTop).isEqualTo(screen.top + margin)
        assertThat(placed.menuAbove).isFalse()
    }

    @Test
    fun `a narrow tile's card is clamped to the screen edges`() {
        val tile = PxRect(820f, 500f, 990f, 700f)
        val placed = place(tile)
        assertThat(placed.cardLeft).isEqualTo(1000f - margin - 900f)
    }

    @Test
    fun `the menu may be as tall as the screen less the card, the gap and the margins`() {
        assertThat(contextMenuMaxHeight(screen, cardHeight = 200f, margin = margin, gap = gap)).isEqualTo(2000f - 40f - 10f - 200f)
        assertThat(contextMenuMaxHeight(screen, cardHeight = 2500f, margin = margin, gap = gap)).isEqualTo(0f)
    }

    @Test
    fun `a menu cut to the room left starts inside the screen wherever the card is`() {
        val tallest = contextMenuMaxHeight(screen, 200f, margin, gap)
        for (centre in listOf(150f, 600f, 1100f, 1600f, 2050f)) {
            val row = PxRect(0f, centre - 60f, 1000f, centre + 60f)
            val placed = place(row, menuHeight = tallest)
            assertThat(placed.menuTop).isAtLeast(screen.top + margin)
            assertThat(placed.menuTop + tallest).isAtMost(screen.bottom - margin)
            assertThat(placed.cardTop).isAtLeast(screen.top + margin)
            assertThat(placed.cardTop + 200f).isAtMost(screen.bottom - margin)
        }
    }

    @Test
    fun `a menu above the card keeps its top row on screen`() {
        val row = PxRect(0f, 1900f, 1000f, 2020f)
        val placed = place(row, menuHeight = 1500f)
        assertThat(placed.menuAbove).isTrue()
        assertThat(placed.menuTop).isAtLeast(screen.top + margin)
        assertThat(placed.menuTop + 1500f + gap).isAtMost(placed.cardTop)
    }
}

