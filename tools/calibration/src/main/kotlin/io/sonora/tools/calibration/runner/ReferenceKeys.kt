package io.sonora.tools.calibration.runner

import io.sonora.core.domain.analysis.MusicalKey

/**
 * Reads the key names of the rekordbox `keys` table (and other common spellings) as [MusicalKey]s: classic names
 * (`Am`, `F#m`, `Bb`, `Dbm`, the Unicode sharp and flat too), spelled-out modes (`A minor`, `Eb major`) and the
 * alphanumeric notation (`8A`). Enharmonic spellings fold onto the same key (`Gbm` = `F#m`, `Cb` = `B`).
 */
object ReferenceKeys {

    /** The key [name] spells, or `null` for `null`, blank or anything else (chords, unknown letters). */
    fun parse(name: String?): MusicalKey? {
        if (name == null) return null
        val text = name.trim().replace('♯', '#').replace('♭', 'b')
        if (text.isEmpty()) return null
        MusicalKey.fromAlphanumeric(text)?.let { return it }

        val match = CLASSIC.matchEntire(text) ?: return null
        val (letter, accidental, mode) = match.destructured
        val natural = NATURALS[letter.uppercase()[0]] ?: return null
        val pitchClass = Math.floorMod(natural + accidental.sumOf { if (it == '#') 1L else -1L }.toInt(), SEMITONES)
        val minor = when (mode.trim().lowercase()) {
            "", "maj", "major" -> false
            "m", "min", "minor" -> true
            else -> return null
        }
        return MusicalKey.fromIndex(pitchClass + 1 + if (minor) SEMITONES else 0)
    }

    private const val SEMITONES = 12
    private val NATURALS = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)
    private val CLASSIC = Regex("([A-Ga-g])([#b]{0,2})\\s*(m|min|minor|maj|major|)", RegexOption.IGNORE_CASE)
}
