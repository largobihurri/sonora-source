package io.sonora.feature.library.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // advanceTimeBy, runCurrent
class DragReorderTest {

    // Four 100 px rows; row 1 is dragged.
    private val slots = (0 until 4).map { RowSlot(index = it, offset = it * 100, size = 100) }
    private val dragged = slots[1]

    @Test
    fun `a row over itself has no target`() {
        assertThat(dragTarget(dragged, dragged = 40f, slots)).isNull()
        assertThat(dragTarget(dragged, dragged = -40f, slots)).isNull()
    }

    @Test
    fun `a row whose middle crosses into a neighbour targets it`() {
        assertThat(dragTarget(dragged, dragged = 60f, slots)).isEqualTo(2)
        assertThat(dragTarget(dragged, dragged = -60f, slots)).isEqualTo(0)
        assertThat(dragTarget(dragged, dragged = 160f, slots)).isEqualTo(3)
    }

    @Test
    fun `a row held past the visible rows targets the row at that end`() {
        assertThat(dragTarget(dragged, dragged = 400f, slots)).isEqualTo(3)
        assertThat(dragTarget(dragged, dragged = -400f, slots)).isEqualTo(0)
    }

    @Test
    fun `moving one key returns the reordered keys`() {
        assertThat(moveKey(listOf(1L, 2L, 3L), key = 3L, to = 0)).containsExactly(3L, 1L, 2L).inOrder()
        assertThat(moveKey(listOf(1L, 2L, 3L), key = 9L, to = 0)).containsExactly(1L, 2L, 3L).inOrder()
    }

    /**
     * A list of 100 px rows under one 100 px header row, laid out in [laidOut] order (what the screen last drew),
     * [scrolled] px down a 600 px viewport.
     */
    private var laidOut = listOf(1L, 2L, 3L, 4L)
    private var scrolled = 0
    private var keptScrollPositions = 0
    private val reorder = DragReorder(
        visibleRows = {
            val header = VisibleRow("header", index = 0, offset = -scrolled, size = 100)
            (listOf(header) + laidOut.mapIndexed { position, key -> VisibleRow(key, position + 1, 100 + position * 100 - scrolled, 100) })
                .filter { it.offset + it.size > 0 && it.offset < 600 }
        },
        firstRowIndex = 1,
        keepScrollPosition = { keptScrollPositions++ },
    )

    /** Drags by [dy] px and lays the list out again, as a frame would. */
    private fun dragAndLayOut(dy: Float) {
        reorder.drag(dy)
        laidOut = reorder.shownOrder(laidOut)
    }

    @Test
    fun `a dragged row follows the finger and swaps past the neighbours it crosses`() {
        val stored = listOf(1L, 2L, 3L, 4L)
        reorder.start(2L, stored)

        dragAndLayOut(60f)
        assertThat(reorder.shownOrder(stored)).containsExactly(1L, 3L, 2L, 4L).inOrder()
        // Started at 200 px, moved 60 px, now laid out at 300 px: drawn 40 px above its slot.
        assertThat(reorder.translationOf(2L)).isEqualTo(-40f)
        assertThat(reorder.translationOf(3L)).isEqualTo(0f)
    }

    @Test
    fun `a row held at the edge while the list scrolls swaps with the rows it passes, and every swap keeps the scroll`() {
        val stored = listOf(1L, 2L, 3L, 4L)
        scrolled = 200 // rows 2, 3, 4 are visible, row 1 just above
        reorder.start(4L, stored) // drawn at 200 px, the viewport's bottom row

        // Dragged over row 3, then held there while the list scrolls back a row: it passes row 2 as well.
        reorder.drag(-100f)
        laidOut = reorder.shownOrder(laidOut)
        assertThat(reorder.shownOrder(stored)).containsExactly(1L, 2L, 4L, 3L).inOrder()
        scrolled = 100
        reorder.scrolled()
        laidOut = reorder.shownOrder(laidOut)

        assertThat(reorder.shownOrder(stored)).containsExactly(1L, 4L, 2L, 3L).inOrder()
        assertThat(reorder.translationOf(4L)).isEqualTo(0f)
        assertThat(keptScrollPositions).isEqualTo(2)
        assertThat(reorder.end(stored)).isEqualTo(1)
    }

