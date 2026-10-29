package io.sonora.core.domain.model

/**
 * One entry of the play queue. [id] is unique within the session and stable while the entry stays queued, so the
 * same [track] queued twice is two distinguishable entries.
 */
data class QueueItem(val id: Long, val track: Track)

/** How tracks picked in the library join the queue. */
enum class QueueAction {
    /** Play immediately, right after the current track, keeping the rest of the queue. */
    PLAY_NOW,

    /** Right after the current track. */
    PLAY_NEXT,

    /** At the end of the queue. */
    ADD_TO_QUEUE,
}
