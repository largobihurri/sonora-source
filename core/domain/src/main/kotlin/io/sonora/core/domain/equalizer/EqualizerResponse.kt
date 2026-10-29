package io.sonora.core.domain.equalizer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The frequency response of a cascade of bands, from the same filters the player runs: what the screen draws, and
 * the automatic preamp (headroom) that keeps every boost below full scale.
 */
object EqualizerResponse {
    /** Points of the log-spaced grid [peakDb] searches, besides each band's frequency and the midpoints between them. */
    const val PEAK_GRID_POINTS = 512

    /** Points of the coarser log grid [peakDb] adds above [AUDIBLE_HZ], up to just below Nyquist, at high rates. */
    const val HIGH_GRID_POINTS = 64

    private const val LOWEST_HZ = 10.0
    private const val AUDIBLE_HZ = 22_000.0

    /** The cascade's gain in dB at [frequencyHz]. */
    fun responseDb(bands: List<EqualizerBand>, gains: List<Float>, frequencyHz: Double, sampleRateHz: Int): Double {
        val coefficients = design(bands, gains, sampleRateHz)
        val w = 2 * PI * frequencyHz / sampleRateHz
        return 10 * log10(magnitudeSquared(coefficients, bands.size, cos(w), cos(2 * w)))
    }

    /** The automatic preamp in dB (≤ 0): minus the curve's highest boost, so no frequency rises above full scale. */
    fun preampDb(bands: List<EqualizerBand>, gains: List<Float>, sampleRateHz: Int): Double {
        val frequencies = DoubleArray(bands.size) { bands[it].frequencyHz }
        val peak = peakDb(design(bands, gains, sampleRateHz), frequencies, bands.size, sampleRateHz)
        return if (peak > 0) -peak else 0.0
    }

    /**
     * The highest gain in dB of the first [count] sections in [coefficients] (laid out [Biquad.STRIDE] apart),
     * searched on a log grid of [PEAK_GRID_POINTS] from 10 Hz to 22 kHz (or just below Nyquist, if lower), then on
     * one of [HIGH_GRID_POINTS] from there to just below Nyquist (a treble shelf keeps rising up there, and the
     * stream carries those frequencies at 96 or 192 kHz), plus each section's [frequencies] and the geometric
     * midpoints between neighbours, where overlapping boosts add up. Allocation-free.
     */
    fun peakDb(coefficients: DoubleArray, frequencies: DoubleArray, count: Int, sampleRateHz: Int): Double {
        val nyquist = 0.49 * sampleRateHz
        val audible = min(AUDIBLE_HZ, nyquist)
        var peak = Double.NEGATIVE_INFINITY
        val step = ln(audible / LOWEST_HZ) / (PEAK_GRID_POINTS - 1)
        for (i in 0 until PEAK_GRID_POINTS) {
            peak = max(peak, gainAt(coefficients, count, LOWEST_HZ * exp(step * i), sampleRateHz))
        }
        if (nyquist > audible) {
            val highStep = ln(nyquist / audible) / HIGH_GRID_POINTS
            for (i in 1..HIGH_GRID_POINTS) {
                peak = max(peak, gainAt(coefficients, count, audible * exp(highStep * i), sampleRateHz))
            }
        }
        for (i in 0 until count) {
            val f = frequencies[i]
            if (f < nyquist) peak = max(peak, gainAt(coefficients, count, f, sampleRateHz))
            if (i > 0) {
                val mid = sqrt(f * frequencies[i - 1])
                if (mid < nyquist) peak = max(peak, gainAt(coefficients, count, mid, sampleRateHz))
            }
        }
        return peak
    }

    private fun gainAt(coefficients: DoubleArray, count: Int, frequencyHz: Double, sampleRateHz: Int): Double {
        val w = 2 * PI * frequencyHz / sampleRateHz
        return 10 * log10(magnitudeSquared(coefficients, count, cos(w), cos(2 * w)))
    }

    private fun magnitudeSquared(coefficients: DoubleArray, count: Int, cosW: Double, cos2W: Double): Double {
        var product = 1.0
        for (i in 0 until count) {
            val o = i * Biquad.STRIDE
            product *= Biquad.magnitudeSquared(
                coefficients[o], coefficients[o + 1], coefficients[o + 2], coefficients[o + 3], coefficients[o + 4], cosW, cos2W,
            )
        }
        return product
    }

    private fun design(bands: List<EqualizerBand>, gains: List<Float>, sampleRateHz: Int): DoubleArray {
        val out = DoubleArray(bands.size * Biquad.STRIDE)
        bands.forEachIndexed { i, band ->
            Biquad.design(band.shape, band.frequencyHz, band.q, gains.getOrElse(i) { 0f }.toDouble(), sampleRateHz, out, i * Biquad.STRIDE)
        }
        return out
    }
}
