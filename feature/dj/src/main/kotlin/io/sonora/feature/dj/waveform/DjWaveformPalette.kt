package io.sonora.feature.dj.waveform

import kotlin.math.pow

/** The three bands of a [io.sonora.core.djmodel.BandWaveform]. */
enum class WaveformBand { LOW, MID, HIGH }

/**
 * The look of the 3-band waveform, as pure data (ARGB colours and the level curve) so it is testable without Compose.
 *
 * Each column is drawn as three bars mirrored around the centre line and layered low, then mid, then high: bass is the
 * big blue body, the mids show amber over it, the highs a white core on top. The planes hold the peak of each band in
 * a linear scale, so the curve lifts the quiet levels (gamma below 1) to keep detail visible.
 *
 * The colours and the exponent are first values, to be tuned by eye on the device.
 */
object DjWaveformPalette {

    /** Low blue (ARGB). */
    const val LOW_COLOR: Int = 0xFF2F6BFF.toInt()

    /** Mid amber (ARGB). */
    const val MID_COLOR: Int = 0xFFFFA726.toInt()

    /** High white (ARGB). */
    const val HIGH_COLOR: Int = 0xFFFFFFFF.toInt()

    /** The beat ticks at the top and bottom edges (ARGB, translucent white). */
    const val BEAT_TICK_COLOR: Int = 0xB3FFFFFF.toInt()

    /**
     * The full-height line on each downbeat, beat 1 of every bar (ARGB, near-opaque red): it has to stand out from the
     * beat ticks and the waveform at a glance, so the bars read like a CDJ's.
     */
    const val DOWNBEAT_COLOR: Int = 0xF2FF3B30.toInt()

    /** The heavier ticks at the top and bottom edges on each downbeat (ARGB, opaque red). */
    const val DOWNBEAT_TICK_COLOR: Int = 0xFFFF3B30.toInt()

    /**
     * The line and ticks on the first downbeat of each phrase of four bars (ARGB, opaque green, iOS system green): a hue
     * apart from the red bars and from the blue, amber and white bands, so the phrases read at a glance.
     */
    const val PHRASE_COLOR: Int = 0xFF30D158.toInt()

    /**
     * The loop region (ARGB, opaque orange-yellow, the colour of a CDJ's loop): the translucent fill, the In and Out edges and
     * the rails along the top and bottom that join them. Every hue is taken by something on this screen (red bars, green
     * phrases, the blue, amber and white bands, the sixteen cue colours), so a loop is told apart by its shape (a closed
     * bracket with square caps) as much as by its colour; this one sits between the amber mids and the yellow cues, and lit
     * it is the only thing washing a whole stretch of the waveform.
     */
    const val LOOP_COLOR: Int = 0xFFFFA800.toInt()

    /** The fixed playhead at the centre (ARGB). */
    const val PLAYHEAD_COLOR: Int = 0xFFFFFFFF.toInt()

    /** The veil over the played part of the overview strip (ARGB, translucent black). */
    const val PLAYED_VEIL_COLOR: Int = 0x8C000000.toInt()

    /** The exponent of the level curve: below 1 raises the quiet levels. */
    const val LEVEL_EXPONENT: Float = 0.8f

    /** Back to front: later bands are drawn over earlier ones. */
    val DRAW_ORDER: List<WaveformBand> = listOf(WaveformBand.LOW, WaveformBand.MID, WaveformBand.HIGH)

    private val curve = FloatArray(256) { (it / 255f).pow(LEVEL_EXPONENT) }.also { it[255] = 1f }

    fun colorOf(band: WaveformBand): Int = when (band) {
        WaveformBand.LOW -> LOW_COLOR
        WaveformBand.MID -> MID_COLOR
        WaveformBand.HIGH -> HIGH_COLOR
    }

    /** The height share 0..1 for a band level; levels outside 0..255 are clamped. */
    fun levelFraction(level: Int): Float = curve[level.coerceIn(0, 255)]

    /** Half the bar height in px for [level], given the largest half height available. */
    fun halfHeightPx(level: Int, maxHalfHeightPx: Float): Float = levelFraction(level) * maxHalfHeightPx

    /** The top edge of a bar mirrored around [centrePx]. */
    fun topPx(centrePx: Float, halfHeightPx: Float): Float = centrePx - halfHeightPx

    /** The bottom edge of a bar mirrored around [centrePx]. */
    fun bottomPx(centrePx: Float, halfHeightPx: Float): Float = centrePx + halfHeightPx
}
