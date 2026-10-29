package io.sonora.feature.dj.waveform

/** The pure placement of cue markers on the scrolling waveform and on the overview; all results in pixels. */
object CueMarkerGeometry {

    /** The x of a marker at [positionMs] on an overview [widthPx] wide for a track [durationMs] long (clamped to the bar). */
    fun overviewX(positionMs: Double, durationMs: Double, widthPx: Float): Float =
        (OverviewGeometry.playedFraction(positionMs, durationMs) * widthPx).toFloat()

    /** Whether a flag whose pole is at [x] and that extends [flagWidthPx] to the right shows any part in a view [viewWidthPx] wide. */
    fun flagVisible(x: Float, flagWidthPx: Float, viewWidthPx: Float): Boolean = x + flagWidthPx > 0f && x < viewWidthPx

    /** Whether a label at [x] has room after the last one drawn, which ended at [previousRight]: they must not overlap. */
    fun labelFits(previousRight: Float, x: Float, gapPx: Float): Boolean = x >= previousRight + gapPx

    /** Lettering on a flag of [argb]: [DARK_INK] on a light colour, [LIGHT_INK] on a dark one (by perceived brightness). */
    fun inkFor(argb: Int): Int {
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        val brightness = (RED_WEIGHT * red + GREEN_WEIGHT * green + BLUE_WEIGHT * blue) / 1000
        return if (brightness >= LIGHT_THRESHOLD) DARK_INK else LIGHT_INK
    }

    const val DARK_INK: Int = 0xE6000000.toInt()
    const val LIGHT_INK: Int = 0xFFFFFFFF.toInt()

    private const val RED_WEIGHT = 299
    private const val GREEN_WEIGHT = 587
    private const val BLUE_WEIGHT = 114
    private const val LIGHT_THRESHOLD = 150
}
