package io.sonora.feature.dj.waveform

/** The maths of the overview bar (the whole track in one strip): played fraction and seeking by touch. All results are clamped. */
object OverviewGeometry {

    /** The share of the track played at [positionMs], 0..1; 0 for an unknown duration or position. */
    fun playedFraction(positionMs: Double, durationMs: Double): Double {
        if (!(durationMs > 0) || positionMs.isNaN() || durationMs.isInfinite()) return 0.0
        return (positionMs / durationMs).coerceIn(0.0, 1.0)
    }

    /** The time at [fraction] of a track [durationMs] long, clamped to `[0, durationMs]`; 0 for an unknown duration. */
    fun msAt(fraction: Double, durationMs: Double): Double {
        if (!(durationMs > 0) || fraction.isNaN()) return 0.0
        return fraction.coerceIn(0.0, 1.0) * durationMs
    }

    /** The fraction 0..1 of the bar at the touch position [xPx] on a bar [widthPx] wide; 0 for an empty bar. */
    fun fractionAt(xPx: Double, widthPx: Double): Double {
        if (!(widthPx > 0) || xPx.isNaN()) return 0.0
        return (xPx / widthPx).coerceIn(0.0, 1.0)
    }
}
