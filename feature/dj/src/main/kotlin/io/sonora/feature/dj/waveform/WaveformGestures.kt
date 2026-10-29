package io.sonora.feature.dj.waveform

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * Scrubbing the scrolling waveform with one finger: the waveform follows the finger (as a record under the hand), so a
 * drag to the right brings earlier music under the fixed playhead. The drawn position is previewed while dragging and
 * sought once on release.
 */
object DragSeek {

    /**
     * The playhead position after dragging the waveform [dragPx] (positive to the right) from [startMs], at [pxPerMs]
     * (see [WaveformScale.pxPerMs]), clamped to `[0, durationMs]`. With no scale the start is kept (clamped).
     */
    fun positionMs(startMs: Double, dragPx: Double, pxPerMs: Double, durationMs: Double): Double {
        val duration = if (durationMs > 0) durationMs else 0.0
        val start = if (startMs.isNaN()) 0.0 else startMs
        val moved = if (pxPerMs > 0 && pxPerMs.isFinite() && dragPx.isFinite()) start - dragPx / pxPerMs else start
        return moved.coerceIn(0.0, duration)
    }

    /** The position sought on release: the nearest millisecond (0 for NaN). */
    fun seekMs(positionMs: Double): Long = if (positionMs.isNaN()) 0L else positionMs.roundToLong()
}

/**
 * Pinch zoom of the scrolling waveform. The gesture's accumulated scale divides the span the gesture started with, and
 * the result snaps to the nearest [WaveformZoom] on a log scale (ratios, since the levels double): spreading the
 * fingers twice as far turns 8 s into 4 s. Stateless, so pinching back returns to where it began.
 */
object ZoomSnap {

    /** The zoom for a pinch that started at [start] and has scaled by [scale] (above 1 spreads the fingers). */
    fun snapped(start: WaveformZoom, scale: Float): WaveformZoom {
        if (!(scale > 0f) || scale.isInfinite()) return start
        val target = ln(start.visibleMs / scale)
        var best = start
        var bestDistance = Double.MAX_VALUE
        for (zoom in WaveformZoom.entries) {
            val distance = abs(ln(zoom.visibleMs) - target)
            if (distance < bestDistance) {
                best = zoom
                bestDistance = distance
            }
        }
        return best
    }
}

/** Seeking by tapping or dragging on the overview strip (the whole track in one bar). */
object OverviewSeek {

    /** The time under the touch at [xPx] on a strip [widthPx] wide, to the nearest ms, clamped to the track. */
    fun msAt(xPx: Double, widthPx: Double, durationMs: Long): Long {
        if (durationMs <= 0) return 0L
        val fraction = OverviewGeometry.fractionAt(xPx, widthPx)
        return OverviewGeometry.msAt(fraction, durationMs.toDouble()).roundToLong()
    }
}
