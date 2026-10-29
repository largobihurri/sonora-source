package io.sonora.core.domain.playback

import kotlin.math.exp
import kotlin.math.max

/**
 * Turns the player's clock readings into the position a frame is drawn at: steady between readings, honest about
 * jumps. The readings ([ClockSample.positionMs]) are right on average but neither smooth nor fresh: they jitter by a
 * few milliseconds, and the player refreshes them only when its playback thread wakes. Media3 1.11's ExoPlayer
 * reports, on the application thread, the position its playback thread stored at its last pass
 * (`PlaybackInfo.positionUs`), and with its default dynamic scheduling that thread wakes, while playing audio, about
 * every half of the audio queued ahead (~125 ms at 1x with a 250 ms AudioTrack buffer): the reading is a staircase,
 * standing still for ~125 ms and then jumping by as much. A waveform scrolling by that would jump; one following it
 * with a tight snap would jump back.
 *
 * So each frame the position is predicted from the last one (`last + dt x speed`), and only a fresh reading (one that
 * differs from the last one taken in) says how far off the prediction is: a stale reading carries no news about now.
 * That error is worked off gradually, a one-pole pull of `1 - e^(-gain x dt)` per frame (so its pace does not depend on
 * the frame rate), capped at [maxSlew] of the frame's own advance: the drawn motion never speeds up or slows down by
 * more than that, and so never stops or steps back. A fresh reading lags the truth by up to one frame (it was stored
 * somewhere since the last frame), so the drawn position runs about half a frame behind the audio; the presentation
 * lead below covers more than that.
 *
 * What it does at once, with no slewing: snap to a fresh reading further than [snapMs] from the prediction (a seek: a
 * real discontinuity), and to the reading on a change of queue item, of playing or paused, or a gap between frames over
 * [MAX_GAP_NANOS]. The reading a snap takes may itself be stale (up to one refresh old), so the first fresh reading
 * after a snap that is ahead of the prediction is taken at once too ([SNAP_SETTLE]); one behind it is slewed. A speed
 * change only bends the slope: the position is continuous through it (a tempo fader drag changes the speed every few
 * frames, while the reading is stale). Paused, it draws exactly the reported position. [lastSnap] tells why the last
 * frame snapped, for diagnostics.
 *
 * **Loops.** While [ClockSample.looping] the position is a point on the loop `[loopInMs, loopOutMs)`, not on a line: the
 * prediction wraps from the end of the loop to its start, and the error of a fresh reading is taken modulo the loop's
 * length, in (-length/2, length/2], so a reading that wrapped a moment before the prediction does (or after) is a small
 * error, not one of almost a whole loop. A wrap is therefore never a snap, and the drawn motion goes on at the playback
 * speed across it. This holds for loops shorter than the readings' refresh: a fresh reading is the audio's position a
 * frame ago whatever the loop's length, and what is worked off is its phase on the loop. Entering and leaving a loop
 * are told by the flag alone (the flag and the reading come from the same pass of the player, see [LoopClockSource]).
 * One thing it cannot know: a loop left with the position within one refresh of its end, which can show as one snap.
 *
 * The drawn frame appears on screen a little after it is computed: [presentLeadNanos] (about one frame) moves the
 * position ahead by that much, at the playback speed, so the picture matches the audio at its display time.
 *
 * Invalid readings (negative or unset position, a speed that is NaN, infinite or not positive) are ignored: the
 * position runs on from the prediction at the last good speed. Not thread-safe: one drawing thread feeds and reads it.
 *
 * @param presentLeadNanos how far ahead of the clock the frame is drawn, to match its display time.
 * @param gainPerSecond how fast the position is pulled toward a fresh reading (per second); small enough that reading
 *   jitter of ten ms or so does not reach the drawn position.
 * @param snapMs the distance of a fresh reading from the prediction at which the position jumps instead of slewing.
 * @param maxSlew the largest correction per frame, as a fraction of the frame's own advance (0.1: the drawn motion runs
 *   between 0.9x and 1.1x the playback speed while it catches up).
 */
