package io.sonora.player.wear.history

/**
 * Turns what the player does into play records, with no Android in it. A play starts when sound starts on a song and is
 * extended, by the time actually heard (paused time does not count), on pause, when the song changes, when it ends and on
 * every [tick]. It is closed by a song change, the end of the queue or an empty queue, so a finished song played again is a
 * new play. A play that made no sound is never recorded. Each extension goes to [sink] as the whole record so far.
 *
 * Not thread-safe: drive it from one thread (the player's).
 */
internal class PlayRecorder(
    private val wallClock: () -> Long,
    private val monotonic: () -> Long,
    private val sink: (PlayLogEntry) -> Unit,
) {
    private var track: String? = null
    private var playing = false
    private var runningSince = 0L
    private var startedAt: Long? = null
    private var listened = 0L
    private var emitted = 0L

    /** The song now loaded, or `null` for none. Closes the play of the previous one, and opens one at once if sound goes on. */
    fun trackChanged(trackId: String?) {
        close()
        track = trackId
        if (playing) open()
    }

    /** Whether sound is being produced. */
    fun playingChanged(isPlaying: Boolean) {
        if (isPlaying == playing) return
        if (isPlaying) {
            playing = true
            open()
        } else {
            settle()
            playing = false
        }
    }

    /** The queue finished: the play ends, so the same song played again starts a new one. */
    fun ended() {
        playingChanged(false)
        close()
    }

    /** Periodic extension of the running play, so a process death loses at most one interval. */
    fun tick() {
        if (playing) settle()
    }

    private fun open() {
        runningSince = monotonic()
        if (startedAt == null && track != null) {
            startedAt = wallClock()
            listened = 0
            emitted = 0
        }
    }

    /** Adds the time heard since the last settle and writes the play if it grew. */
    private fun settle() {
        val now = monotonic()
        if (startedAt != null) {
            listened += (now - runningSince).coerceAtLeast(0)
            val id = track
            if (id != null && listened > emitted) {
                emitted = listened
                sink(PlayLogEntry(id, startedAt!!, listened))
            }
        }
        runningSince = now
    }

    private fun close() {
        if (playing) settle()
        startedAt = null
        listened = 0
        emitted = 0
    }
}