    @Test
    fun `the held row's middle is known only once its last swap is laid out`() {
        val stored = listOf(1L, 2L, 3L, 4L)
        assertThat(reorder.draggedMiddle()).isNull()
        reorder.start(2L, stored)
        assertThat(reorder.draggedMiddle()).isEqualTo(250f)

        reorder.drag(60f)
        assertThat(reorder.draggedMiddle()).isNull()
        laidOut = reorder.shownOrder(laidOut)
        assertThat(reorder.draggedMiddle()).isEqualTo(310f)
    }

    @Test
    fun `a dropped row stays where it was dropped until the stored order catches up`() {
        val stored = listOf(1L, 2L, 3L, 4L)
        reorder.start(2L, stored)
        dragAndLayOut(60f)
        dragAndLayOut(100f)

        assertThat(reorder.end(stored)).isEqualTo(3)

        // The move is being saved: the stored rows are still the old ones, yet the row stays where it was dropped.
        assertThat(reorder.draggedKey).isNull()
        assertThat(reorder.shownOrder(stored)).containsExactly(1L, 3L, 4L, 2L).inOrder()
        // Saved: the list follows the stored order from now on, even if it later changes back.
        val saved = listOf(1L, 3L, 4L, 2L)
        assertThat(reorder.shownOrder(saved)).containsExactly(1L, 3L, 4L, 2L).inOrder()
        reorder.settle(saved) // the screen's effect, once the saved order is shown
        assertThat(reorder.awaitingSave).isFalse()
        assertThat(reorder.shownOrder(stored)).containsExactly(1L, 2L, 3L, 4L).inOrder()
    }

    @Test
    fun `a drop whose save never lands goes back to the stored order`() = runTest {
        val stored = listOf(1L, 2L, 3L, 4L)
        reorder.start(2L, stored)
        dragAndLayOut(60f)
        assertThat(reorder.end(stored)).isEqualTo(2)
        assertThat(reorder.awaitingSave).isTrue()

        launch { reorder.awaitSave(stored) }
        advanceTimeBy(DragReorder.SAVE_WAIT_MS - 1)
        assertThat(reorder.shownOrder(stored)).containsExactly(1L, 3L, 2L, 4L).inOrder()

        advanceTimeBy(2)
        assertThat(reorder.awaitingSave).isFalse()
        assertThat(reorder.shownOrder(stored)).isEqualTo(stored)
    }

    @Test
    fun `a save that lands in time ends the wait at once`() = runTest {
        val stored = listOf(1L, 2L, 3L, 4L)
        reorder.start(2L, stored)
        dragAndLayOut(60f)
        reorder.end(stored)

        val waiting = launch { reorder.awaitSave(listOf(1L, 3L, 2L, 4L)) }
        runCurrent()

        assertThat(waiting.isCompleted).isTrue()
        assertThat(reorder.awaitingSave).isFalse()
    }

    @Test
    fun `showing the order changes nothing`() {
        val stored = listOf(1L, 2L, 3L, 4L)
        reorder.start(2L, stored)
        dragAndLayOut(60f)
        reorder.end(stored)

        // Read during composition: a frame with other keys must not forget the drop by itself.
        reorder.shownOrder(listOf(9L))
        assertThat(reorder.awaitingSave).isTrue()
        assertThat(reorder.shownOrder(stored)).containsExactly(1L, 3L, 2L, 4L).inOrder()
    }

    @Test
    fun `a drop that moved nothing, or over rows that changed meanwhile, saves nothing and shows the stored rows`() {
        val stored = listOf(1L, 2L, 3L, 4L)
        reorder.start(2L, stored)
        dragAndLayOut(20f)
        assertThat(reorder.end(stored)).isNull()
        assertThat(reorder.shownOrder(stored)).isEqualTo(stored)

        reorder.start(2L, stored)
        dragAndLayOut(60f)
        val changed = listOf(1L, 2L, 3L)
        assertThat(reorder.end(changed)).isNull()
        assertThat(reorder.shownOrder(changed)).isEqualTo(changed)
    }

    @Test
    fun `a cancelled drag shows the stored rows again`() {
        val stored = listOf(1L, 2L, 3L, 4L)
        reorder.start(2L, stored)
        dragAndLayOut(60f)

        reorder.cancel()

        assertThat(reorder.draggedKey).isNull()
        assertThat(reorder.shownOrder(stored)).isEqualTo(stored)
    }
}
