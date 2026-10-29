package io.sonora.core.audiodsp.dub

import kotlin.math.abs
import kotlin.math.ceil

/**
 * The last [capacityFrames] frames of the stage's input, addressed by absolute source frame (the stage's count of
 * frames handed down since configure). The latency-compensated THROW reads it to send the audio that was already
 * heard when the finger landed. Always written while the stage is hosted.
 *
 * Non-finite input (or beyond +-1e6) is stored as silence. Allocates only in the constructor; [write], [get] and
 * [reset] are real-time safe. Not thread-safe.
 */
class HistoryRing(val channelCount: Int, val capacityFrames: Int) {
    init {
        require(channelCount in 1..2) { "channelCount must be 1 or 2: $channelCount" }
        require(capacityFrames > 0) { "capacityFrames must be positive: $capacityFrames" }
    }

    private val data = FloatArray(capacityFrames * channelCount)
    private var firstFrame = 0L

    /** The absolute index of the last frame written; `firstFrame - 1` before the first write. */
    var newestFrame: Long = -1L
        private set

    /** The oldest frame still held. */
    val oldestFrame: Long get() = maxOf(firstFrame, newestFrame - capacityFrames + 1)

    fun contains(frame: Long): Boolean = frame in oldestFrame..newestFrame

    /** Appends the next frame; a mono ring ignores [right]. */
    fun write(left: Float, right: Float) {
        val frame = newestFrame + 1
        val base = Math.floorMod(frame, capacityFrames.toLong()).toInt() * channelCount
        data[base] = sanitize(left)
        if (channelCount == 2) data[base + 1] = sanitize(right)
        newestFrame = frame
    }

    /** The sample of [frame] on [channel]; 0 outside the held window or for a channel this ring does not have. */
    fun get(frame: Long, channel: Int): Float {
        if (channel !in 0 until channelCount || !contains(frame)) return 0f
        return data[Math.floorMod(frame, capacityFrames.toLong()).toInt() * channelCount + channel]
    }

    /** Forgets every frame; the next [write] is frame [nextFrame]. */
    fun reset(nextFrame: Long = 0L) {
        data.fill(0f)
        firstFrame = nextFrame
        newestFrame = nextFrame - 1
    }

    private fun sanitize(x: Float): Float = if (abs(x) <= MAX_INPUT) x else 0f

    companion object {
        /** How far back a touch can reach: more than the 125 ms buffer plus touch and wake-up latency. */
        const val HISTORY_SECONDS = 0.4
        private const val MAX_INPUT = 1e6f

        fun capacityFor(sampleRate: Int, seconds: Double = HISTORY_SECONDS): Int =
            ceil(sampleRate * seconds - 1e-9).toInt().coerceAtLeast(1)
    }
}