class PositionSmoother(
    private val presentLeadNanos: Long = DEFAULT_PRESENT_LEAD_NANOS,
    private val gainPerSecond: Double = DEFAULT_GAIN_PER_SECOND,
    private val snapMs: Double = SNAP_MS,
    private val maxSlew: Double = MAX_SLEW,
) {
    private var started = false
    private var lastFrameNanos = 0L
    private var lastItem = 0L
    private var lastPlaying = false
    private var speed = 1.0

    /** The smoothed position, before the presentation lead, ms. */
    private var position = 0.0

    /** The last reading taken in: a reading equal to it is stale. */
    private var lastReading = Long.MIN_VALUE

    /** What is left to work off of the last fresh reading's distance from the prediction, ms. */
    private var pendingErrorMs = 0.0

    /** True from a snap until the next fresh reading (the snap's own reading may have been stale). */
    private var settling = false

    /** The loop the current frame is in: `[loopIn, loopIn + loopLength)`, or no loop when [loopLength] is zero. */
    private var loopIn = 0.0
    private var loopLength = 0.0

    /** Why the last [advance] snapped: one of the `SNAP_` constants, [SNAP_NONE] when it predicted or slewed. */
    var lastSnap: Int = SNAP_NONE
        private set

    /**
     * The position to draw the frame timed [frameNanos] (a monotonic clock, such as `Choreographer`'s frame time) at,
     * in ms, from the current [sample]. Allocation-free.
     */
    fun advance(frameNanos: Long, sample: ClockSample): Double {
        lastSnap = SNAP_NONE
        val loopSpan = sample.loopOutMs - sample.loopInMs
        if (sample.looping && loopSpan > 0.0 && loopSpan.isFinite() && sample.loopInMs.isFinite()) {
            loopIn = sample.loopInMs
            loopLength = loopSpan
        } else {
            loopLength = 0.0
        }
        val reading = sample.positionMs
        val hasReading = reading >= 0
        val reportedSpeed = sample.speed.toDouble()
        val speedValid = reportedSpeed.isFinite() && reportedSpeed > 0.0

        if (!started) {
            if (!hasReading) return 0.0
            started = true
            lastFrameNanos = frameNanos
            lastItem = sample.queueItemId
            lastPlaying = sample.playing
            speed = if (speedValid) reportedSpeed else 1.0
            snapTo(reading, SNAP_START)
            return drawn(sample.playing)
        }

        val gapNanos = frameNanos - lastFrameNanos
        val dtSeconds = if (gapNanos > 0) gapNanos / NANOS_PER_SECOND else 0.0
        val breakReason = when {
            sample.queueItemId != lastItem -> SNAP_ITEM
            sample.playing != lastPlaying -> SNAP_PLAYING
            gapNanos > MAX_GAP_NANOS -> SNAP_GAP
            else -> SNAP_NONE
        }
        // A new speed only bends the slope: the position runs on from where it was.
        if (speedValid) speed = reportedSpeed
        val advanceMs = dtSeconds * speed * MS_PER_SECOND
        lastFrameNanos = max(lastFrameNanos, frameNanos)
        lastItem = sample.queueItemId
        lastPlaying = sample.playing

        if (!sample.playing) {
            if (hasReading) {
                position = reading.toDouble()
                lastReading = reading
            }
            pendingErrorMs = 0.0
            return drawn(false)
        }

        if (breakReason != SNAP_NONE && hasReading) {
            snapTo(reading, breakReason)
            return drawn(true)
        }

        val predicted = wrapped(position + advanceMs)
        if (hasReading && reading != lastReading) {
            lastReading = reading
            val error = errorOf(reading - predicted)
            val wasSettling = settling
            settling = false
            when {
                error > snapMs -> return snapped(reading, SNAP_JUMP_AHEAD)
                error < -snapMs -> return snapped(reading, SNAP_JUMP_BACK)
                wasSettling && error > 0.0 -> return snapped(reading, SNAP_SETTLE)
                else -> pendingErrorMs = error
            }
        }
        val limit = maxSlew * advanceMs
        val step = (pendingErrorMs * (1.0 - exp(-gainPerSecond * dtSeconds))).coerceIn(-limit, limit)
        pendingErrorMs -= step
        position = wrapped(predicted + step)
        return drawn(true)
    }

    /** [x] put back on the current loop, or [x] itself when there is none. */
    private fun wrapped(x: Double): Double =
        if (loopLength > 0.0) loopIn + (x - loopIn).mod(loopLength) else x

    /** [error] (reading minus prediction) as the shortest way round the current loop: in (-length/2, length/2]. */
    private fun errorOf(error: Double): Double {
        if (loopLength <= 0.0) return error
        val half = loopLength / 2
        var e = error % loopLength
        if (e > half) e -= loopLength else if (e <= -half) e += loopLength
        return e
    }

    /** Forgets everything: the next [advance] takes its reading as it is (a new session of the screen). */
    fun reset() {
        started = false
        speed = 1.0
        pendingErrorMs = 0.0
        settling = false
        lastReading = Long.MIN_VALUE
    }

    private fun snapped(reading: Long, reason: Int): Double {
        snapTo(reading, reason)
        settling = false
        return drawn(true)
    }

    private fun snapTo(reading: Long, reason: Int) {
        position = reading.toDouble()
        lastReading = reading
        pendingErrorMs = 0.0
        settling = true
        lastSnap = reason
    }

    private fun drawn(playing: Boolean): Double =
        if (playing) wrapped(position + presentLeadNanos / NANOS_PER_MS * speed) else position

    companion object {
        /** About one frame at 60 Hz, ns. */
        const val DEFAULT_PRESENT_LEAD_NANOS: Long = 16_666_667L

        /**
         * The pull toward a fresh reading, per second: an error is worked off with a time constant of one second, so
         * ten ms of jitter stays under 2 ms.
         */
        const val DEFAULT_GAIN_PER_SECOND: Double = 1.0

        /**
         * A fresh reading this far (ms) from the prediction is a jump (a seek), not jitter. Fresh readings are at most
         * a frame or two old, so anything near this is a real discontinuity; smaller errors are slewed at [MAX_SLEW].
         */
        const val SNAP_MS: Double = 150.0

        /** The largest correction per frame, as a fraction of the frame's own advance. */
        const val MAX_SLEW: Double = 0.1

        /** A gap between frames beyond this (ns) ends the prediction: the reading alone is trusted. */
        const val MAX_GAP_NANOS: Long = 1_000_000_000L

        /** [lastSnap]: no snap, the position was predicted or slewed (or the player is paused). */
        const val SNAP_NONE: Int = 0

        /** [lastSnap]: the first reading after a start or [reset]. */
        const val SNAP_START: Int = 1

        /** [lastSnap]: another queue item. */
        const val SNAP_ITEM: Int = 2

        /** [lastSnap]: playback started or stopped. */
        const val SNAP_PLAYING: Int = 3

        /** [lastSnap]: a gap between frames over [MAX_GAP_NANOS]. */
        const val SNAP_GAP: Int = 4

        /** [lastSnap]: a fresh reading more than the snap distance ahead (a seek forward). */
        const val SNAP_JUMP_AHEAD: Int = 5

        /** [lastSnap]: a fresh reading more than the snap distance behind (a seek back, a stall). */
        const val SNAP_JUMP_BACK: Int = 6

        /** [lastSnap]: the first fresh reading after a snap was ahead of the prediction (the snap's was stale). */
        const val SNAP_SETTLE: Int = 7

        /** How many `SNAP_` values there are (for a per-reason count). */
        const val SNAP_REASONS: Int = 8

        private const val NANOS_PER_SECOND = 1e9
        private const val NANOS_PER_MS = 1e6
        private const val MS_PER_SECOND = 1_000.0
    }
}
