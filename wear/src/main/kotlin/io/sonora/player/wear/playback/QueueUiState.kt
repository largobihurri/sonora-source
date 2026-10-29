package io.sonora.player.wear.playback

import io.sonora.core.watchsync.remote.QueueDirection
import io.sonora.core.watchsync.remote.moveTarget

/** One song of the player's timeline, as the queue screen needs it (a plain value so the mapping stays pure). */
data class QueueItem(val mediaId: String, val title: String?, val artist: String?)

/**
 * A row of the queue screen: the playing song or an upcoming one. [index] is its place in the player's timeline (what the
 * commands take); [key] tells rows apart in the list, also when a song is queued more than once.
 */
data class QueueEntry(
    val key: String,
    val index: Int,
    val mediaId: String,
    val title: String,
    val artist: String?,
    val isCurrent: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    /** Whether the entry can be taken out: always on the watch's own queue; the phone's playing song cannot be. */
    val canRemove: Boolean = true,
)

/** The queue screen's content: the playing song first, then the upcoming ones in order. Empty when nothing is queued. */
data class QueueUiState(val entries: List<QueueEntry> = emptyList())

/**
 * The queue to show for a timeline of [items] whose playing song is at [currentIndex] (clamped into the timeline when the
 * player does not know it). Songs already played are not listed.
 */
fun queueUiState(items: List<QueueItem>, currentIndex: Int): QueueUiState {
    if (items.isEmpty()) return QueueUiState()
    val current = currentIndex.coerceIn(0, items.lastIndex)
    val seen = HashMap<String, Int>()
    val entries = (current until items.size).map { index ->
        val item = items[index]
        val occurrence = seen.merge(item.mediaId, 1, Int::plus)!!
        QueueEntry(
            key = "${item.mediaId}#$occurrence",
            index = index,
            mediaId = item.mediaId,
            title = item.title.orEmpty(),
            artist = item.artist,
            isCurrent = index == current,
            canMoveUp = moveTarget(QueueDirection.Up, index, current, items.size) != null,
            canMoveDown = moveTarget(QueueDirection.Down, index, current, items.size) != null,
        )
    }
    return QueueUiState(entries)
}

/**
 * Whether the timeline (of [count] songs, whose media id at an index is [mediaIdAt]) still has this entry's song at the
 * entry's place. A queue screen can be a step behind the player, so a command built from an old entry must do nothing when
 * the index is out of range or another song now sits there. [mediaIdAt] is only called for an index inside the timeline.
 */
fun QueueEntry.isAt(count: Int, mediaIdAt: (Int) -> String): Boolean = index in 0 until count && mediaIdAt(index) == mediaId
