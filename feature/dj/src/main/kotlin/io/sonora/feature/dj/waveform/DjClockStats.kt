package io.sonora.feature.dj.waveform

import io.sonora.core.domain.playback.ClockReadFailure
import io.sonora.core.domain.playback.PositionSmoother
import java.util.Locale
import kotlin.math.ceil

/**
 * One window (a second) of the DJ screen's clock, counted for the `SonoraDjClock` log line ([DjClockLog]): frames
 * drawn, clock reads that worked or failed (by [ClockReadFailure] reason, a queue item mismatch with both ids, no
 * position), how often the player's reading was fresh and the longest it stood still, the smoother's snaps by reason
 * ([PositionSmoother.lastSnap]), the drawn position's step per frame (largest, smallest, 90th percentile, back steps,
 * still frames), the longest frame gap and the waveform tiles missing when drawn.
 *
 * Plain fields and one preallocated array, so counting allocates nothing per frame; only [summary] allocates, once per
 * window. Main thread only (the frame loop and the draw phase).
 */
class DjClockStats(deltaCapacity: Int = DELTA_CAPACITY) {
    var frames: Int = 0
        private set
    var readsOk: Int = 0
        private set

    /** Failed reads, indexed by [ClockReadFailure] reason. */
    val readFailures = IntArray(ClockReadFailure.UNKNOWN + 1)
    var itemMismatches: Int = 0
        private set

    /** The ids of the last mismatch: the item the screen shows and the one the clock read. */
    var mismatchUiItem: Long = 0L
        private set
    var mismatchClockItem: Long = 0L
        private set
    var noPosition: Int = 0
        private set

    /** Smoother snaps, indexed by `PositionSmoother.SNAP_` reason ([PositionSmoother.SNAP_NONE] unused). */
    val snaps = IntArray(PositionSmoother.SNAP_REASONS)

    /** Readings that differed from the one before (the player refreshed its position). */
    var freshReadings: Int = 0
        private set

    /** The longest time between two fresh readings that ended in this window, ms of frame time. */
    var longestStaleMs: Double = 0.0
        private set
    var tileMisses: Int = 0
        private set
    var backSteps: Int = 0
        private set
    var stillFrames: Int = 0
        private set
    var longestFrameGapMs: Double = 0.0
        private set

    private val deltas = DoubleArray(deltaCapacity)
    private var deltaCount = 0
    private var speed = 1f
    private var windowStartNanos = UNSET
    private var lastFrameNanos = UNSET
    private var lastDrawnMs = Double.NaN
    private var lastRaw = Long.MIN_VALUE
    private var lastFreshNanos = UNSET

    /** A new frame loop begins: forgets the last frame, drawn position and reading, and starts a new window. */
    fun restart() {
        lastFrameNanos = UNSET
        lastDrawnMs = Double.NaN
        lastRaw = Long.MIN_VALUE
        lastFreshNanos = UNSET
        clear(UNSET)
    }

    /** The clock read [positionMs] at [speed] for the frame timed [frameNanos]. */
    fun onReadOk(positionMs: Long, speed: Float, frameNanos: Long) {
        readsOk++
        this.speed = speed
        if (positionMs != lastRaw) {
            freshReadings++
            if (lastFreshNanos != UNSET) {
                val staleMs = (frameNanos - lastFreshNanos) / NANOS_PER_MS
                if (staleMs > longestStaleMs) longestStaleMs = staleMs
            }
            lastRaw = positionMs
            lastFreshNanos = frameNanos
        }
    }

    /** The clock said no, for [reason] (a [ClockReadFailure] value). */
    fun onReadFailed(reason: Int) {
        readFailures[reason.coerceIn(0, ClockReadFailure.UNKNOWN)]++
    }

    /** The clock read another queue item ([clockItem]) than the one the screen shows ([uiItem]). */
    fun onItemMismatch(uiItem: Long, clockItem: Long) {
        itemMismatches++
        mismatchUiItem = uiItem
        mismatchClockItem = clockItem
    }

    /** The clock read no position. */
    fun onNoPosition() {
        noPosition++
    }

    /** The smoother's [PositionSmoother.lastSnap] for this frame. */
    fun onSnap(reason: Int) {
        if (reason > PositionSmoother.SNAP_NONE && reason < snaps.size) snaps[reason]++
    }

