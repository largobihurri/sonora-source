package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.QueueItem
import io.sonora.core.domain.model.Track
import org.junit.Test

class ReorderTest {

    /** Five 60 px rows stacked from the top. */
    private val slots = List(5) { ListSlot(index = it, offset = it * 60, size = 60) }

    @Test
    fun `a row stays put until its middle crosses into a neighbour`() {
        assertThat(reorderTarget(slots[1], dragged = 29f, slots)).isNull()
        assertThat(reorderTarget(slots[1], dragged = -29f, slots)).isNull()
    }

    @Test
    fun `dragging past half a row swaps with the neighbour in that direction`() {
        assertThat(reorderTarget(slots[1], dragged = 31f, slots)).isEqualTo(2)
        assertThat(reorderTarget(slots[1], dragged = -31f, slots)).isEqualTo(0)
    }

    @Test
    fun `a fast drag over several rows targets the row under its middle`() {
        assertThat(reorderTarget(slots[0], dragged = 200f, slots)).isEqualTo(3)
    }

    @Test
    fun `a row held past the visible rows targets the row at that end`() {
        assertThat(reorderTarget(slots[2], dragged = 500f, slots)).isEqualTo(4)
        assertThat(reorderTarget(slots[2], dragged = -500f, slots)).isEqualTo(0)
        assertThat(reorderTarget(slots[4], dragged = 120f, slots)).isNull() // already the last row
    }

    /** Ten 100 px rows in a 400 px viewport, [scrolled] px down the list, laid out in [laidOut] order. */
    private val items = (1L..10L).map { QueueItem(it, track(it)) }
    private var laidOut = items
    private var scrolled = 500
    private var keptScrollPositions = 0
    private val reorder = UpNextReorder(
        visibleRows = {
            laidOut.mapIndexed { index, item -> VisibleRow(item.id, index, offset = index * 100 - scrolled, size = 100) }
                .filter { it.offset + it.size > 0 && it.offset < 400 }
        },
        keepScrollPosition = { keptScrollPositions++ },
    )

    /** Lays the list out again, as a frame would. */
    private fun layOut() {
        laidOut = reorder.order
    }

    @Test
    fun `while the list scrolls under a held row, the row keeps swapping with the rows it passes`() {
        reorder.start(7L, items) // index 6, drawn at 100 px
        reorder.drag(-100f)
        layOut()
        assertThat(reorder.order.map { it.id }.indexOf(7L)).isEqualTo(5)

        // Held still at the top edge while the list scrolls 60 px back: the row above now sits under it.
        scrolled -= 60
        reorder.scrolled()
        layOut()

        assertThat(reorder.order.map { it.id }.indexOf(7L)).isEqualTo(4)
        // Still under the finger: 0 px in the viewport, though its slot is at 400 - 440 = -40 px.
        assertThat(reorder.translationOf(7L)).isEqualTo(40f)
        assertThat(reorder.end(items.map { it.id })).isEqualTo(6 to 4)
    }

    @Test
    fun `every swap keeps the list where it is, so it never follows the first row it moved`() {
        reorder.start(7L, items)
        reorder.drag(-100f)
        layOut()
        scrolled -= 60
        reorder.scrolled()

        assertThat(keptScrollPositions).isEqualTo(2)
    }

    @Test
    fun `the held row's middle is known only once its last swap is laid out`() {
        assertThat(reorder.draggedMiddle()).isNull()
        reorder.start(7L, items)
        assertThat(reorder.draggedMiddle()).isEqualTo(150f)

        reorder.drag(-100f)
        assertThat(reorder.draggedMiddle()).isNull()
        layOut()
        assertThat(reorder.draggedMiddle()).isEqualTo(50f)

        reorder.cancel()
        assertThat(reorder.draggedMiddle()).isNull()
    }

    private fun track(id: Long) = Track(
        id = id, uri = "content://doc/$id", title = "T$id", artist = "A", album = "B", albumArtist = null,
        durationMs = 1_000, trackNumber = null, discNumber = null, year = null, albumId = 1, artworkUri = null,
        mimeType = "audio/flac", sizeBytes = 1,
    )
}
