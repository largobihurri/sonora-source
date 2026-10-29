package io.sonora.feature.nowplaying

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.sonora.core.domain.model.QueueItem

/** A laid-out list row: its position in the list, top offset and height in px. */
internal data class ListSlot(val index: Int, val offset: Int, val size: Int)

/** A row the list has laid out: its lazy-list [key], list [index], top [offset] and [size] in px. */
internal data class VisibleRow(val key: Any, val index: Int, val offset: Int, val size: Int)

/**
 * The row a dragged row should swap with: the one under the dragged row's middle once moved by [dragged] px from
 * where [slot] started, or null while it is still over itself. A row held past the visible rows (at or beyond the
 * list's edge, where it scrolls) targets the row at that end.
 */
internal fun reorderTarget(slot: ListSlot, dragged: Float, slots: List<ListSlot>): Int? {
    if (slots.isEmpty()) return null
    val top = slots.minOf { it.offset }.toFloat()
    val bottom = slots.maxOf { it.offset + it.size } - 1f
    val middle = (slot.offset + dragged + slot.size / 2f).coerceIn(top, bottom.coerceAtLeast(top))
    return slots.firstOrNull { it.index != slot.index && middle >= it.offset && middle < it.offset + it.size }?.index
}

/**
 * Drag-to-reorder for the Up Next list (rows keyed by queue item id). While a row is dragged the list shows [order],
 * reordered locally as the row crosses its neighbours (also while the list auto-scrolls under it, see [scrolled]); the
 * queue itself only changes once, on drop. [keepScrollPosition] runs before each local move: it pins the list to the
 * index it shows, as otherwise a lazy list follows its first visible row's key and would jump with a moved first row.
 */
@Stable
internal class UpNextReorder(private val visibleRows: () -> List<VisibleRow>, private val keepScrollPosition: () -> Unit) {

    constructor(listState: LazyListState) : this(
        { listState.layoutInfo.visibleItemsInfo.map { VisibleRow(it.key, it.index, it.offset, it.size) } },
        { listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) },
    )

    var order by mutableStateOf(emptyList<QueueItem>())
        private set
    var draggedId by mutableStateOf<Long?>(null)
        private set
    private var delta by mutableFloatStateOf(0f)
    private var startOffset = 0
    private var startPosition = -1
    private var startIds = emptyList<Long>()

    fun start(id: Long, items: List<QueueItem>) {
        val row = visibleRows().firstOrNull { it.key == id } ?: return
        order = items
        startIds = items.map { it.id }
        startPosition = row.index
        startOffset = row.offset
        delta = 0f
        draggedId = id
    }

    fun drag(dy: Float) {
        if (draggedId == null) return
        delta += dy
        retarget()
    }

    /**
     * The list scrolled under the held row. The row stays under the finger (its offsets are the viewport's), so it may
     * now be over another row.
     */
    fun scrolled() = retarget()

    /** The dragged row's middle in the viewport, or null when nothing is dragged or its last swap is not laid out. */
    fun draggedMiddle(): Float? {
        val id = draggedId ?: return null
        val row = visibleRows().firstOrNull { it.key == id } ?: return null
        if (row.index != order.indexOfFirst { it.id == id }) return null
        return startOffset + delta + row.size / 2f
    }

    private fun retarget() {
        val id = draggedId ?: return
        val visible = visibleRows()
        val row = visible.firstOrNull { it.key == id } ?: return
        val position = order.indexOfFirst { it.id == id }
        if (row.index != position) return // the last swap is not laid out yet
        val slots = visible.map { ListSlot(it.index, it.offset, it.size) }
        val target = reorderTarget(ListSlot(position, startOffset, row.size), delta, slots) ?: return
        if (target !in order.indices) return
        keepScrollPosition()
        order = order.toMutableList().apply { add(target, removeAt(position)) }
    }

    /** Vertical translation that keeps the dragged row under the finger. */
    fun translationOf(id: Long): Float {
        if (id != draggedId) return 0f
        val row = visibleRows().firstOrNull { it.key == id } ?: return 0f
        return startOffset + delta - row.offset
    }

    /**
     * Drops the row and returns its (from, to) up-next positions, or null when it did not move or the queue changed
     * under the drag ([currentIds] differ from the rows the drag started with).
     */
    fun end(currentIds: List<Long>): Pair<Int, Int>? {
        val id = draggedId ?: return null
        val to = order.indexOfFirst { it.id == id }
        cancel()
        return (startPosition to to).takeIf { currentIds == startIds && to >= 0 && to != startPosition }
    }

    fun cancel() {
        draggedId = null
        delta = 0f
    }
}
