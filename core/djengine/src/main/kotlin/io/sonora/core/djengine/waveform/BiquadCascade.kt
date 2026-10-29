package io.sonora.core.djengine.waveform

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.PI
import kotlin.math.sin

/**
 * A chain of second-order sections (biquads), each
 * `H(z) = (b0 + b1 z^-1 + b2 z^-2) / (1 + a1 z^-1 + a2 z^-2)`, in doubles and transposed direct form II:
 * `y = b0 x + z1; z1 = b1 x - a1 y + z2; z2 = b2 x - a2 y`. Chaining second-order sections keeps a high-order filter
 * numerically well-behaved where one high-order difference equation would not be. [process] allocates nothing.
 *
 * The coefficient arrays (one entry per section, in signal order) are copied. Not thread-safe: the sections carry
 * their state.
 */
class BiquadCascade(b0: DoubleArray, b1: DoubleArray, b2: DoubleArray, a1: DoubleArray, a2: DoubleArray) {

    private val b0 = b0.copyOf()
    private val b1 = b1.copyOf()
    private val b2 = b2.copyOf()
    private val a1 = a1.copyOf()
    private val a2 = a2.copyOf()
    private val z1: DoubleArray
    private val z2: DoubleArray

    init {
        val size = b0.size
        require(size > 0) { "A cascade needs at least one section" }
        require(b1.size == size && b2.size == size && a1.size == size && a2.size == size) {
            "All coefficient arrays must have one entry per section: $size"
        }
        z1 = DoubleArray(size)
        z2 = DoubleArray(size)
    }

    /** Number of second-order sections. */
    val sectionCount: Int get() = b0.size

    /** Filters one sample. */
    fun process(x: Double): Double {
        var value = x
        for (i in b0.indices) {
            val y = b0[i] * value + z1[i]
            z1[i] = b1[i] * value - a1[i] * y + z2[i]
            z2[i] = b2[i] * value - a2[i] * y
            value = y
        }
        return value
    }

    /** Clears the state, as at the start of a signal. */
    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    /**
     * Zeroes the state of every section whose state has decayed to nothing (after silence), so the filter never runs
     * on slow subnormal numbers. A live state is left untouched, so the output is unaffected.
     */
    fun flushDenormals() {
        for (i in z1.indices) {
            if (abs(z1[i]) < TINY && abs(z2[i]) < TINY) {
                z1[i] = 0.0
                z2[i] = 0.0
            }
        }
    }

    /** The gain `|H(e^jw)|` (linear) at [frequencyHz] for a signal sampled at [sampleRateHz]. */
    fun magnitudeAt(frequencyHz: Double, sampleRateHz: Int): Double {
        val w = 2 * PI * frequencyHz / sampleRateHz
        val c1 = cos(w)
        val s1 = -sin(w)
        val c2 = cos(2 * w)
        val s2 = -sin(2 * w)
        var gain = 1.0
        for (i in b0.indices) {
            val numRe = b0[i] + b1[i] * c1 + b2[i] * c2
            val numIm = b1[i] * s1 + b2[i] * s2
            val denRe = 1.0 + a1[i] * c1 + a2[i] * c2
            val denIm = a1[i] * s1 + a2[i] * s2
            gain *= hypot(numRe, numIm) / hypot(denRe, denIm)
        }
        return gain
    }

    /** [magnitudeAt] in decibels. */
    fun magnitudeDbAt(frequencyHz: Double, sampleRateHz: Int): Double = 20 * log10(magnitudeAt(frequencyHz, sampleRateHz))

    /** This cascade followed by [other], as a new cascade with cleared state. */
    operator fun plus(other: BiquadCascade): BiquadCascade = BiquadCascade(
        b0 + other.b0,
        b1 + other.b1,
        b2 + other.b2,
        a1 + other.a1,
        a2 + other.a2,
    )

    private companion object {
        const val TINY = 1e-25
    }
}
