package io.sonora.core.domain.stats

import java.util.UUID

/**
 * Measures how long each playback is actually heard, for the listening statistics: only time spent playing counts,
 * measured on [now] (a monotonic clock, milliseconds), so pauses, buffering and seeks add nothing, and a skipped song
 * keeps the part that was heard. A playback starts at the wall-clock time ([wallNow]) it first plays, not when it
 * became current while paused (a queue restored at app start).
 *
 * Playbacks follow the play counts' rule: one queue entry from its start, a new entry or the same entry again on
 * repeat; the same entry restated without a repeat goes on. Each call that ends or pauses a playback, or marks it
 * counted, returns its [Listening] to save (`null` when there is nothing worth saving: no song key, never played, or
 * under [MIN_LISTENED_MS] heard and not counted). Not thread-safe: call it from the player's thread.
 */
class ListeningMeter(
    private val now: () -> Long,
    private val wallNow: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private var entry: String? = null
    private var key: String? = null
    private var id = ""
    private var startedAtMs: Long? = null
    private var heardMs = 0L
    private var playingSince: Long? = null
    private var counted = false

    /**
     * [entryId] (a queue entry id, `null` for none) became the current entry, playing [key]. Returns the playback it
     * ends, or the progress of the one it goes on with when it pauses it.
     */
    fun start(entryId: String?, key: String?, isPlaying: Boolean, isRepeat: Boolean): Listening? {
        if (entryId != null && entryId == entry && !isRepeat) {
            this.key = key
            return setPlaying(isPlaying)
        }
        val ended = setPlaying(false) ?: snapshot()
        entry = entryId
        this.key = key
        id = newId()
        startedAtMs = null
        heardMs = 0L
        counted = false
        setPlaying(isPlaying)
        return ended
    }

    /** Playing started or stopped; a stop returns the playback's progress so far. */
    fun setPlaying(isPlaying: Boolean): Listening? {
        val since = playingSince
        if (isPlaying && since == null) {
            playingSince = now()
            if (startedAtMs == null) startedAtMs = wallNow()
        } else if (!isPlaying && since != null) {
            heardMs += now() - since
            playingSince = null
            return snapshot()
        }
        return null
    }

    /** The current playback counted as a play: returns it at once, so the play shows before the song ends. */
    fun markCounted(): Listening? {
        counted = true
        return snapshot()
    }

    /** Playback is going away (the service stops): pauses the current playback and returns it. */
    fun finish(): Listening? = setPlaying(false) ?: snapshot()

    private fun snapshot(): Listening? {
        val key = key?.takeIf { it.isNotBlank() } ?: return null
        val started = startedAtMs ?: return null
        val heard = heardMs + (playingSince?.let { now() - it } ?: 0L)
        if (heard < MIN_LISTENED_MS && !counted) return null
        return Listening(id, key, started, heard, counted)
    }

    companion object {
        /** Less than this heard is a passing skip, not listening worth keeping. */
        const val MIN_LISTENED_MS = 1_000L
    }
}
