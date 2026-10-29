package io.sonora.core.playback

import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Decides when a playback counts as a play, by time actually heard: half of the song or 4 minutes,
 * whichever comes first. Only time spent playing counts, measured on [now] (a monotonic clock, milliseconds): paused
 * and buffering time do not, and neither does a seek, which moves the position without anything being heard. Time
 * heard is song time: wall time scaled by the playback speed ([setSpeed]).
 *
 * A playback is one queue entry from its start: a new entry, or the same entry again on repeat. The same entry
 * restated without a repeat (a queue change that keeps it current) goes on with its listening. Each playback counts
 * once, under its song key (the library's track uri); an entry without a key never counts.
 *
 * A duration that is unknown (0 or less) counts at 4 minutes: that much listening is a play whatever the real length,
 * so it never counts more than the rule would; a duration learnt later ([setDuration]) applies at once.
 * Not thread-safe: call it from the player's thread.
 */
internal class PlayCountTracker(private val now: () -> Long) {
    private var entry: String? = null
    private var key: String? = null
    private var durationMs = 0L
    private var heardMs = 0L
    private var playingSince: Long? = null
    private var counted = false
    private var speed = 1f

    /**
     * [entryId] (a queue entry id, `null` for none) became the current entry, playing [key]. Returns the key of the
     * playback it ends if that one just reached its threshold and was not reported yet.
     */
    fun start(entryId: String?, key: String?, durationMs: Long, isPlaying: Boolean, isRepeat: Boolean): String? {
        val finished = poll()
        if (entryId != null && entryId == entry && !isRepeat) {
            this.key = key
            if (durationMs > 0) this.durationMs = durationMs
            setPlaying(isPlaying)
            return finished
        }
        entry = entryId
        this.key = key
        this.durationMs = durationMs
        heardMs = 0L
        counted = false
        playingSince = if (isPlaying) now() else null
        return finished
    }

    /** The current entry's duration, once the player knows it. */
    fun setDuration(durationMs: Long) {
        if (durationMs > 0) this.durationMs = durationMs
    }

    fun setPlaying(isPlaying: Boolean) {
        val since = playingSince
        if (isPlaying && since == null) {
            playingSince = now()
        } else if (!isPlaying && since != null) {
            heardMs += songTime(now() - since)
            playingSince = null
        }
    }

    /**
     * Playback runs at [speed] (a factor) from now on: listening is counted in song time, so at 2x half of a song is
     * heard in a quarter of its length. Time played so far keeps the speed it was played at.
     */
    fun setSpeed(speed: Float) {
        if (speed <= 0f || speed.isNaN() || speed == this.speed) return
        playingSince?.let { since ->
            val at = now()
            heardMs += songTime(at - since)
            playingSince = at
        }
        this.speed = speed
    }

    /** The current playback's key, once, when it has been heard long enough to count; `null` otherwise. */
    fun poll(): String? {
        val key = key?.takeIf { it.isNotBlank() } ?: return null
        if (counted || heard() < thresholdMs()) return null
        counted = true
        return key
    }

    /** Listening still needed for the current playback to count while it plays; `null` when nothing is pending. */
    fun nextCheckInMs(): Long? {
        if (key.isNullOrBlank() || counted || playingSince == null) return null
        return ceil((thresholdMs() - heard()).coerceAtLeast(0L) / speed.toDouble()).toLong()
    }

    /** Song time heard so far: wall time played, each stretch at its speed. */
    private fun heard(): Long = heardMs + (playingSince?.let { songTime(now() - it) } ?: 0L)

    /** The song time played in [wallMs] of playing at the current speed. */
    private fun songTime(wallMs: Long): Long = (wallMs * speed.toDouble()).roundToLong()

    private fun thresholdMs(): Long = if (durationMs > 0) minOf(durationMs / 2, MAX_THRESHOLD_MS) else MAX_THRESHOLD_MS

    private companion object {
        const val MAX_THRESHOLD_MS = 4 * 60_000L
    }
}
