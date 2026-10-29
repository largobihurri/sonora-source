package io.sonora.core.rekordbox.pdb

import io.sonora.core.domain.analysis.MusicalKey

private val MAJOR_ROOTS = listOf("C", "Db", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
private val MINOR_ROOTS = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "G#", "A", "Bb", "B")

/**
 * The key's name as rekordbox stores it in the `keys` table of `export.pdb`: the bare root for majors (`E`), the root
 * plus `m` for minors (`Am`). Sonora writes the alphanumeric names instead ([PdbWriter]): the XDJ-700 (firmware 1.13)
 * shows the table's names verbatim, whatever `DEVSETTING.DAT` asks for. Kept for comparing with rekordbox's exports.
 *
 * Real exports confirm the natural keys (`Dm`, `Am`, `E`, `D`, `Fm`); the accidentals follow the usual DJ spelling (flats
 * except F#, C#m and G#m) and are not confirmed against rekordbox yet.
 */
val MusicalKey.classicName: String
    get() = if (isMinor) MINOR_ROOTS[index - 13] + "m" else MAJOR_ROOTS[index - 1]
