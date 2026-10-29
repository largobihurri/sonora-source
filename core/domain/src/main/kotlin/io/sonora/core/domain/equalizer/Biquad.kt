package io.sonora.core.domain.equalizer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The filter shape of one equalizer band. */
enum class BandShape { LOW_SHELF, PEAKING, HIGH_SHELF }

/**
 * One second-order IIR section, normalised so a0 = 1:
 * `y[n] = b0·x[n] + b1·x[n-1] + b2·x[n-2] - a1·y[n-1] - a2·y[n-2]`.
 */
data class BiquadCoefficients(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {

    /** The filter's gain in dB at [frequencyHz] for a stream at [sampleRateHz]. */
    fun magnitudeDb(frequencyHz: Double, sampleRateHz: Int): Double {
        val w = 2 * PI * frequencyHz / sampleRateHz
        return 10 * log10(Biquad.magnitudeSquared(b0, b1, b2, a1, a2, cos(w), cos(2 * w)))
    }

    companion object {
        val IDENTITY = BiquadCoefficients(1.0, 0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * Filter design from Robert Bristow-Johnson's Audio EQ Cookbook (peaking EQ, low shelf, high shelf), with `Q` as
 * the bandwidth control (shelves use the cookbook's Q form; `1/√2` is the steepest shelf with no overshoot).
 *
 * A band at or above Nyquist cannot exist at that sample rate: a peaking band there is flat, and a shelf's corner is
 * pulled down to [MAX_SHELF_FRACTION] of the rate so a 16 kHz treble shelf still lifts the top of a 32 kHz stream.
 */
object Biquad {
    /** The highest shelf corner, as a fraction of the sample rate. */
    const val MAX_SHELF_FRACTION = 0.45

    /** Number of doubles one section takes in a coefficient array: b0, b1, b2, a1, a2. */
    const val STRIDE = 5

    fun design(shape: BandShape, frequencyHz: Double, q: Double, gainDb: Double, sampleRateHz: Int): BiquadCoefficients {
        val out = DoubleArray(STRIDE)
        design(shape, frequencyHz, q, gainDb, sampleRateHz, out, 0)
        return BiquadCoefficients(out[0], out[1], out[2], out[3], out[4])
    }

    /** Allocation-free [design]: writes b0, b1, b2, a1, a2 into [out] from [offset]. */
    fun design(shape: BandShape, frequencyHz: Double, q: Double, gainDb: Double, sampleRateHz: Int, out: DoubleArray, offset: Int) {
        val nyquist = sampleRateHz / 2.0
        if (shape == BandShape.PEAKING && frequencyHz >= nyquist) {
            identity(out, offset)
            return
        }
        val f = if (shape == BandShape.PEAKING) frequencyHz else minOf(frequencyHz, MAX_SHELF_FRACTION * sampleRateHz)
        val a = 10.0.pow(gainDb / 40)
        val w0 = 2 * PI * f / sampleRateHz
        val cosW = cos(w0)
        val alpha = sin(w0) / (2 * q)
        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double
        when (shape) {
            BandShape.PEAKING -> {
                b0 = 1 + alpha * a
                b1 = -2 * cosW
                b2 = 1 - alpha * a
                a0 = 1 + alpha / a
                a1 = -2 * cosW
                a2 = 1 - alpha / a
            }
            BandShape.LOW_SHELF -> {
                val s = 2 * sqrt(a) * alpha
                b0 = a * ((a + 1) - (a - 1) * cosW + s)
                b1 = 2 * a * ((a - 1) - (a + 1) * cosW)
                b2 = a * ((a + 1) - (a - 1) * cosW - s)
                a0 = (a + 1) + (a - 1) * cosW + s
                a1 = -2 * ((a - 1) + (a + 1) * cosW)
                a2 = (a + 1) + (a - 1) * cosW - s
            }
            BandShape.HIGH_SHELF -> {
                val s = 2 * sqrt(a) * alpha
                b0 = a * ((a + 1) + (a - 1) * cosW + s)
                b1 = -2 * a * ((a - 1) + (a + 1) * cosW)
                b2 = a * ((a + 1) + (a - 1) * cosW - s)
                a0 = (a + 1) - (a - 1) * cosW + s
                a1 = 2 * ((a - 1) - (a + 1) * cosW)
                a2 = (a + 1) - (a - 1) * cosW - s
            }
        }
        out[offset] = b0 / a0
        out[offset + 1] = b1 / a0
        out[offset + 2] = b2 / a0
        out[offset + 3] = a1 / a0
        out[offset + 4] = a2 / a0
    }

    private fun identity(out: DoubleArray, offset: Int) {
        out[offset] = 1.0
        out[offset + 1] = 0.0
        out[offset + 2] = 0.0
        out[offset + 3] = 0.0
        out[offset + 4] = 0.0
    }

    /**
     * |H(e^jw)|² of one section from `cos(w)` and `cos(2w)`, which depend only on the frequency, so a cascade's
     * response at one frequency needs the cosines once for every section.
     */
    fun magnitudeSquared(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double, cosW: Double, cos2W: Double): Double {
        val num = b0 * b0 + b1 * b1 + b2 * b2 + 2 * (b0 * b1 + b1 * b2) * cosW + 2 * b0 * b2 * cos2W
        val den = 1 + a1 * a1 + a2 * a2 + 2 * (a1 + a1 * a2) * cosW + 2 * a2 * cos2W
        return num / den
    }
}
