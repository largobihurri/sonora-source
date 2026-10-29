package io.sonora.core.djengine.key

import io.sonora.core.djmodel.KeyEstimate
import io.sonora.core.domain.analysis.MusicalKey
import kotlin.math.sqrt

/** How the frames of a [ChromaFrames] are averaged into one 36-bin profile. */
enum class FrameWeighting {
    /** Every non-silent frame casts one vote: each is scaled to unit sum before averaging, whatever its loudness. */
    UNIFORM,

    /** Each non-silent frame counts as it is, so its weight is its energy (the sum of its bins): louder passages decide. */
    ENERGY,
}

/**
 * Global key from a track's chroma: average the 36-bin HPCP over the non-silent frames, correct the tuning, fold to 12
 * pitch classes, and correlate with the 24 major and minor key templates of a [KeyProfile].
 *
 * Method, after Faraldo, Gomez, Jorda and Herrera, "Key Estimation in Electronic Dance Music" (ECIR 2016, LNCS 9626),
 * Section 3.1, and the template matching of Krumhansl, "Cognitive Foundations of Musical Pitch" (1990) and Gomez,
 * "Tonal description of music audio signals" (PhD thesis, UPF, 2006):
 * 1. Frames whose energy (sum of bins) is zero, not finite, or below [SILENCE_RELATIVE] of the loudest frame are skipped:
 *    a normaliser turns dither noise into a full-scale random pattern, which must not vote. Negative and non-finite
 *    bins count as 0. The remaining frames are averaged with the chosen [FrameWeighting].
 * 2. Detuning correction, as in the paper: the averaged vector's largest bin says how far the track sits from the
 *    reference pitch. With bin `3 * pc` the centre of pitch class `pc`, a maximum on a bin of the form `3k + 1`
 *    (a third of a semitone sharp) shifts the fold one bin up, and `3k + 2` (flat) one bin down. This is done once per
 *    track and can be turned off.
 * 3. Fold 36 to 12: pitch class `pc` is the sum of the three bins centred on its (shifted) centre bin, cyclically.
 *    The three groups per semitone partition the 36 bins, so every bit of energy lands in exactly one pitch class
 *    (the alternative of correlating at 36-bin resolution with interpolated templates needs a tuning model for the
 *    templates and gains nothing once the tuning is corrected).
 * 4. Pearson correlation (cosine similarity of mean-centred vectors, as in the Krumhansl-Schmuckler algorithm; the
 *    paper uses cosine on uncentred vectors) of the 12 values with each of the 24 templates, the profile rotated to
 *    each tonic. The best one is the key and its correlation the strength.
 *
 * Bin convention (the HPCP sink's): bin `3 * pc` is pitch class `pc` (0 = C .. 11 = B, A at bin 27), sharper means a higher bin;
 * a different layout is
 * handled by [classify]'s `firstBinPitchClass`.
 *
 * `null` when there is nothing to decide: no frames, only silent frames, or a flat (or all-zero) profile, whose
 * correlation is undefined.
 */
object KeyClassifier {
    /** A frame is silent when its energy is below this fraction of the loudest frame's (-30 dB in energy). */
    const val SILENCE_RELATIVE: Double = 1e-3

    private const val PITCH_CLASSES = 12
    private const val BINS_PER_PITCH_CLASS = ChromaFrames.BINS_PER_FRAME / PITCH_CLASSES

    /** A profile this flat (standard deviation relative to its mean) has no key. */
    private const val FLAT_RELATIVE = 1e-6

    /**
     * The global key of [chroma], or `null` when there is none (see the class comment). [firstBinPitchClass] is the
     * pitch class (0 = C) at bin 0; [detuningCorrection] turns step 2 off. The estimate's `strength` is the best
     * correlation, which lies in 0..1 (the 12 rotations of a mean-centred template sum to zero, so the best of them
     * cannot be negative) and its method `hpcp-<profile id>/1`.
     */
    fun classify(
        chroma: ChromaFrames,
        profile: KeyProfile = KeyProfile.EDMA,
        weighting: FrameWeighting = FrameWeighting.UNIFORM,
        firstBinPitchClass: Int = 0,
        detuningCorrection: Boolean = true,
    ): KeyEstimate? {
        require(firstBinPitchClass in 0 until PITCH_CLASSES) { "firstBinPitchClass must be 0..11: $firstBinPitchClass" }
        val averaged = average(chroma, weighting) ?: return null
        val shift = if (detuningCorrection) detuning(averaged) else 0
        val pitchClasses = fold(averaged, shift, firstBinPitchClass)
        return bestKey(pitchClasses, profile)
    }

