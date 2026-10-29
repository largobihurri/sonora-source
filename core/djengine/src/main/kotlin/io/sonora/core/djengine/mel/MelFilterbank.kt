package io.sonora.core.djengine.mel

import kotlin.math.exp
import kotlin.math.ln

/**
 * The triangular mel filterbank of Beat This! (`torchaudio.functional.melscale_fbanks(..., norm=None,
 * mel_scale="slaney")`, see `tools/beatthis/README.md`), built in double precision and stored sparsely.
 *
 * The Slaney mel scale is linear below 1000 Hz (`mel = hz / (200 / 3)`) and logarithmic above it
 * (`mel = 15 + ln(hz / 1000) / (ln(6.4) / 27)`; Slaney 1998, "Auditory Toolbox", as used by librosa and torchaudio).
 * [bandCount] + 2 equally spaced mel points between the mels of [fMinHz] and [fMaxHz] give the band edges; band `m`
 * rises from point `m` to point `m + 1` and falls to point `m + 2`. The triangles are evaluated at the linear
 * frequencies of the FFT bins (`k * sampleRate / fftSize`), not snapped to them, have a peak of 1 and are not
 * normalised by their area.
 */
internal class MelFilterbank(sampleRateHz: Int, fftSize: Int, val bandCount: Int, fMinHz: Double, fMaxHz: Double) {

    /** Number of FFT bins the filterbank reads: `fftSize / 2 + 1`. */
    val binCount: Int = fftSize / 2 + 1

    // Band b has non-zero weights on bins firstBin[b] until firstBin[b] + length[b], stored from start[b] in `weights`.
    private val firstBin = IntArray(bandCount)
    private val length = IntArray(bandCount)
    private val start = IntArray(bandCount)
    private val weights: DoubleArray

    init {
        require(fftSize >= 2 && bandCount > 0 && sampleRateHz > 0) { "invalid filterbank dimensions" }
        require(fMinHz >= 0 && fMaxHz > fMinHz && fMaxHz <= sampleRateHz / 2.0) { "invalid band range $fMinHz..$fMaxHz Hz" }
        val mMin = hzToMel(fMinHz)
        val mMax = hzToMel(fMaxHz)
        val edgesHz = DoubleArray(bandCount + 2) { melToHz(mMin + it * ((mMax - mMin) / (bandCount + 1))) }
        val binStepHz = (sampleRateHz / 2.0) / (binCount - 1)

        val collected = ArrayList<Double>()
        for (band in 0 until bandCount) {
            val low = edgesHz[band]
            val centre = edgesHz[band + 1]
            val high = edgesHz[band + 2]
            var first = -1
            var last = -1
            for (bin in 0 until binCount) {
                val hz = bin * binStepHz
                val weight = maxOf(0.0, minOf((hz - low) / (centre - low), (high - hz) / (high - centre)))
                if (weight > 0.0) {
                    if (first < 0) first = bin
                    last = bin
                }
            }
            firstBin[band] = maxOf(first, 0)
            length[band] = if (first < 0) 0 else last - first + 1
            start[band] = collected.size
            for (bin in firstBin[band] until firstBin[band] + length[band]) {
                val hz = bin * binStepHz
                collected += maxOf(0.0, minOf((hz - low) / (centre - low), (high - hz) / (high - centre)))
            }
        }
        weights = collected.toDoubleArray()
    }

    /** The weight of FFT bin [bin] in band [band]. */
    fun weight(bin: Int, band: Int): Double {
        require(bin in 0 until binCount && band in 0 until bandCount) { "bin $bin or band $band out of range" }
        val index = bin - firstBin[band]
        return if (index in 0 until length[band]) weights[start[band] + index] else 0.0
    }

    /** Writes into [bands] the weighted sums of [magnitudes] (`binCount` values) for every band. Allocates nothing. */
    fun project(magnitudes: DoubleArray, bands: DoubleArray) {
        require(magnitudes.size >= binCount && bands.size >= bandCount) { "magnitudes need $binCount values, bands $bandCount" }
        for (band in 0 until bandCount) {
            var sum = 0.0
            val bin0 = firstBin[band]
            val offset = start[band]
            for (i in 0 until length[band]) sum += weights[offset + i] * magnitudes[bin0 + i]
            bands[band] = sum
        }
    }

    private companion object {
        const val SPACING_HZ = 200.0 / 3
        const val LOG_START_HZ = 1_000.0
        const val LOG_START_MEL = LOG_START_HZ / SPACING_HZ
        val LOG_STEP = ln(6.4) / 27.0

        fun hzToMel(hz: Double): Double =
            if (hz >= LOG_START_HZ) LOG_START_MEL + ln(hz / LOG_START_HZ) / LOG_STEP else hz / SPACING_HZ

        fun melToHz(mel: Double): Double =
            if (mel >= LOG_START_MEL) LOG_START_HZ * exp(LOG_STEP * (mel - LOG_START_MEL)) else mel * SPACING_HZ
    }
}
