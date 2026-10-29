package io.sonora.core.domain.queue

import io.sonora.core.domain.model.QueueItem
import io.sonora.core.domain.model.RepeatMode

/**
 * What playback resumes from after the process died: the [PlayQueue] (play order, restore order, shuffle state), the
 * entry that was playing and its position, and the repeat mode.
 */
data class PlaybackSnapshot(
    val items: List<QueueItem>,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffled: Boolean,
    val unshuffledIds: List<Long>,
    val repeatMode: RepeatMode,
) {
    /** The queue to continue with; fresh ids continue after the saved ones so none is handed out twice. */
    fun toQueue(): PlayQueue = PlayQueue(
        items = items,
        currentIndex = currentIndex,
        shuffled = shuffled,
        unshuffledIds = unshuffledIds,
        nextId = maxOf(1L, (items.maxOfOrNull { it.id } ?: 0L) + 1),
    )

    /**
     * The snapshot with only the entries [keep] accepts (e.g. files still readable), repaired so it is safe to play:
     * the current entry keeps its position; if it is dropped the next kept entry plays from its start (or, with none
     * after it, the last kept one before it). The restore order keeps its saved order for the kept entries and gains
     * any queued entry it missed. Returns `null` when nothing is left.
     */
    fun restorable(keep: (QueueItem) -> Boolean): PlaybackSnapshot? {
        if (items.isEmpty()) return null
        val index = currentIndex.coerceIn(items.indices)
        val kept = items.filter(keep)
        if (kept.isEmpty()) return null
        val keptIds = kept.map { it.id }
        val keptSet = keptIds.toHashSet()
        val current = items[index]
        val (resumeAt, position) = if (current.id in keptSet) {
            current to positionMs.coerceAtLeast(0L)
        } else {
            val replacement = items.drop(index + 1).firstOrNull { it.id in keptSet } ?: items.take(index).last { it.id in keptSet }
            replacement to 0L
        }
        val restoreOrder = if (shuffled) {
            val known = unshuffledIds.distinct().filter { it in keptSet }
            val knownSet = known.toHashSet()
            known + keptIds.filter { it !in knownSet }
        } else {
            keptIds
        }
        return copy(
            items = kept,
            currentIndex = kept.indexOf(resumeAt),
            positionMs = position,
            unshuffledIds = restoreOrder,
        )
    }
}

/** This queue as a [PlaybackSnapshot] at [positionMs]; `null` when nothing is queued. */
fun PlayQueue.snapshot(positionMs: Long, repeatMode: RepeatMode): PlaybackSnapshot? {
    if (current == null) return null
    return PlaybackSnapshot(items, currentIndex, positionMs.coerceAtLeast(0L), shuffled, unshuffledIds, repeatMode)
}
