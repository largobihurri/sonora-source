package io.sonora.core.djengine.key

import io.sonora.core.audiodsp.fft.RealFft
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Turns one frame of mono samples into a 36-bin Harmonic Pitch Class Profile (HPCP), following Gomez, "Tonal
 * description of music audio signals" (PhD thesis, UPF, 2006), and Gomez, "Tonal description of polyphonic audio for
 * music content processing" (INFORMS Journal on Computing 18(3), 2006). Written from the publications; no code of
 * Essentia (AGPL) was consulted or translated.
 *
 * Per frame:
 * 1. Remove the mean (a DC offset has no pitch and its window leakage would otherwise fake low peaks), multiply by a
 *    Blackman-Harris 62 dB window and take the real FFT. Blackman-Harris (Harris 1978, three terms 0.44959, 0.49364,
 *    0.05677, sidelobes at -62 dB falling fast) is chosen over Hann because a loud bass partial must not leak enough
 *    into the neighbouring bins to be picked as spurious peaks; the price is a wider main lobe (+-3 bins), which the
 *    4096-sample frame at 44.1 kHz (10.8 Hz per bin) tolerates for the note range that carries harmony.
 * 2. Spectral whitening: the magnitudes are multiplied by `envelope^(WHITENING_EXPONENT - 1)`, a smooth spectral
 *    envelope (Klapuri-style band-wise compression: a band of envelope `e` ends up at about `e^WHITENING_EXPONENT`), so a loud
 *    bass or a bright instrument does not hide the weaker notes of the harmony. The envelope is the RMS of the
 *    magnitudes in triangular bands one third of an octave apart (own simplification of Klapuri's critical-band
 *    filterbank; a floor of 1e-4 of the loudest band keeps rounding noise from being amplified).
 * 3. Peak picking: local maxima of the whitened magnitude between [MIN_FREQUENCY_HZ] and [MAX_FREQUENCY_HZ], refined
 *    by parabolic interpolation of the log magnitude (exact for a Gaussian main lobe, which a Blackman-Harris lobe
 *    approximates), keeping the [MAX_PEAKS] strongest. Peaks below [PEAK_FLOOR] of the strongest are dropped (sidelobe
 *    and noise peaks; an addition of this implementation).
 * 4. Each peak `(f, a)` adds `a^2` to the bins near the pitch class of `f` and, as the peak may be the h-th harmonic of a
 *    note, near that of `f / h` for h = 1..[HARMONICS] with weight `HARMONIC_DECAY^(h-1)`. The bin weight is the cos^2
 *    window of 4/3 semitone width: `cos^2(pi / 2 * d / (l / 2))` for `|d| <= l / 2`, `d` the distance in semitones.
 * 5. The frame is divided by its maximum (unit max); a silent frame stays all zeros.
 *
 * Bin `3 * pc` is pitch class `pc` (0 = C .. 11 = B) at the reference tuning, so A is bin 27 and bin `b` is centred on
 * `reference * 2^((b - 27) / 36)` modulo octaves. A sharper tone moves to higher bins.
 *
 * Allocation-free after construction. Not thread-safe.
 */
