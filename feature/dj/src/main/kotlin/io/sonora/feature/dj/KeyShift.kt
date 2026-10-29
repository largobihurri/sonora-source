package io.sonora.feature.dj

import io.sonora.core.domain.analysis.MusicalKey
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Where a key lands when the music plays at another pitch. A pitch change of [pitchFactor] is `12 log2(factor)`
 * semitones, rounded to the nearest whole one (a DJ reads the key as a note, not as cents).
 *
 * On the Camelot-style wheel ([MusicalKey.alphanumeric]) a semitone up is seven places clockwise (a fifth is one place,
 * and a semitone is seven fifths up modulo the octave), whatever the letter: C major 8B is a semitone below D flat major
 * 3B. The mode never changes, only the number.
 */
object KeyShift {
    private const val SEMITONES_PER_OCTAVE = 12
    private const val WHEEL_PLACES_PER_SEMITONE = 7

    /**
     * [key] as it sounds when played with the frequency factor [pitchFactor] (1 is unchanged, 2 an octave up). A factor
     * that is not a positive finite number leaves the key as it is.
     */
    fun shifted(key: MusicalKey, pitchFactor: Double): MusicalKey {
        if (!pitchFactor.isFinite() || pitchFactor <= 0.0) return key
        val semitones = (SEMITONES_PER_OCTAVE * log2(pitchFactor)).roundToInt()
        return shiftedBy(key, semitones)
    }

    private fun shiftedBy(key: MusicalKey, semitones: Int): MusicalKey {
        val name = key.alphanumeric
        val number = name.dropLast(1).toInt()
        val letter = name.last()
        val moved = Math.floorMod(number - 1 + WHEEL_PLACES_PER_SEMITONE * (semitones % SEMITONES_PER_OCTAVE), SEMITONES_PER_OCTAVE) + 1
        return MusicalKey.fromAlphanumeric("$moved$letter") ?: key
    }
}
