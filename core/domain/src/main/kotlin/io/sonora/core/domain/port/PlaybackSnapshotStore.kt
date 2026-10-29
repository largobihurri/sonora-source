package io.sonora.core.domain.port

import io.sonora.core.domain.queue.PlaybackSnapshot

/**
 * Keeps the last [PlaybackSnapshot] across process death. The queue is large and changes rarely, the position is tiny
 * and changes all the time, so they are saved apart: [savePosition] never rewrites the queue.
 */
interface PlaybackSnapshotStore {
    /** The last saved snapshot, with the latest saved position; `null` if none or unreadable. */
    suspend fun load(): PlaybackSnapshot?

    /** Saves the whole snapshot (queue, current entry and position). */
    suspend fun save(snapshot: PlaybackSnapshot)

    /** Saves only where playback is: queue entry [itemId] at [positionMs]. Ignored on load if that entry is gone. */
    suspend fun savePosition(itemId: Long, positionMs: Long)
}
