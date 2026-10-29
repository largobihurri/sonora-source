package io.sonora.core.domain.queue

import io.sonora.core.domain.model.QueueItem
import io.sonora.core.domain.model.Track
import kotlin.random.Random

/** One primitive playlist change, in player index semantics (indices refer to the list before the edit). */
sealed interface QueueEdit {
    /** Replaces the whole playlist and makes [startIndex] current. */
    data class SetAll(val items: List<QueueItem>, val startIndex: Int) : QueueEdit

    /** Inserts [items] so the first one lands at [index]. */
    data class Insert(val index: Int, val items: List<QueueItem>) : QueueEdit

    /** Moves one item; [to] is its index once moved. */
    data class Move(val from: Int, val to: Int) : QueueEdit

    /** Removes the items in [from] until [toExclusive]; never covers the current item. */
    data class Remove(val from: Int, val toExclusive: Int) : QueueEdit

    /** Replaces [from] until [toExclusive] with [items]; never covers the current item. */
    data class Replace(val from: Int, val toExclusive: Int, val items: List<QueueItem>) : QueueEdit

    /** Makes [index] the current item, from its start. */
    data class SkipTo(val index: Int) : QueueEdit

    /**
     * Swaps the entry at [index] for [item]: the same entry (same id and file) with new track details. May be the
     * current item, which keeps playing.
     */
    data class Update(val index: Int, val item: QueueItem) : QueueEdit
}

/** The queue after an operation plus the playlist edits that turn the previous playlist into it. */
data class QueueChange(val queue: PlayQueue, val edits: List<QueueEdit>)

/**
 * The real play queue: [items] is the order the player plays (shuffle included), so "Up Next" is always truthful.
 * Operations never interrupt the current item unless they say so (play, play now, skip to).
 *
 * Shuffle rule: turning shuffle on keeps the current item and moves it first, followed by every other item in random
 * order; [unshuffledIds] remembers the order to restore. Turning it off restores that order around the current item.
 * Inserts are recorded in both orders: Play Now / Play Next go right after the current item in both (so they follow
 * the track they were queued behind when shuffle is turned off), Add to Queue goes at the end of both. Moves while
 * shuffled only reorder the play order; removals drop the item from both. [shuffled] is the user's intent: it is kept
 * on an empty queue (tracks queued into it then play shuffled) and only [play] and [setShuffle] change it.
 */
