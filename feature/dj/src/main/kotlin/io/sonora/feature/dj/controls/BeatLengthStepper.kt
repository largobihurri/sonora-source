package io.sonora.feature.dj.controls

import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.loop.LoopQuantizer
import io.sonora.core.djmodel.loop.LoopSizes
import kotlin.math.abs
import kotlin.math.ln

/**
 * The beat lengths of the stepper in the loop controls (a CDJ's beat loop select): the eleven sizes from 1/32 to 32 beats
 * the loop engine offers ([LoopSizes.BEATS]), the arrows moving one size at a time and the middle showing the size.
 *
 * Pure, so the rules (the ends, a length that is between two sizes, what is shown while a loop plays) are unit tested.
 */
object BeatLengthStepper {

    /** Every size, shortest first. */
    val SIZES: List<Double> = LoopSizes.BEATS

    /** The length a fresh stepper shows: four beats, a bar in 4/4. */
    const val DEFAULT: Double = 4.0

    /** How far a loop may be from a whole size (as a share of it) and still be called that size. */
    private const val MEASURE_TOLERANCE = 0.03

    private const val EPSILON = 1e-9

    /** The text of [beats]: `1/32` to `1/2` below a beat, a whole number from one. */
    fun label(beats: Double): String =
        if (beats < 1.0 - EPSILON) "1/${(1.0 / beats).toInt()}" else beats.toInt().toString()

    /**
     * The size [delta] steps from [beats]: one size up for a positive delta, one down for a negative one (the ends hold).
     * A length between two sizes goes to the next size in the direction asked; a delta of zero is the nearest size.
     */
    fun stepped(beats: Double, delta: Int): Double = when {
        delta > 0 -> SIZES.firstOrNull { it > beats * (1.0 + EPSILON) } ?: SIZES.last()
        delta < 0 -> SIZES.lastOrNull { it < beats * (1.0 - EPSILON) } ?: SIZES.first()
        else -> SIZES.minBy { abs(ln(it / beats)) }
    }

    /** Whether there is a size beyond [beats] in the direction of [delta]. */
    fun canStep(beats: Double, delta: Int): Boolean = when {
        delta > 0 -> beats < SIZES.last() * (1.0 - EPSILON)
        delta < 0 -> beats > SIZES.first() * (1.0 + EPSILON)
        else -> true
    }

    /**
     * The size of the loop `[inMs, outMs)` counted on [grid] (a loop may start anywhere; a tempo change inside it is
     * counted in beats), or `null` without a grid or when the loop is not one of the sizes within a few percent.
     */
    fun measured(inMs: Double, outMs: Double, grid: DjBeatGrid?, durationMs: Double): Double? {
        if (grid == null || !(outMs > inMs)) return null
        val quantizer = LoopQuantizer(grid, durationMs)
        val length = quantizer.beatPositionAt(outMs) - quantizer.beatPositionAt(inMs)
        return SIZES.firstOrNull { abs(length / it - 1.0) <= MEASURE_TOLERANCE }
    }

    /** What the stepper shows: the size of the loop that is set when it is one of the sizes, else the size chosen. */
    fun shown(chosen: Double, measured: Double?): Double = measured ?: chosen
}
