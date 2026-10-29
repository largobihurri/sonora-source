package io.sonora.core.djengine.beats

import io.sonora.core.djengine.waveform.BesselFilters
import io.sonora.core.djengine.waveform.BiquadCascade
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The per-sample low-band onset function behind [OnsetEnvelopeSink], one sample in, one value out, no allocation:
 *
 * 1. a 4th-order Bessel band-pass ([BesselFilters], `lowHz..highHz`) keeps the kick region;
 * 2. the instantaneous power `y^2` goes through a one-pole smoother of time constant [smoothingMs], and its square
 *    root is the amplitude envelope `a[n]` of the band (a continuous, smooth function of time: sampling it every hop
 *    never aliases and does not depend on where the hop grid falls);
 * 3. the onset function is the half-wave rectified rise of that envelope over one hop,
 *    `max(0, a[n] - a[n - hop])`, an energy derivative (a spectral flux of a single band; Bello et al. 2005, "A tutorial
 *    on onset detection in music signals", sec. III-B; Dixon 2006, "Onset detection revisited").
 *
 * The chain is causal, so its response to a transient arrives a little late (the group delay of the band-pass, the
 * smoother and the difference); [OnsetEnvelopeSink] measures that delay once on an impulse and compensates it.
 */
internal class OnsetChain(
    sampleRateHz: Int,
    private val hopSamples: Int,
    lowHz: Double,
    highHz: Double,
    smoothingMs: Double,
) {
    private val band: BiquadCascade = BesselFilters.bandPass(lowHz, highHz, sampleRateHz)
    private val alpha = 1.0 - exp(-1.0 / (smoothingMs / 1000.0 * sampleRateHz))
    private val history = DoubleArray(hopSamples)
    private var position = 0
    private var power = 0.0

    /** Pushes one sample (finite) and returns the rectified rise of the band envelope over the last hop. */
    fun process(x: Double): Double {
        val y = band.process(x)
        power += alpha * (y * y - power)
        val amplitude = sqrt(power)
        val before = history[position]
        history[position] = amplitude
        if (++position == hopSamples) position = 0
        return max(0.0, amplitude - before)
    }

    /** Zeroes state that has decayed to nothing, so silence after loud audio never runs on subnormal numbers. */
    fun flushDenormals() {
        band.flushDenormals()
        if (power < TINY_POWER) power = 0.0
    }

    private companion object {
        /** Power below which the smoother state is dropped: -300 dB re full scale, far under any 32-bit sample. */
        const val TINY_POWER = 1e-30
    }
}