    /** The weighted mean of the non-silent frames, or `null` when there are none. */
    private fun average(chroma: ChromaFrames, weighting: FrameWeighting): DoubleArray? {
        val frameCount = chroma.frameCount
        val energies = DoubleArray(frameCount)
        var loudest = 0.0
        for (f in 0 until frameCount) {
            var energy = 0.0
            for (b in 0 until ChromaFrames.BINS_PER_FRAME) energy += clean(chroma[f, b])
            energies[f] = if (energy.isFinite()) energy else 0.0
            if (energies[f] > loudest) loudest = energies[f]
        }
        if (loudest <= 0.0) return null
        val floor = loudest * SILENCE_RELATIVE

        val sum = DoubleArray(ChromaFrames.BINS_PER_FRAME)
        var weightSum = 0.0
        for (f in 0 until frameCount) {
            if (energies[f] < floor || energies[f] <= 0.0) continue
            // ENERGY adds the raw bins (weight = energy); UNIFORM scales the frame to unit sum first.
            val scale = if (weighting == FrameWeighting.ENERGY) 1.0 else 1.0 / energies[f]
            for (b in 0 until ChromaFrames.BINS_PER_FRAME) sum[b] += scale * clean(chroma[f, b])
            weightSum += scale * energies[f]
        }
        if (weightSum <= 0.0) return null
        for (b in sum.indices) sum[b] /= weightSum
        return sum
    }

    private fun clean(value: Float): Double = if (value.isFinite() && value > 0f) value.toDouble() else 0.0

    /** -1, 0 or +1 bins: where the largest bin sits within its semitone (first maximum wins a tie). */
    private fun detuning(averaged: DoubleArray): Int {
        var peak = 0
        for (b in 1 until averaged.size) if (averaged[b] > averaged[peak]) peak = b
        return when (peak % BINS_PER_PITCH_CLASS) {
            1 -> 1
            2 -> -1
            else -> 0
        }
    }

    /** Pitch-class vector indexed 0 = C: sums of the three bins centred on `3 * k + shift`, k counted from the first bin. */
    private fun fold(averaged: DoubleArray, shift: Int, firstBinPitchClass: Int): DoubleArray {
        val bins = averaged.size
        val out = DoubleArray(PITCH_CLASSES)
        for (k in 0 until PITCH_CLASSES) {
            val centre = k * BINS_PER_PITCH_CLASS + shift
            var sum = 0.0
            for (j in -1..1) sum += averaged[Math.floorMod(centre + j, bins)]
            out[(k + firstBinPitchClass) % PITCH_CLASSES] = sum
        }
        return out
    }

    private fun bestKey(pitchClasses: DoubleArray, profile: KeyProfile): KeyEstimate? {
        val mean = pitchClasses.average()
        var norm = 0.0
        val centred = DoubleArray(PITCH_CLASSES) { pitchClasses[it] - mean }
        for (v in centred) norm += v * v
        norm = sqrt(norm)
        if (!(mean > 0.0) || norm <= FLAT_RELATIVE * mean * sqrt(PITCH_CLASSES.toDouble())) return null

        var bestIndex = -1
        var bestCorrelation = Double.NEGATIVE_INFINITY
        val template = DoubleArray(PITCH_CLASSES)
        for (minor in booleanArrayOf(false, true)) {
            var templateMean = 0.0
            for (d in 0 until PITCH_CLASSES) templateMean += profile.weight(minor, d)
            templateMean /= PITCH_CLASSES
            for (root in 0 until PITCH_CLASSES) {
                var templateNorm = 0.0
                for (pc in 0 until PITCH_CLASSES) {
                    template[pc] = profile.weight(minor, Math.floorMod(pc - root, PITCH_CLASSES)) - templateMean
                    templateNorm += template[pc] * template[pc]
                }
                var dot = 0.0
                for (pc in 0 until PITCH_CLASSES) dot += centred[pc] * template[pc]
                val correlation = dot / (norm * sqrt(templateNorm))
                if (correlation > bestCorrelation) {
                    bestCorrelation = correlation
                    bestIndex = root + 1 + if (minor) PITCH_CLASSES else 0
                }
            }
        }
        val key = MusicalKey.fromIndex(bestIndex) ?: return null
        if (!bestCorrelation.isFinite()) return null
        return KeyEstimate(key, bestCorrelation.coerceIn(0.0, 1.0), "hpcp-${profile.id}/1")
    }
}
