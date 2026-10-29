package io.sonora.core.playback

import android.util.Log
import io.sonora.core.domain.port.PlaybackSnapshotStore
import io.sonora.core.domain.queue.PlaybackSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Saves what playback resumes from, sparing the disk: queue changes (edits, shuffle, repeat) are saved once they
 * settle for [debounceMs]; the position alone (a few bytes) is saved right away when another entry starts or playback
 * pauses or reaches the end of the queue (flushing a pending queue save), and every [positionEveryMs] while playing. An
 * empty queue is never saved, so the queue that was playing survives a session that starts empty. Save failures are
 * logged and never stop recording. Confined to [scope]'s thread.
 *
 * Saves run one at a time, in the order they were asked for (a slow one is never overtaken), so the last state asked
 * for is the one left on disk; saves waiting meanwhile collapse into the latest queue and the latest position.
 *
 * At the end of the queue its last entry is saved at its start: that is what play does after the end, so resumption
 * opens where the next play would start instead of on a finished song.
 */
internal class SnapshotRecorder(
    private val scope: CoroutineScope,
    private val store: PlaybackSnapshotStore,
    private val now: () -> Long,
    private val debounceMs: Long = 1_000L,
    private val positionEveryMs: Long = 10_000L,
) {
    private var latest: PlaybackSnapshot? = null
    private var pendingQueueSave: Job? = null
    private var positionItemId: Long? = null
    private var positionSavedAt = 0L
    private var wasPlaying = false
    private var wasEnded = false

    // The single writer and what it has still to save.
    private var writer: Job? = null
    private var queueToSave: PlaybackSnapshot? = null
    private var positionToSave: SavedPosition? = null

    private class SavedPosition(val itemId: Long, val positionMs: Long)

    /** Takes in the player's state ([ended]: the queue played to its end); call on every player event and tick. */
    fun onState(snapshot: PlaybackSnapshot?, isPlaying: Boolean, ended: Boolean = false) {
        val paused = wasPlaying && !isPlaying
        val justEnded = ended && !wasEnded
        wasPlaying = isPlaying
        wasEnded = ended
        if (snapshot == null) return
        val state = if (ended) snapshot.copy(positionMs = 0L) else snapshot
        val previous = latest
        latest = state
        if (previous == null || !sameQueue(previous, state)) scheduleQueueSave()
        val itemId = state.items[state.currentIndex].id
        when {
            paused || justEnded -> {
                if (pendingQueueSave?.isActive == true) saveQueueNow()
                savePosition(itemId, state.positionMs)
            }
            itemId != positionItemId -> savePosition(itemId, state.positionMs)
            isPlaying && now() - positionSavedAt >= positionEveryMs -> savePosition(itemId, state.positionMs)
        }
    }

    /** Cheap on every tick: the queue's lists are the same instances until the queue changes. */
    private fun sameQueue(a: PlaybackSnapshot, b: PlaybackSnapshot) =
        same(a.items, b.items) && same(a.unshuffledIds, b.unshuffledIds) && a.shuffled == b.shuffled &&
            a.repeatMode == b.repeatMode

    private fun same(a: List<*>, b: List<*>) = a === b || a == b

    private fun scheduleQueueSave() {
        pendingQueueSave?.cancel()
        pendingQueueSave = scope.launch {
            delay(debounceMs)
            latest?.let(::saveQueue)
        }
    }

    private fun saveQueueNow() {
        pendingQueueSave?.cancel()
        pendingQueueSave = null
        latest?.let(::saveQueue)
    }

    /** The queue snapshot carries the position too, newer than any position still waiting, which it replaces. */
    private fun saveQueue(snapshot: PlaybackSnapshot) {
        queueToSave = snapshot
        positionToSave = null
        write()
    }

    private fun savePosition(itemId: Long, positionMs: Long) {
        positionItemId = itemId
        positionSavedAt = now()
        positionToSave = SavedPosition(itemId, positionMs)
        write()
    }

    /** Starts the writer unless it runs; it saves what waits (the queue first, then any later position) until none. */
    private fun write() {
        if (writer?.isActive == true) return
        writer = scope.launch {
            while (true) {
                val queue = queueToSave
                val position = positionToSave
                when {
                    queue != null -> {
                        queueToSave = null
                        safely { store.save(queue) }
                    }
                    position != null -> {
                        positionToSave = null
                        safely { store.savePosition(position.itemId, position.positionMs) }
                    }
                    else -> break
                }
            }
        }
    }

    private suspend fun safely(save: suspend () -> Unit) {
        try {
            save()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Playback snapshot not saved", e)
        }
    }

    private companion object {
        const val TAG = "SonoraSnapshot"
    }
}
