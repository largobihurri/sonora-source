package io.sonora.feature.dj.waveform

import io.sonora.core.domain.playback.DjLoopState
import kotlin.math.max
import kotlin.math.min

/** How far the loop has got: only an In marked, closed and waiting for the audio to reach Out, repeating, or left with Exit. */
enum class LoopPhase { MARKING, ARMED, LOOPING, EXITED }

/**
 * The loop to draw on the waveforms, in the time of the playback clock (the time the playhead is drawn in): from
 * [inMs] to [outMs], or, while only an In is marked ([outMs] `null`), to the playhead.
 */
data class LoopRegion(val inMs: Double, val outMs: Double?, val phase: LoopPhase) {

    /** Where the region ends with the playhead at [positionMs]: its Out, or, still being marked, the playhead (never before In). */
    fun endAt(positionMs: Double): Double = outMs ?: max(positionMs, inMs)

    companion object {
        /** The region of [state], `null` without an In. */
        fun of(state: DjLoopState): LoopRegion? {
            val start = state.inMs ?: return null
            val phase = when {
                state.markingIn -> LoopPhase.MARKING
                state.looping -> LoopPhase.LOOPING
                state.armed -> LoopPhase.ARMED
                else -> LoopPhase.EXITED
            }
            return LoopRegion(start, state.outMs, phase)
        }
    }
}

/** The pure placement of the loop region on the scrolling waveform and on the overview, in pixels (no allocation, safe per frame). */
object LoopGeometry {

    /** The x in the scrolling view of [ms], [leftPx] being the absolute pixel at the view's left edge and [pxPerMs] the scale. */
    fun x(ms: Double, leftPx: Double, pxPerMs: Double): Float = (ms * pxPerMs - leftPx).toFloat()

    /** Whether the span [x0], [x1] (x0 <= x1) shows any part in a view [widthPx] wide. */
    fun visible(x0: Float, x1: Float, widthPx: Float): Boolean = x1 >= 0f && x0 <= widthPx

    /** [x] kept inside a view [widthPx] wide. */
    fun clamped(x: Float, widthPx: Float): Float = min(max(x, 0f), widthPx)

    /** Whether an edge at [x] (with its handle) is in a view [widthPx] wide. */
    fun edgeVisible(x: Float, widthPx: Float): Boolean = x >= 0f && x <= widthPx

    /** The left end, on an overview [widthPx] wide, of the loop from [inMs] on a track [durationMs] long. */
    fun overviewLeft(inMs: Double, durationMs: Double, widthPx: Float): Float = CueMarkerGeometry.overviewX(inMs, durationMs, widthPx)

    /**
     * The right end of the loop to [outMs] on the overview: where Out falls, at least [minWidthPx] from the left end so a
     * very short loop is still a visible mark, never past the strip.
     */
    fun overviewRight(inMs: Double, outMs: Double, durationMs: Double, widthPx: Float, minWidthPx: Float): Float {
        val left = overviewLeft(inMs, durationMs, widthPx)
        val right = CueMarkerGeometry.overviewX(outMs, durationMs, widthPx)
        return min(widthPx, max(right, left + minWidthPx))
    }

    /** The alpha of the region's fill: strongest while the loop repeats, fainter the less active it is. */
    fun fillAlpha(phase: LoopPhase): Float = when (phase) {
        LoopPhase.LOOPING -> 0.30f
        LoopPhase.ARMED -> 0.20f
        LoopPhase.MARKING -> 0.14f
        LoopPhase.EXITED -> 0.10f
    }
}
