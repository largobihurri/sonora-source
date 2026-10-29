package io.sonora.core.audiodsp.dub

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Where a THROW sends from. */
enum class ThrowMode {
    /** From the frame that was heard when the finger landed: the first repeat lands one delay after it. */
    COMPENSATED,

    /** From the frontier at once: the delay is too short to reach back to the heard frame. */
    FRONTIER,
}

/**
 * The latency-compensated THROW send: a per-frame send gain over the [history] window, read by the echo as audio
 * written in the past ([DelayOverlay]).
 *
 * When a press arrives, the audio from the heard press frame up to the frontier was already handed down, so it
 * cannot go through the normal send. Instead, this ring marks those frames (and every frame after them while the pad
 * is held) with the send gain; the echo's taps read `history x gain` next to its own ring, as if that audio had been
 * written at its own frame, so its first repeat lands exactly one delay after the heard press. Nothing is written
 * into the echo loop until a frame leaves this window (the echo then commits it where it belongs), so a release
 * heard before the frontier can still fade out (5 ms) the queued part after it, and repeats already circulating in
 * the loop (from earlier throws) are never touched.
 *
 * Gains: a 5 ms linear fade-in from the press frame, a 5 ms fade-out from the release frame. A press while a previous
 * fade-out is still running keeps the louder of the two gains per frame; a press while held is ignored.
 *
 * Fallback: when the echo delay is no longer than the depth from the heard frame to the frontier, the repeat would
 * be due before the frontier, so the throw sends from the frontier ([ThrowMode.FRONTIER]), and so does its release.
 *
 * Protocol, per frame: `history.write(...)`, then [advance], then the echo's `processFrame(..., this)`. Pad events
 * ([press], [release]) are applied between frames. Allocates only in the constructor; every call is real-time safe
 * (a press or release touches at most [capacityFrames] gains). Not thread-safe.
 */
class PendingSendRing(val history: HistoryRing, sampleRate: Int) : DelayOverlay {
    init {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
    }

    /** The window length, the history's: a release can reach this far back and frames leave in this order. */
    val capacityFrames: Int = history.capacityFrames

    /** Frames of each fade. */
    val rampFrames: Int = maxOf(1, (RAMP_SECONDS * sampleRate).roundToInt())

    private val gains = FloatArray(capacityFrames)

    /** The last frame [advance] gave a gain to. */
    var newestFrame: Long = history.newestFrame
        private set

    /** True from a press until its fade-out has been written. */
    var isActive: Boolean = false
        private set

    /** The mode of the current (or last) throw. */
    var mode: ThrowMode = ThrowMode.FRONTIER
        private set

    /** The first frame of the current (or last) throw. */
    var pressFrame: Long = 0L
        private set

    /** The release frame of the current throw; meaningful while [isReleased]. */
    var releaseFrame: Long = 0L
        private set

    var isReleased: Boolean = false
        private set

    /** Gives the frame [history] has just received its send gain. */
    fun advance() {
        val frame = history.newestFrame
        if (frame != newestFrame + 1) clear()
        newestFrame = frame
        gains[slot(frame)] = if (isActive) segmentGain(frame) else 0f
        if (isActive && isReleased && frame + 1 >= releaseFrame + rampFrames) isActive = false
    }

    /**
     * Starts a throw from [heardFrame] (from [PresentationClock.heardFrameAt]; NaN means the frontier), clamped to
     * the history window, against the echo's current [delayFrames]. Ignored while a throw is held.
     */
    fun press(heardFrame: Double, delayFrames: Double): ThrowMode {
        if (isActive && !isReleased) return mode
        val frontier = newestFrame + 1
        val oldest = minOf(frontier, maxOf(history.oldestFrame, newestFrame - capacityFrames + 1))
        val heard = if (heardFrame.isFinite()) heardFrame.roundToLong().coerceIn(oldest, frontier) else frontier
        mode = if (delayFrames > (frontier - heard).toDouble()) ThrowMode.COMPENSATED else ThrowMode.FRONTIER
        pressFrame = if (mode == ThrowMode.COMPENSATED) heard else frontier
        isReleased = false
        isActive = true
        var k = pressFrame
        while (k <= newestFrame) {
            val i = slot(k)
            gains[i] = maxOf(gains[i], fadeIn(k))
            k++
        }
        return mode
    }

    /**
     * Releases the held throw at [heardFrame] (clamped between the press frame and the frontier; NaN, or a frontier
     * throw, means the frontier): the frames from there on fade out over 5 ms. Earlier frames are untouched.
     */
    fun release(heardFrame: Double) {
        if (!isActive || isReleased) return
        val frontier = newestFrame + 1
        val heard = if (mode == ThrowMode.FRONTIER || !heardFrame.isFinite()) frontier else heardFrame.roundToLong()
        releaseFrame = heard.coerceIn(pressFrame, frontier)
        isReleased = true
        var k = releaseFrame
        while (k <= newestFrame) {
            val i = slot(k)
            gains[i] = minOf(gains[i], fadeOut(k))
            k++
        }
        if (frontier >= releaseFrame + rampFrames) isActive = false
    }

    /** Releases at the frontier (FREEZE engaging, a pad cancel, release-all). */
    fun releaseAtFrontier() = release(Double.NaN)

    /** Drops every pending send (a re-prepare, or the history restarting). */
    fun clear() {
        gains.fill(0f)
        isActive = false
        isReleased = false
    }

    /** The pending send of [frame] on [channel]: the history sample times its gain; 0 outside the window. */
    fun sendAt(frame: Long, channel: Int): Float {
        if (frame > newestFrame || frame <= newestFrame - capacityFrames || !history.contains(frame)) return 0f
        val g = gains[slot(frame)]
        return if (g == 0f) 0f else history.get(frame, channel) * g
    }

    /** [sendAt] by age: the frame [framesAgo] frames before the newest one. */
    override fun tap(framesAgo: Int, channel: Int): Float {
        if (framesAgo < 0 || framesAgo >= capacityFrames) return 0f
        return sendAt(newestFrame - framesAgo, channel)
    }

    private fun segmentGain(frame: Long): Float {
        val fadeIn = fadeIn(frame)
        return if (isReleased) minOf(fadeIn, fadeOut(frame)) else fadeIn
    }

    private fun fadeIn(frame: Long): Float =
        if (frame < pressFrame) 0f else minOf(1f, (frame - pressFrame + 1).toFloat() / rampFrames)

    private fun fadeOut(frame: Long): Float =
        if (frame < releaseFrame) 1f else ((releaseFrame + rampFrames - frame).toFloat() / rampFrames).coerceIn(0f, 1f)

    private fun slot(frame: Long): Int = Math.floorMod(frame, capacityFrames.toLong()).toInt()

    companion object {
        const val RAMP_SECONDS = 0.005
    }
}
