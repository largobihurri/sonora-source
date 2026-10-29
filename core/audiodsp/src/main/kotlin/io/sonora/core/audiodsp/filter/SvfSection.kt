package io.sonora.core.audiodsp.filter

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.tan

/**
 * Coefficients of one second-order section in the trapezoidal (topology-preserving) state-variable form.
 *
 * The cutoff is pre-warped (`g = tan(pi fc / fs)`), so the response at the cutoff is exact at every sample rate and
 * matches a bilinear-transform biquad; unlike a direct-form biquad, the state survives coefficient changes made every
 * sample, which is what a swept filter needs. One set of coefficients can drive any number of [SvfState]s.
 */
internal class SvfCoefficients {
    var k = 0.0
        private set
    var g = 0.0
        private set
    var a1 = 0.0
        private set
    var a2 = 0.0
        private set
    var a3 = 0.0
        private set

    /** [cutoffHz] is clamped to [MIN_CUTOFF_HZ] .. `MAX_CUTOFF_RATIO * sampleRateHz`; [q] must be positive. */
    fun set(cutoffHz: Double, q: Double, sampleRateHz: Double) {
        val fc = cutoffHz.coerceIn(MIN_CUTOFF_HZ, sampleRateHz * MAX_CUTOFF_RATIO)
        g = tan(PI * fc / sampleRateHz)
        k = 1.0 / q
        a1 = 1.0 / (1.0 + g * (g + k))
        a2 = g * a1
        a3 = g * a2
    }

    companion object {
        const val MIN_CUTOFF_HZ = 1.0

        /** Highest cutoff as a fraction of the sample rate, safely below Nyquist. */
        const val MAX_CUTOFF_RATIO = 0.45
    }
}

/**
 * The two integrator states of one state-variable section for one channel. Each call ticks the section once and returns
 * one of its responses, so call exactly one of them per sample.
 *
 * [nodeKnee] bounds the band-pass (resonant) integrator with a soft knee: below it the section is exactly linear, above
 * it the state saturates towards `nodeKnee + 1`, which keeps high-resonance sweeps of loud input bounded.
 * States smaller than [DENORMAL_FLOOR] are flushed to zero.
 */
internal class SvfState(private val nodeKnee: Double = Double.POSITIVE_INFINITY) {
    private var ic1 = 0.0
    private var ic2 = 0.0
    private var band = 0.0
    private var low = 0.0

    fun reset() {
        ic1 = 0.0
        ic2 = 0.0
        band = 0.0
        low = 0.0
    }

    fun lowPass(c: SvfCoefficients, x: Double): Double {
        tick(c, x)
        return low
    }

    fun highPass(c: SvfCoefficients, x: Double): Double {
        tick(c, x)
        return x - c.k * band - low
    }

    /** Second-order allpass: unity magnitude, phase from 0 to -360 degrees through -180 at the cutoff. */
    fun allPass(c: SvfCoefficients, x: Double): Double {
        tick(c, x)
        return x - 2.0 * c.k * band
    }

    private fun tick(c: SvfCoefficients, x: Double) {
        val v3 = x - ic2
        val v1 = c.a1 * ic1 + c.a2 * v3
        val v2 = ic2 + c.a2 * ic1 + c.a3 * v3
        band = v1
        low = v2
        ic1 = flush(limit(2.0 * v1 - ic1))
        ic2 = flush(2.0 * v2 - ic2)
    }

    private fun limit(v: Double): Double {
        val magnitude = abs(v)
        if (magnitude <= nodeKnee) return v
        val excess = magnitude - nodeKnee
        val limited = nodeKnee + excess / (1.0 + excess)
        return if (v < 0) -limited else limited
    }

    private fun flush(v: Double): Double = if (abs(v) < DENORMAL_FLOOR) 0.0 else v

    companion object {
        const val DENORMAL_FLOOR = 1e-30
    }
}
