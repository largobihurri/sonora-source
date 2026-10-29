package io.sonora.core.audiodsp.dub

/**
 * Converts between the two time bases of the latency-compensated THROW: wall time (a touch is stamped in
 * monotonic nanoseconds, the same base as `System.nanoTime` and `MotionEvent` uptime on the device) and source
 * frames (what the stage counts while it hands audio down).
 *
 * At each buffer the owner [record]s the frames handed down so far, the source frame being presented (heard) now,
 * and the playback speed. Then the frame heard at an earlier instant t is
 * `presentedFrame - (now - t) * sampleRate * speed`, since source frames are presented `speed` times faster than the
 * wall clock. [queuedFrames] is the depth between the frontier and what is heard.
 *
 * Pure arithmetic: allocation-free, no locks. Not thread-safe (the audio thread owns it).
 */
class PresentationClock(val sampleRate: Int) {
    private var hasRecord = false
    private var nowNanos = 0L

    var framesHandedDown: Long = 0L
        private set

    /** The source frame heard at the last record, never ahead of the frames handed down. */
    var presentedFrame: Double = 0.0
        private set

    /** The playback speed at the last record; 1.0 when an invalid value was given. */
    var speed: Double = 1.0
        private set

    val queuedFrames: Double get() = framesHandedDown - presentedFrame

    /**
     * A non-finite [presentedFrame] counts as nothing queued; one ahead of [framesHandedDown] is clamped to it; a
     * non-finite or non-positive [speed] counts as 1.0.
     */
    fun record(nowNanos: Long, framesHandedDown: Long, presentedFrame: Double, speed: Double) {
        this.nowNanos = nowNanos
        this.framesHandedDown = framesHandedDown
        val handed = framesHandedDown.toDouble()
        this.presentedFrame = if (presentedFrame.isFinite()) minOf(presentedFrame, handed) else handed
        this.speed = if (speed > 0.0 && speed.isFinite()) speed else 1.0
        hasRecord = true
    }

    /**
     * The source frame that was heard at [eventNanos]; NaN before the first record. A touch stamped after the last
     * record (clock skew) is heard at the presented frame.
     */
    fun heardFrameAt(eventNanos: Long): Double {
        if (!hasRecord) return Double.NaN
        val ageSeconds = maxOf(0L, nowNanos - eventNanos) * 1e-9
        return presentedFrame - ageSeconds * sampleRate * speed
    }

    companion object {
        /**
         * The source frame presented at the reported [positionUs] (media time), given that frame [anchorFrame] was at
         * media time [anchorPositionUs] (recorded when the stage started counting after configure or a flush).
         */
        fun presentedFrame(positionUs: Long, anchorPositionUs: Long, anchorFrame: Long, sampleRate: Int): Double =
            anchorFrame + (positionUs - anchorPositionUs) * sampleRate / 1e6
    }
}