internal class HpcpFrameAnalyzer(
    private val sampleRateHz: Int,
    private val frameSize: Int,
    private val referenceFrequencyHz: Double,
) {
    private val fft = RealFft(frameSize)
    private val binCount = fft.binCount
    private val window = DoubleArray(frameSize)
    private val windowed = FloatArray(frameSize)
    private val re = FloatArray(binCount)
    private val im = FloatArray(binCount)
    private val magnitude = DoubleArray(binCount)

    private val firstBin = ceil(MIN_FREQUENCY_HZ * frameSize / sampleRateHz).toInt()
    private val lastBin = minOf(floor(MAX_FREQUENCY_HZ * frameSize / sampleRateHz).toInt(), binCount - 1)
    private val magnitudeScale: Double

    // Whitening bands: bin k lies between bands bandOf[k] and bandOf[k] + 1 with weight (1 - bandFrac[k]) / bandFrac[k].
    private val bandOf = IntArray(binCount)
    private val bandFrac = DoubleArray(binCount)
    private val bandCount: Int
    private val bandWeight: DoubleArray
    private val bandEnergy: DoubleArray
    private val bandGain: DoubleArray

    private val peakFrequency = DoubleArray(MAX_PEAKS)
    private val peakAmplitude = DoubleArray(MAX_PEAKS)
    private val profile = DoubleArray(ChromaFrames.BINS_PER_FRAME)

    init {
        require(lastBin - firstBin >= 2) { "sample rate $sampleRateHz Hz / frame $frameSize leave no usable spectrum" }
        var sum = 0.0
        for (n in 0 until frameSize) {
            val x = 2 * PI * n / frameSize
            window[n] = WINDOW_A0 - WINDOW_A1 * cos(x) + WINDOW_A2 * cos(2 * x)
            sum += window[n]
        }
        magnitudeScale = 2.0 / sum // a sine of amplitude A then has a peak magnitude of about A
        val top = bandPosition(lastBin)
        bandCount = floor(top).toInt() + 2
        bandWeight = DoubleArray(bandCount)
        bandEnergy = DoubleArray(bandCount)
        bandGain = DoubleArray(bandCount)
        for (k in firstBin..lastBin) {
            val u = bandPosition(k)
            val j = floor(u).toInt()
            bandOf[k] = j
            bandFrac[k] = u - j
            bandWeight[j] += 1 - bandFrac[k]
            bandWeight[j + 1] += bandFrac[k]
        }
    }

    /** Position of [bin] on the band axis: thirds of an octave above [MIN_FREQUENCY_HZ]. */
    private fun bandPosition(bin: Int): Double =
        BANDS_PER_OCTAVE * ln(bin.toDouble() * sampleRateHz / frameSize / MIN_FREQUENCY_HZ) / LN2

    /** Computes the profile of the [frameSize] samples `frame[0 until frameSize]` into `out[outOffset until outOffset + 36]`. */
    fun analyze(frame: FloatArray, out: FloatArray, outOffset: Int) {
        var mean = 0.0
        for (n in 0 until frameSize) mean += frame[n]
        mean /= frameSize
        for (n in 0 until frameSize) windowed[n] = ((frame[n] - mean) * window[n]).toFloat()
        fft.forward(windowed, re, im)

        java.util.Arrays.fill(bandEnergy, 0.0)
        for (k in firstBin..lastBin) {
            val m = sqrt(re[k].toDouble() * re[k] + im[k].toDouble() * im[k]) * magnitudeScale
            magnitude[k] = m
            val e = m * m
            bandEnergy[bandOf[k]] += (1 - bandFrac[k]) * e
            bandEnergy[bandOf[k] + 1] += bandFrac[k] * e
        }
        var loudest = 0.0
        for (j in 0 until bandCount) {
            bandEnergy[j] = if (bandWeight[j] > 0) sqrt(bandEnergy[j] / bandWeight[j]) else 0.0
            if (bandEnergy[j] > loudest) loudest = bandEnergy[j]
        }
        if (!(loudest > SILENCE)) {
            java.util.Arrays.fill(out, outOffset, outOffset + ChromaFrames.BINS_PER_FRAME, 0f)
            return
        }
        val floor = loudest * ENVELOPE_FLOOR
        for (j in 0 until bandCount) bandGain[j] = maxOf(bandEnergy[j], floor).pow(WHITENING_EXPONENT - 1)
        for (k in firstBin..lastBin) {
            val j = bandOf[k]
            magnitude[k] *= (1 - bandFrac[k]) * bandGain[j] + bandFrac[k] * bandGain[j + 1]
        }

        val peaks = pickPeaks()
        accumulate(peaks)
        var max = 0.0
        for (b in profile.indices) if (profile[b] > max) max = profile[b]
        for (b in profile.indices) out[outOffset + b] = if (max > 0) (profile[b] / max).toFloat() else 0f
    }

    /** Fills [peakFrequency] and [peakAmplitude] with the strongest peaks and returns how many there are. */
    private fun pickPeaks(): Int {
        var count = 0
        var weakest = 0
        for (k in firstBin + 1 until lastBin) {
            val b = magnitude[k]
            val a = magnitude[k - 1]
            val c = magnitude[k + 1]
            if (!(b > a && b >= c && b > 0.0)) continue
            val la = ln(maxOf(a, TINY))
            val lb = ln(b)
            val lc = ln(maxOf(c, TINY))
            val shift = 0.5 * (la - lc) / (la - 2 * lb + lc) // in -0.5..0.5 as b is a local maximum
            val amplitude = exp(lb - 0.25 * (la - lc) * shift)
            val frequency = (k + shift) * sampleRateHz / frameSize
            if (count < MAX_PEAKS) {
                peakFrequency[count] = frequency
                peakAmplitude[count] = amplitude
                if (amplitude < peakAmplitude[weakest]) weakest = count
                count++
            } else if (amplitude > peakAmplitude[weakest]) {
                peakFrequency[weakest] = frequency
                peakAmplitude[weakest] = amplitude
                weakest = 0
                for (i in 1 until MAX_PEAKS) if (peakAmplitude[i] < peakAmplitude[weakest]) weakest = i
            }
        }
        return count
    }

    private fun accumulate(peakCount: Int) {
        java.util.Arrays.fill(profile, 0.0)
        var strongest = 0.0
        for (i in 0 until peakCount) if (peakAmplitude[i] > strongest) strongest = peakAmplitude[i]
        val bins = ChromaFrames.BINS_PER_FRAME
        for (i in 0 until peakCount) {
            if (peakAmplitude[i] < strongest * PEAK_FLOOR) continue
            val energy = peakAmplitude[i] * peakAmplitude[i]
            // Position in bins of the peak's pitch class, where the reference pitch (A) is bin 27.
            val position = bins * ln(peakFrequency[i] / referenceFrequencyHz) / LN2 + A_BIN
            for (h in 1..HARMONICS) {
                if (peakFrequency[i] / h < MIN_FREQUENCY_HZ) break
                val q = position - bins * LN_OF_HARMONIC[h] / LN2
                val weight = energy * HARMONIC_WEIGHT[h]
                val first = ceil(q - HALF_WINDOW_BINS).toInt()
                val last = floor(q + HALF_WINDOW_BINS).toInt()
                for (n in first..last) {
                    val d = n - q
                    if (d <= -HALF_WINDOW_BINS || d >= HALF_WINDOW_BINS) continue
                    val c = cos(PI / 2 * d / HALF_WINDOW_BINS)
                    profile[Math.floorMod(n, bins)] += weight * c * c
                }
            }
        }
    }

    companion object {
        const val MIN_FREQUENCY_HZ = 40.0
        const val MAX_FREQUENCY_HZ = 5_000.0
        const val MAX_PEAKS = 100

        /** Harmonics per peak (the peak itself and its 2nd..4th sub-harmonics are candidate fundamentals). */
        const val HARMONICS = 4

        /** Contribution of a peak as the h-th harmonic of a note is `HARMONIC_DECAY^(h-1)` of its own. */
        const val HARMONIC_DECAY = 0.6

        /** Weighting window length in semitones (4/3: 4 bins of 1/3 semitone, zero at the edges). */
        const val WINDOW_SEMITONES = 4.0 / 3.0

        /** Whitened magnitude is `magnitude * envelope^(WHITENING_EXPONENT - 1)`: a level difference shrinks to its cube root. */
        const val WHITENING_EXPONENT = 0.33

        /** Relative magnitude below which a peak of the frame is ignored. */
        const val PEAK_FLOOR = 1e-3

        private const val BANDS_PER_OCTAVE = 3.0
        private const val ENVELOPE_FLOOR = 1e-4

        /** Loudest band envelope (amplitude, full scale 1) under which the frame is silence: -120 dBFS. */
        private const val SILENCE = 1e-6
        private const val TINY = 1e-30
        private const val A_BIN = 27.0
        private const val HALF_WINDOW_BINS = WINDOW_SEMITONES / 2 * 3 // in bins of 1/3 semitone
        private val LN2 = ln(2.0)

        // Blackman-Harris 62 dB, three terms (Harris 1978).
        private const val WINDOW_A0 = 0.44959
        private const val WINDOW_A1 = 0.49364
        private const val WINDOW_A2 = 0.05677

        private val LN_OF_HARMONIC = DoubleArray(HARMONICS + 1) { if (it == 0) 0.0 else ln(it.toDouble()) }
        private val HARMONIC_WEIGHT = DoubleArray(HARMONICS + 1) { if (it == 0) 0.0 else HARMONIC_DECAY.pow(it - 1) }
    }
}
