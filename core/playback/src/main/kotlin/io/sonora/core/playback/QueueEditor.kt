package io.sonora.core.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.sonora.core.domain.model.QueueItem
import io.sonora.core.domain.queue.PlayQueue
import io.sonora.core.domain.queue.QueueChange
import kotlin.random.Random

/**
 * Keeps the [PlayQueue] model in step with a Media3 [Player] (the app's MediaController): [sync] adopts the player's
 * playlist and position, [edit] runs a queue operation against it and applies the resulting edits, [followPlayer]
 * takes in changes other controllers made. Main thread only.
 *
 * The player's shuffle flag mirrors [PlayQueue.shuffled]; the session plays its playlist as listed whatever the flag
 * says (see [SessionPlayer]), so the flag is pure state that every controller shares. A flag that differs from the
 * queue was set by another controller (notification, Bluetooth, Android Auto) and is applied to the real queue.
 */
internal class QueueEditor(private val random: Random = Random) {
    var queue = PlayQueue()
        private set

    /** True while edits are applied; player events seen meanwhile describe a half-edited playlist. */
    var editing = false
        private set

    /** False until the first sync after creation or [reset]; that sync takes the shuffle state from the player. */
    private var adopted = false

    /** Ids handed to session entries without an item id, per media id in playlist order (see [queueItems]). */
    private var foreignIds = emptyMap<String, List<Long>>()
    private var nextForeignId = FOREIGN_ITEM_ID

    /** A restored queue the session is about to load (see [expectRestore]). */
    private var restored: PlayQueue? = null

    /**
     * The session is loading [queue] (a resumed snapshot) behind this editor's back: once the player's playlist holds
     * exactly its entries, the queue is [queue] itself, with full tracks and its restore order, instead of a queue
     * rebuilt from MediaItems. Any other non-empty playlist first cancels the expectation.
     */
    fun expectRestore(queue: PlayQueue) {
        restored = queue
    }

    /** The queue synced with [player]'s playlist and position. */
    fun sync(player: Player): PlayQueue {
        restored?.let { expected -> adoptRestored(player, expected)?.let { return it } }
        if (!adopted) {
            queue = queue.copy(shuffled = player.shuffleModeEnabled)
            adopted = true
        }
        return queue.synced(queueItems(player), player.currentMediaItemIndex).also { queue = it }
    }

    /**
     * Takes in a player event: re-syncs the queue and, when another controller toggled the shuffle flag, reorders the
     * real queue the same way the app's own toggle does. Returns true when that changed the queue.
     */
    fun followPlayer(player: Player): Boolean {
        if (editing) return false
        val wanted = player.shuffleModeEnabled
        if (sync(player).shuffled == wanted) return false
        return edit(player, startPlayback = false) { setShuffle(wanted, random) }
    }

    /**
     * Runs [operation] on the synced queue and applies its edits to [player]; returns false when nothing changed.
     * [startPlayback] starts playing after an operation that changed something.
     */
    fun edit(player: Player, startPlayback: Boolean, operation: PlayQueue.() -> QueueChange): Boolean {
        val change = sync(player).operation()
        if (change.edits.isEmpty() && change.queue == queue) return false
        editing = true
        try {
            change.edits.applyTo(PlayerPlaylist(player, MediaItemMapper::toMediaItem))
            // Shown to every controller; it never changes what plays (the session's shuffle order is the identity).
            if (player.shuffleModeEnabled != change.queue.shuffled) player.shuffleModeEnabled = change.queue.shuffled
            queue = change.queue
            if (startPlayback && change.edits.isNotEmpty()) {
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            }
        } finally {
            editing = false
        }
        return true
    }

    private fun adoptRestored(player: Player, expected: PlayQueue): PlayQueue? {
        if (player.mediaItemCount == 0) return null
        restored = null
        val ids = List(player.mediaItemCount) { QueueMediaId.parse(player.getMediaItemAt(it).mediaId).itemId }
        if (ids != expected.items.map { it.id }) return null
        adopted = true
        queue = expected.copy(
            currentIndex = player.currentMediaItemIndex.coerceIn(expected.items.indices),
            nextId = maxOf(expected.nextId, queue.nextId),
        )
        return queue
    }

    /** Forgets the queue (the session is gone); item ids keep counting up. */
    fun reset() {
        queue = PlayQueue(nextId = queue.nextId)
        adopted = false
        foreignIds = emptyMap()
    }

    /**
     * The player's playlist as queue items. Entries this process queued keep their full track. Entries without an
     * item id (queued by another controller or an earlier process) are rebuilt from their MediaItem and keep the id
     * they got first: the n-th entry with a given media id keeps the n-th id handed to that media id, so inserting or
     * removing other entries never renumbers them.
     */
    private fun queueItems(player: Player): List<QueueItem> {
        val known = queue.items.associateBy { it.id }
        val handedOut = HashMap<String, MutableList<Long>>()
        val items = List(player.mediaItemCount) { index ->
            val item = player.getMediaItemAt(index)
            val itemId = QueueMediaId.parse(item.mediaId).itemId
            if (itemId != null) {
                known[itemId] ?: QueueItem(itemId, MediaItemMapper.toTrack(item))
            } else {
                val ids = handedOut.getOrPut(item.mediaId) { mutableListOf() }
                val id = foreignIds[item.mediaId]?.getOrNull(ids.size) ?: nextForeignId--
                ids += id
                foreignItem(item, id, known)
            }
        }
        foreignIds = handedOut
        return items
    }

    private fun foreignItem(item: MediaItem, id: Long, known: Map<Long, QueueItem>): QueueItem =
        known[id] ?: QueueItem(id, MediaItemMapper.toTrack(item))

    private companion object {
        /** First id for session entries without one; counts down, so never one [PlayQueue] mints (from 1 up). */
        const val FOREIGN_ITEM_ID = -1L
    }
}
