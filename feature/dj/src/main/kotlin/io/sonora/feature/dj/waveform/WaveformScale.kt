package io.sonora.feature.dj.waveform

/**
 * How much of the track the scrolling waveform shows at once. Zooming changes the visible span, never the data: the
 * scale and the envelopes derive from it.
 */
enum class WaveformZoom(val visibleSeconds: Int) {
    SECONDS_4(4),
    SECONDS_8(8),
    SECONDS_16(16),
    SECONDS_32(32),
    ;

    /** The visible span in milliseconds. */
    val visibleMs: Double get() = visibleSeconds * 1000.0

    companion object {
        /** The span the screen opens with. */
        val DEFAULT: WaveformZoom = SECONDS_8
    }
}

/**
 * The mapping between track time and pixels of the scrolling waveform at one [zoom] on a view [widthPx] wide.
 *
 * Pixels are absolute: pixel 0 is the start of the track, whatever is on screen. Scrolling is therefore a pure
 * translation (the left edge of the view moves, nothing is re-laid out), and an envelope or a tile built once stays
 * valid for the whole track at this scale. The whole view width holds [WaveformZoom.visibleMs].
 */
class WaveformScale(val widthPx: Int, val zoom: WaveformZoom) {

    init {
        require(widthPx > 0) { "widthPx must be positive: $widthPx" }
    }

    /** Pixels per millisecond of track time. */
    val pxPerMs: Double = widthPx / zoom.visibleMs

    /** The absolute pixel (from the track start, fractional) of [ms]. */
    fun absolutePx(ms: Double): Double = ms * pxPerMs

    /** The track time at the absolute pixel [px]; the inverse of [absolutePx]. */
    fun msAt(px: Double): Double = px / pxPerMs

    /**
     * The absolute pixel at the left edge of the view when the playhead, fixed at the centre of the view, is at
     * [positionMs]. It is negative near the track start: the area before the first sample is empty.
     */
    fun leftPxFor(positionMs: Double): Double = absolutePx(positionMs) - widthPx / 2.0
}
