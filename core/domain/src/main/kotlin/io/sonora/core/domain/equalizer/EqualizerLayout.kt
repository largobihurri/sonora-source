package io.sonora.core.domain.equalizer

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * One equalizer band: a filter centred (or, for a shelf, cornered) at [frequencyHz]. [nominalHz] is the rounded
 * frequency the screen shows (`31`, `1.4k`), as on hardware graphic equalizers.
 */
data class EqualizerBand(val frequencyHz: Double, val nominalHz: Double, val shape: BandShape, val q: Double)

/**
 * The band layout of each [EqualizerMode]. Graphic modes are spaced on the base-2 series through 1 kHz with shelves
 * at both ends and constant-Q peaking bands between them, one bandwidth apart: Q √2 for octaves, about 2.87 for
 * half octaves.
 */
object EqualizerLayout {
    /** The most bands any mode has. */
    const val MAX_BANDS = 20

    private val SHELF_Q = 1 / sqrt(2.0)
    private val OCTAVE_Q = sqrt(2.0)
    private val HALF_OCTAVE_Q = 2.0.pow(0.25) / (sqrt(2.0) - 1)

    private val four = listOf(
        // Sub-bass, bass, mids (wide) and treble.
        EqualizerBand(60.0, 60.0, BandShape.LOW_SHELF, SHELF_Q),
        EqualizerBand(150.0, 150.0, BandShape.PEAKING, 1.0),
        EqualizerBand(1_000.0, 1_000.0, BandShape.PEAKING, 0.5),
        EqualizerBand(8_000.0, 8_000.0, BandShape.HIGH_SHELF, SHELF_Q),
    )

    private val eleven = graphic(
        steps = -6..4,
        stepsPerOctave = 1,
        q = OCTAVE_Q,
        nominal = listOf(16, 31, 63, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000),
    )

    // 22 Hz to 16 kHz: half-octave steps cannot span 31 Hz to 16 kHz in 20 bands, and a band above 16 kHz would
    // sit at or past Nyquist for 44.1 kHz audio, so the extra band goes to the bottom.
    private val twenty = graphic(
        steps = -11..8,
        stepsPerOctave = 2,
        q = HALF_OCTAVE_Q,
        nominal = listOf(
            22, 31, 45, 63, 90, 125, 180, 250, 355, 500, 710, 1_000, 1_400, 2_000, 2_800, 4_000, 5_600, 8_000, 11_000, 16_000,
        ),
    )

    fun bands(mode: EqualizerMode): List<EqualizerBand> = when (mode) {
        EqualizerMode.FOUR_BANDS -> four
        EqualizerMode.ELEVEN_BANDS -> eleven
        EqualizerMode.TWENTY_BANDS -> twenty
    }

    /** Bands at `1 kHz · 2^(step / stepsPerOctave)` for each of [steps], shelves at both ends. */
    private fun graphic(steps: IntRange, stepsPerOctave: Int, q: Double, nominal: List<Int>): List<EqualizerBand> {
        val frequencies = steps.map { step -> 1_000.0 * 2.0.pow(step.toDouble() / stepsPerOctave) }
        check(frequencies.size == nominal.size)
        return frequencies.mapIndexed { i, frequency ->
            val shape = when (i) {
                0 -> BandShape.LOW_SHELF
                frequencies.lastIndex -> BandShape.HIGH_SHELF
                else -> BandShape.PEAKING
            }
            EqualizerBand(frequency, nominal[i].toDouble(), shape, if (shape == BandShape.PEAKING) q else SHELF_Q)
        }
    }
}
