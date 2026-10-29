package io.sonora.tools.calibration

import io.sonora.core.domain.analysis.MusicalKey

/**
 * The MIREX audio key detection score (MIREX 2005 "Audio Key Finding"; Gomez, "Tonal description of music audio
 * signals", PhD thesis, UPF 2006; same weights as `mir_eval.key.weighted_score`): 1.0 for the same key, 0.5 for a
 * perfect fifth, 0.3 for the relative major or minor, 0.2 for the parallel major or minor, 0 otherwise.
 *
 * Keys are [MusicalKey]s, whose index follows qm-dsp: 1..12 are the major keys on C, Db, ... B and 13..24 the minor
 * keys on the same roots (so the tonic is `(index - 1) mod 12`).
 */
object KeyMetrics {

    /** The tonic as a pitch class, 0 = C ... 11 = B, whatever the mode. */
    fun tonic(key: MusicalKey): Int = (key.index - 1) % SEMITONES

    /**
     * Scores [estimate] against [reference]; a missing key on either side (qm-dsp reports none) scores 0.
     *
     * The fifth is the estimate a perfect fifth above the reference in the same mode, which is what MIREX and
     * `mir_eval` count by default. With [allowDescendingFifths] the fifth below also scores 0.5, which suits the
     * DJ view where the neighbours on the Camelot wheel are both harmonic mixes.
     */
    fun mirexScore(estimate: MusicalKey?, reference: MusicalKey?, allowDescendingFifths: Boolean = false): Double {
        if (estimate == null || reference == null) return 0.0
        val up = Math.floorMod(tonic(estimate) - tonic(reference), SEMITONES)
        val sameMode = estimate.isMinor == reference.isMinor
        return when {
            sameMode && up == 0 -> 1.0
            sameMode && (up == FIFTH || (allowDescendingFifths && up == SEMITONES - FIFTH)) -> 0.5
            !sameMode && !reference.isMinor && up == RELATIVE_MINOR_ABOVE_MAJOR -> 0.3
            !sameMode && reference.isMinor && up == RELATIVE_MAJOR_ABOVE_MINOR -> 0.3
            !sameMode && up == 0 -> 0.2
            else -> 0.0
        }
    }

    /** Mean [mirexScore] over `(estimate, reference)` pairs; `NaN` for no pairs. */
    fun meanScore(pairs: List<Pair<MusicalKey?, MusicalKey?>>, allowDescendingFifths: Boolean = false): Double {
        if (pairs.isEmpty()) return Double.NaN
        return pairs.sumOf { (estimate, reference) -> mirexScore(estimate, reference, allowDescendingFifths) } / pairs.size
    }

    private const val SEMITONES = 12
    private const val FIFTH = 7
    private const val RELATIVE_MINOR_ABOVE_MAJOR = 9
    private const val RELATIVE_MAJOR_ABOVE_MINOR = 3
}