    /** A frame timed [frameNanos] was drawn at [drawnMs]. */
    fun onFrame(frameNanos: Long, drawnMs: Double) {
        frames++
        if (windowStartNanos == UNSET) windowStartNanos = frameNanos
        if (lastFrameNanos != UNSET) {
            val gapMs = (frameNanos - lastFrameNanos) / NANOS_PER_MS
            if (gapMs > longestFrameGapMs) longestFrameGapMs = gapMs
        }
        if (!lastDrawnMs.isNaN()) {
            val delta = drawnMs - lastDrawnMs
            if (delta < 0.0) backSteps++ else if (delta == 0.0) stillFrames++
            if (deltaCount < deltas.size) deltas[deltaCount++] = delta
        }
        lastFrameNanos = frameNanos
        lastDrawnMs = drawnMs
    }

    /** A waveform frame was drawn with a visible tile not rendered yet. */
    fun onTileMiss() {
        tileMisses++
    }

    /** Whether a second has passed since the window began, at [frameNanos]. */
    fun due(frameNanos: Long): Boolean = windowStartNanos != UNSET && frameNanos - windowStartNanos >= WINDOW_NANOS

    /** Starts the next window at [frameNanos] (the counts go to zero; the last frame and reading carry on). */
    fun clear(frameNanos: Long) {
        frames = 0
        readsOk = 0
        readFailures.fill(0)
        itemMismatches = 0
        noPosition = 0
        snaps.fill(0)
        freshReadings = 0
        longestStaleMs = 0.0
        tileMisses = 0
        backSteps = 0
        stillFrames = 0
        longestFrameGapMs = 0.0
        deltaCount = 0
        windowStartNanos = frameNanos
    }

    fun maxDeltaMs(): Double {
        if (deltaCount == 0) return Double.NaN
        var max = deltas[0]
        for (i in 1 until deltaCount) if (deltas[i] > max) max = deltas[i]
        return max
    }

    fun minDeltaMs(): Double {
        if (deltaCount == 0) return Double.NaN
        var min = deltas[0]
        for (i in 1 until deltaCount) if (deltas[i] < min) min = deltas[i]
        return min
    }

    /** The 90th percentile of the steps (nearest rank). Sorts the window's steps in place. */
    fun p90DeltaMs(): Double {
        if (deltaCount == 0) return Double.NaN
        deltas.sort(0, deltaCount)
        return deltas[ceil(P90 * deltaCount).toInt().coerceIn(1, deltaCount) - 1]
    }

    /** The window as one log line (allocates: call once per window). */
    fun summary(): String {
        val f = readFailures
        val s = snaps
        val mismatch = if (itemMismatches > 0) "$itemMismatches(ui=$mismatchUiItem,clock=$mismatchClockItem)" else "0"
        return "frames=$frames gapMax=${ms(longestFrameGapMs)} speed=${String.format(Locale.ROOT, "%.3f", speed)}" +
            " | clock ok=$readsOk noPlayer=${f[ClockReadFailure.NO_PLAYER]} thread=${f[ClockReadFailure.WRONG_THREAD]}" +
            " noItem=${f[ClockReadFailure.NO_ITEM]} unknown=${f[ClockReadFailure.UNKNOWN]} mismatch=$mismatch" +
            " noPos=$noPosition | raw fresh=$freshReadings staleMax=${ms(longestStaleMs)}" +
            " | snaps start=${s[PositionSmoother.SNAP_START]} item=${s[PositionSmoother.SNAP_ITEM]}" +
            " play=${s[PositionSmoother.SNAP_PLAYING]} gap=${s[PositionSmoother.SNAP_GAP]}" +
            " ahead=${s[PositionSmoother.SNAP_JUMP_AHEAD]} back=${s[PositionSmoother.SNAP_JUMP_BACK]}" +
            " settle=${s[PositionSmoother.SNAP_SETTLE]}" +
            " | step max=${ms(maxDeltaMs())} min=${ms(minDeltaMs())} p90=${ms(p90DeltaMs())}" +
            " backSteps=$backSteps still=$stillFrames | tileMiss=$tileMisses"
    }

    private fun ms(value: Double): String = if (value.isNaN()) "-" else String.format(Locale.ROOT, "%.1f", value)

    companion object {
        /** Steps kept per window: a second at 120 Hz, with room to spare. */
        const val DELTA_CAPACITY: Int = 256

        private const val WINDOW_NANOS = 1_000_000_000L
        private const val NANOS_PER_MS = 1e6
        private const val P90 = 0.9
        private const val UNSET = Long.MIN_VALUE
    }
}
