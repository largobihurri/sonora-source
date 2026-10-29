package io.sonora.feature.equalizer

import kotlin.math.floor

/*
 * How the equalizer panel shares its room, in dp. The curve and the sliders' tracks are the only parts that can give;
 * everything else (switches, labels, Reset, the hint) keeps its size. Pure, so it is unit-tested.
 */

/** The response curve: tall enough to read a boost, no taller than it was before it had to share. */
internal const val EQUALIZER_CURVE_MIN_HEIGHT = 56f
internal const val EQUALIZER_CURVE_MAX_HEIGHT = 120f

/** A band's track: about 4 dp per dB of travel at the least, 200 dp at the most. */
internal const val EQUALIZER_TRACK_MIN_HEIGHT = 96f
internal const val EQUALIZER_TRACK_MAX_HEIGHT = 200f

/** The curve's share of the room, the curve and tracks at full size (120:200). */
private const val CURVE_SHARE = 0.375f

/** The heights of the curve and of the tracks, and whether the panel must still scroll. */
internal data class EqualizerMetrics(val curveHeight: Float, val trackHeight: Float, val scrolls: Boolean)

/**
 * Sizes the curve and the tracks to the room a panel [availableHeight] tall leaves after [fixedHeight] of other
 * content. They share it 3:5 between their minimum and maximum, whatever one cannot take going to the other, so the
 * content fills the panel exactly and never scrolls while the minimums fit. With room to spare both stay at their
 * maximum (the panel wraps them); without room for the minimums they keep them and the panel scrolls.
 */
internal fun equalizerMetrics(availableHeight: Float, fixedHeight: Float): EqualizerMetrics {
    val room = availableHeight - fixedHeight
    if (room < EQUALIZER_CURVE_MIN_HEIGHT + EQUALIZER_TRACK_MIN_HEIGHT) {
        return EqualizerMetrics(EQUALIZER_CURVE_MIN_HEIGHT, EQUALIZER_TRACK_MIN_HEIGHT, scrolls = true)
    }
    val firstCurve = (room * CURVE_SHARE).coerceIn(EQUALIZER_CURVE_MIN_HEIGHT, EQUALIZER_CURVE_MAX_HEIGHT)
    val track = (room - firstCurve).coerceIn(EQUALIZER_TRACK_MIN_HEIGHT, EQUALIZER_TRACK_MAX_HEIGHT)
    val curve = (room - track).coerceIn(EQUALIZER_CURVE_MIN_HEIGHT, EQUALIZER_CURVE_MAX_HEIGHT)
    return EqualizerMetrics(curve, track, scrolls = false)
}

/** The least clear space between two neighbouring knobs, so a row of them never reads as a chain. */
internal const val BAND_KNOB_GAP = 8f

private const val KNOB_MAX_WIDTH = 26f
private const val KNOB_MAX_HEIGHT = 14f
private const val KNOB_MIN_HEIGHT = 10f
private const val KNOB_ASPECT = 0.55f

/** The narrowest column that still holds a frequency label ("125", "16k") at the compact size. */
private const val MIN_FIT_COLUMN = 20f

/** Below this a column takes the compact labels. */
private const val COMPACT_COLUMN = 26f

/** About how wide a scrolling column is: a label like "1,4k" with air on both sides, and a full-size knob. */
private const val SCROLL_COLUMN = 34f

/** The width of one band's column, its knob, whether the bands scroll sideways and whether labels are compact. */
internal data class BandColumns(
    val columnWidth: Float,
    val knobWidth: Float,
    val knobHeight: Float,
    val scrolls: Boolean,
    val compactLabels: Boolean,
)

/**
 * Lays [bandCount] sliders across [availableWidth]. While each column gets at least 20 dp they share the width
 * equally (4 and 11 bands on a phone), the knob narrowing so a gap of [BAND_KNOB_GAP] always shows between knobs.
 * Narrower than that, the labels could not be read: the bands scroll sideways instead (20 bands), in columns about
 * 34 dp wide, sized so the viewport shows whole columns and a half one peeking at the edge, a cue that there is more
 * and a natural place to snap to.
 */
internal fun bandColumns(bandCount: Int, availableWidth: Float): BandColumns {
    val width = availableWidth.coerceAtLeast(1f)
    val fit = width / bandCount.coerceAtLeast(1)
    val scrolls = fit < MIN_FIT_COLUMN
    val column = if (scrolls) {
        val whole = floor(width / SCROLL_COLUMN).coerceAtLeast(1f)
        width / (whole + 0.5f)
    } else {
        fit
    }
    val knobWidth = (column - BAND_KNOB_GAP).coerceIn(0f, KNOB_MAX_WIDTH)
    val knobHeight = (knobWidth * KNOB_ASPECT).coerceIn(KNOB_MIN_HEIGHT, KNOB_MAX_HEIGHT).coerceAtMost(knobWidth)
    return BandColumns(column, knobWidth, knobHeight, scrolls, compactLabels = column < COMPACT_COLUMN)
}
