package io.sonora.core.domain.playback

/**
 * One reading of the playback clock, filled in by [PlaybackClock.read]. Mutable on purpose: a per-frame reader keeps
 * one instance and reuses it, so reading the clock allocates nothing.
 *
 * @property queueItemId the queue item playing (the id the queue gives each entry, so a repeated track is told apart
 *   from itself), or [NO_ITEM] when the item carries none. A change means another song (or the same one again) began.
 * @property positionMs where the audible stream is, in the song's own time (the renderer's clock, so the AudioTrack's
 *   latency is already accounted for).
 * @property speed the playback speed now (1 = normal).
 * @property playing whether the position is moving: playing and ready, not paused, buffering or ended.
 * @property looping whether the audio being heard is a DJ loop repeating `[loopInMs, loopOutMs)`: the position then
 *   wraps from the end of the loop back to its start with no jump of the audio, and [positionMs] stays inside it. It
 *   is read in the same pass as [positionMs], so the two agree (see [LoopClockSource]).
 * @property loopInMs the start of the loop being heard, in the same time as [positionMs]; meaningful while [looping].
 * @property loopOutMs the end (exclusive) of the loop being heard; meaningful while [looping].
 */
class ClockSample {
    var queueItemId: Long = NO_ITEM
    var positionMs: Long = 0L
    var speed: Float = 1f
    var playing: Boolean = false
    var looping: Boolean = false
    var loopInMs: Double = 0.0
    var loopOutMs: Double = 0.0

    companion object {
        /** The queue item id of a song whose media id carries none. */
        const val NO_ITEM: Long = -1L
    }
}

/**
 * The player's own clock, sampled once per drawn frame by the DJ screen. Unlike the playback state the UI collects
 * (refreshed every 250 ms through the media controller), this reads the playing engine directly, so a waveform can
 * scroll with the audio frame by frame. Feed the readings to a [PositionSmoother] to remove their jitter.
 */
interface PlaybackClock {

    /**
     * Writes the current reading into [into] and returns true; returns false, leaving [into] untouched, when there is
     * no local player to read (none attached, casting to another device, nothing queued) or the call is not on the
     * player's own thread (the main thread). Cheap and allocation-free, meant for the draw phase of every frame.
     */
    fun read(into: ClockSample): Boolean

    /**
     * Why the last [read] said no, one of [ClockReadFailure]'s values ([ClockReadFailure.NONE] after a good read), for
     * diagnostics. A clock that does not tell says [ClockReadFailure.UNKNOWN].
     */
    val lastFailure: Int get() = ClockReadFailure.UNKNOWN
}

/** The reasons a [PlaybackClock.read] says no (plain ints, so tracking them allocates nothing). */
object ClockReadFailure {
    /** The last read succeeded. */
    const val NONE: Int = 0

    /** No local player is attached (none yet, gone, or casting). */
    const val NO_PLAYER: Int = 1

    /** Called off the player's own thread. */
    const val WRONG_THREAD: Int = 2

    /** The player has no current item. */
    const val NO_ITEM: Int = 3

    /** The clock does not say. */
    const val UNKNOWN: Int = 4
}
