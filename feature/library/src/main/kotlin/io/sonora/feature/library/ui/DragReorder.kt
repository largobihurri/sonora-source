package io.sonora.feature.library.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** A laid-out list row: its index in the list, top offset and height in px. */
internal data class RowSlot(val index: Int, val offset: Int, val size: Int)

/** A row the list has laid out: its lazy-list [key], list [index], top [offset] and [size] in px. */
internal data class VisibleRow(val key: Any, val index: Int, val offset: Int, val size: Int)

/**
 * The row index a dragged row should swap with: the row under the dragged row's middle once moved by [dragged] px
 * from where [slot] started, or null while it is still over itself. A row held past the visible rows (at or beyond the
 * list's edge, where it scrolls) targets the row at that end.
 */
internal fun dragTarget(slot: RowSlot, dragged: Float, slots: List<RowSlot>): Int? {
    if (slots.isEmpty()) return null
    val top = slots.minOf { it.offset }.toFloat()
    val bottom = slots.maxOf { it.offset + it.size } - 1f
    val middle = (slot.offset + dragged + slot.size / 2f).coerceIn(top, bottom.coerceAtLeast(top))
    return slots.firstOrNull { it.index != slot.index && middle >= it.offset && middle < it.offset + it.size }?.index
}

/** [keys] with [key] moved to index [to]; unchanged when [key] is not there. */
internal fun moveKey(keys: List<Long>, key: Long, to: Int): List<Long> {
    val from = keys.indexOf(key)
    if (from < 0) return keys
    return keys.toMutableList().apply { add(to.coerceIn(0, lastIndex), removeAt(from)) }
}

/**
 * Drag-to-reorder for a lazy list whose rows are keyed by Long ids and start at list index [firstRowIndex] (rows
 * above are headers). While a row is dragged the list shows [order], reordered locally as the row crosses its
 * neighbours (also while the list auto-scrolls under it, see [scrolled]); the caller saves the move once, on drop
 * ([end]), and shows [shownOrder], which keeps the dropped order until the saved order arrives; the screen runs
 * [awaitSave] in an effect keyed on [awaitingSave] and the stored keys, which goes back to the stored order when the save
 * never lands. [keepScrollPosition] runs before each local move: it pins the list to the index it shows, as otherwise a
 * lazy list follows its first visible row's key and would jump with a moved first row.
 */
@Stable
internal class DragReorder(
    private val visibleRows: () -> List<VisibleRow>,
    private val firstRowIndex: Int,
    private val keepScrollPosition: () -> Unit = {},
) {

    constructor(listState: LazyListState, firstRowIndex: Int) : this(
        { listState.layoutInfo.visibleItemsInfo.map { VisibleRow(it.key, it.index, it.offset, it.size) } },
        firstRowIndex,
        { listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) },
    )

    var order by mutableStateOf(emptyList<Long>())
        private set
    var draggedKey by mutableStateOf<Long?>(null)
        private set
    private var delta by mutableFloatStateOf(0f)
    private var startOffset = 0
    private var startPosition = -1
    private var startKeys = emptyList<Long>()

    /** The order a drop left on screen while its move is saved, and the stored keys it was dropped over. */
    private class Drop(val order: List<Long>, val over: List<Long>)

    // Snapshot state: going back to the stored order after a failed save must recompose the list.
    private var drop by mutableStateOf<Drop?>(null)

    /** True after a drop until its saved order arrives, or [awaitSave] gives up on it. */
    val awaitingSave: Boolean get() = drop != null

    fun start(key: Long, keys: List<Long>) {
        val row = visibleRows().firstOrNull { it.key == key } ?: return
        drop = null
        order = keys
        startKeys = keys
        startPosition = keys.indexOf(key)
        startOffset = row.offset
        delta = 0f
        draggedKey = key
    }

    fun drag(dy: Float) {
        if (draggedKey == null) return
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
        val key = draggedKey ?: return null
        val row = visibleRows().firstOrNull { it.key == key } ?: return null
        if (row.index - firstRowIndex != order.indexOf(key)) return null
        return startOffset + delta + row.size / 2f
    }

    private fun retarget() {
        val key = draggedKey ?: return
        val visible = visibleRows().filter { it.index >= firstRowIndex }
        val row = visible.firstOrNull { it.key == key } ?: return
        val position = order.indexOf(key)
        if (row.index - firstRowIndex != position) return // the last swap is not laid out yet
        val slots = visible.map { RowSlot(it.index - firstRowIndex, it.offset, it.size) }
        val target = dragTarget(RowSlot(position, startOffset, row.size), delta, slots) ?: return
        if (target !in order.indices) return
        keepScrollPosition()
        order = moveKey(order, key, target)
    }

    /**
     * The row order to show for the stored [keys]: the dragged order while a row is dragged; after a drop, the dropped
     * order while the stored keys are still the ones it was dropped over, so the row never snaps back while the write
     * lands; otherwise [keys]. Only reads, so it may run during composition.
     */
    fun shownOrder(keys: List<Long>): List<Long> {
        if (draggedKey != null) return order
        val pending = drop ?: return keys
        return if (keys == pending.over) pending.order else keys
    }

    /** The stored [keys] are shown: a drop they no longer match (its move was saved, or the list changed) is done. */
    fun settle(keys: List<Long>) {
        val pending = drop ?: return
        if (keys != pending.over) drop = null
    }

    /**
     * Settles on the stored [keys] ([settle]), then, while a drop still waits for its save, gives it [SAVE_WAIT_MS] to
     * land before showing the stored order again (a save that failed changes nothing). Run from an effect keyed on
     * [awaitingSave] and the stored keys, so a save landing in time cancels the wait.
     */
    suspend fun awaitSave(keys: List<Long>) {
        settle(keys)
        if (drop == null) return
        delay(SAVE_WAIT_MS)
        drop = null
    }

    /** Vertical translation that keeps the dragged row under the finger. */
    fun translationOf(key: Long): Float {
        if (key != draggedKey) return 0f
        val row = visibleRows().firstOrNull { it.key == key } ?: return 0f
        return startOffset + delta - row.offset
    }

    /**
     * Drops the row and returns its new index, or null when it did not move or the list changed under the drag
     * ([currentKeys] differ from the rows the drag started with).
     */
    fun end(currentKeys: List<Long>): Int? {
        val key = draggedKey ?: return null
        val to = order.indexOf(key)
        val moved = to.takeIf { currentKeys == startKeys && to >= 0 && to != startPosition }
        if (moved != null) drop = Drop(order, currentKeys)
        cancel()
        return moved
    }

    fun cancel() {
        draggedKey = null
        delta = 0f
    }

    companion object {
        /** How long a dropped order waits for its saved order before the stored order shows again. */
        const val SAVE_WAIT_MS = 3_000L
    }
}