data class PlayQueue(
    val items: List<QueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val shuffled: Boolean = false,
    /** Item ids in the order shuffle off restores; the same ids as [items], in [items] order while unshuffled. */
    val unshuffledIds: List<Long> = items.map { it.id },
    /** Next fresh item id; ids are never reused within a session. */
    val nextId: Long = 1L,
) {
    val current: QueueItem? get() = items.getOrNull(currentIndex)

    /** Replaces the queue with [tracks]; shuffled, a random track goes first and [startIndex] is ignored. */
    fun play(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false, random: Random = Random): QueueChange {
        if (tracks.isEmpty()) return unchanged()
        val fresh = mint(tracks)
        val order = if (shuffle) {
            val first = fresh[random.nextInt(fresh.size)]
            listOf(first) + fresh.filter { it.id != first.id }.shuffled(random)
        } else {
            fresh
        }
        val queue = PlayQueue(
            items = order,
            currentIndex = if (shuffle) 0 else startIndex.coerceIn(0, order.lastIndex),
            shuffled = shuffle,
            unshuffledIds = fresh.ids(),
            nextId = nextId + fresh.size,
        )
        return QueueChange(queue, listOf(QueueEdit.SetAll(queue.items, queue.currentIndex)))
    }

    /** Inserts [tracks] after the current item and skips to the first of them; on an empty queue they become the queue. */
    fun playNow(tracks: List<Track>): QueueChange {
        if (tracks.isEmpty()) return unchanged()
        if (current == null) return play(tracks, shuffle = shuffled)
        val inserted = insertAfterCurrent(tracks)
        val first = currentIndex + 1
        return QueueChange(inserted.queue.copy(currentIndex = first), inserted.edits + QueueEdit.SkipTo(first))
    }

    /** Inserts [tracks] right after the current item; on an empty queue they become the queue (shuffle kept). */
    fun playNext(tracks: List<Track>): QueueChange = when {
        tracks.isEmpty() -> unchanged()
        current == null -> play(tracks, shuffle = shuffled)
        else -> insertAfterCurrent(tracks)
    }

    /** Appends [tracks]; on an empty queue they become the queue (shuffle kept). */
    fun addToQueue(tracks: List<Track>): QueueChange {
        if (tracks.isEmpty()) return unchanged()
        if (current == null) return play(tracks, shuffle = shuffled)
        val fresh = mint(tracks)
        val queue = copy(items = items + fresh, unshuffledIds = unshuffledIds + fresh.ids(), nextId = nextId + fresh.size)
        return QueueChange(queue, listOf(QueueEdit.Insert(items.size, fresh)))
    }

    /** Moves an upcoming item to another upcoming position; anything else is ignored. */
    fun move(from: Int, to: Int): QueueChange {
        if (from == to || !isUpcoming(from) || !isUpcoming(to)) return unchanged()
        val moved = items.toMutableList().apply { add(to, removeAt(from)) }
        val queue = copy(items = moved, unshuffledIds = if (shuffled) unshuffledIds else moved.ids())
        return QueueChange(queue, listOf(QueueEdit.Move(from, to)))
    }

    /** Removes any item but the current one. */
    fun remove(index: Int): QueueChange {
        if (index !in items.indices || index == currentIndex) return unchanged()
        val removedId = items[index].id
        val queue = copy(
            items = items.filterIndexed { i, _ -> i != index },
            currentIndex = if (index < currentIndex) currentIndex - 1 else currentIndex,
            unshuffledIds = unshuffledIds.filter { it != removedId },
        )
        return QueueChange(queue, listOf(QueueEdit.Remove(index, index + 1)))
    }

    /** Removes every item after the current one. */
    fun clearUpcoming(): QueueChange {
        if (current == null || currentIndex == items.lastIndex) return unchanged()
        val removed = items.drop(currentIndex + 1).ids().toSet()
        val queue = copy(items = items.take(currentIndex + 1), unshuffledIds = unshuffledIds.filter { it !in removed })
        return QueueChange(queue, listOf(QueueEdit.Remove(currentIndex + 1, items.size)))
    }

    fun skipTo(index: Int): QueueChange {
        if (index !in items.indices) return unchanged()
        return QueueChange(copy(currentIndex = index), listOf(QueueEdit.SkipTo(index)))
    }

    /** Reorders the real queue (see the class rule); the current item keeps playing. */
    fun setShuffle(enabled: Boolean, random: Random = Random): QueueChange {
        if (enabled == shuffled) return unchanged()
        val current = current ?: return QueueChange(copy(shuffled = enabled), emptyList())
        val order = if (enabled) {
            listOf(current) + items.filter { it.id != current.id }.shuffled(random)
        } else {
            val byId = items.associateBy { it.id }
            unshuffledIds.map(byId::getValue)
        }
        val rebuilt = rebuildAround(order)
        return rebuilt.copy(queue = rebuilt.queue.copy(shuffled = enabled))
    }

    /** Every entry for [track]'s file ([Track.uri]) takes [track]'s details, in place; nothing else changes. */
    fun updateTrack(track: Track): QueueChange {
        val edits = items.mapIndexedNotNull { index, item ->
            if (item.track.uri == track.uri) QueueEdit.Update(index, item.copy(track = track)) else null
        }
        if (edits.isEmpty()) return unchanged()
        val updated = items.toMutableList().apply { edits.forEach { set(it.index, it.item) } }
        return QueueChange(copy(items = updated), edits)
    }

    /**
     * Adopts the player's playlist ([playerItems], current [playerIndex]). When it holds the same entries the queue
     * only takes the position; otherwise (e.g. a restarted session) the queue is rebuilt from it, keeping the restore
     * order of the entries still present. The shuffle state is the user's intent and survives every sync, an empty
     * player included.
     */
    fun synced(playerItems: List<QueueItem>, playerIndex: Int): PlayQueue {
        if (playerItems.isEmpty()) return PlayQueue(shuffled = shuffled, nextId = nextId)
        val index = playerIndex.coerceIn(0, playerItems.lastIndex)
        val ids = playerItems.ids()
        if (ids == items.ids()) return copy(currentIndex = index)
        val present = ids.toSet()
        val known = unshuffledIds.filter { it in present }
        val knownSet = known.toSet()
        return PlayQueue(
            items = playerItems,
            currentIndex = index,
            shuffled = shuffled,
            unshuffledIds = if (shuffled) known + ids.filter { it !in knownSet } else ids,
            nextId = maxOf(nextId, ids.max() + 1),
        )
    }

    private fun insertAfterCurrent(tracks: List<Track>): QueueChange {
        val current = checkNotNull(current)
        val fresh = mint(tracks)
        val at = currentIndex + 1
        val anchor = unshuffledIds.indexOf(current.id) + 1
        val queue = copy(
            items = items.toMutableList().apply { addAll(at, fresh) },
            unshuffledIds = unshuffledIds.toMutableList().apply { addAll(anchor, fresh.ids()) },
            nextId = nextId + fresh.size,
        )
        return QueueChange(queue, listOf(QueueEdit.Insert(at, fresh)))
    }

    /**
     * Turns the playlist into [order] (which holds the same items) without touching the current item: the items
     * before it and the items after it are each replaced in one edit, skipped when already in place.
     */
    private fun rebuildAround(order: List<QueueItem>): QueueChange {
        val currentId = checkNotNull(current).id
        val newIndex = order.indexOfFirst { it.id == currentId }
        val before = order.take(newIndex)
        val after = order.drop(newIndex + 1)
        val edits = buildList {
            if (items.take(currentIndex).ids() != before.ids()) add(QueueEdit.Replace(0, currentIndex, before))
            if (items.drop(currentIndex + 1).ids() != after.ids()) {
                add(QueueEdit.Replace(newIndex + 1, newIndex + items.size - currentIndex, after))
            }
        }
        return QueueChange(copy(items = order, currentIndex = newIndex), edits)
    }

    private fun isUpcoming(index: Int): Boolean = current != null && index in (currentIndex + 1)..items.lastIndex

    private fun mint(tracks: List<Track>): List<QueueItem> = tracks.mapIndexed { i, t -> QueueItem(nextId + i, t) }

    private fun unchanged() = QueueChange(this, emptyList())

    private fun List<QueueItem>.ids(): List<Long> = map { it.id }
}
