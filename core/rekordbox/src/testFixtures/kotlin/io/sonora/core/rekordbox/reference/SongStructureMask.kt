package io.sonora.core.rekordbox.reference

/**
 * The XOR mask rekordbox 6 and later put on the `PSSI` song structure section of an export, as published by Deep Symmetry
 * ("Rekordbox Export Structure Analysis", https://djl-analysis.deepsymmetry.org/rekordbox-export-analysis/anlz.html,
 * "Song Structure Tag", analysis contributed by Michael Ganss) and implemented in crate-digger's
 * `rekordbox_anlz.ksy` (https://github.com/Deep-Symmetry/crate-digger).
 *
 * Every byte of the section from offset `0x12` (just after `len_entries`, the `u16` at `0x10`) to its end is XORed with
 * a cycling 19-byte key: [PATTERN] with `len_entries` added to each byte (modulo 256). The operation is its own
 * inverse. A masked file is told from an unmasked one by the mood word at `0x12`, which is 1..3 when readable and much
 * larger when masked ([looksMasked]).
 */
object SongStructureMask {
    /** The base pattern, before `len_entries` is added to each byte. */
    val PATTERN: IntArray = intArrayOf(
        0xCB, 0xE1, 0xEE, 0xFA, 0xE5, 0xEE, 0xAD, 0xEE, 0xE9, 0xD2, 0xE9, 0xEB, 0xE1, 0xE9, 0xF3, 0xE8, 0xE9, 0xF4, 0xE1,
    )

    /** Offset of the first masked byte inside the section. */
    const val MASK_START: Int = 0x12

    /** Offset of `len_entries` (`u16`, big-endian), the number added to each pattern byte. */
    const val LEN_ENTRIES_AT: Int = 0x10

    /** Moods above this are not a mood: the tag is masked (crate-digger uses the same threshold). */
    private const val MAX_PLAUSIBLE_MOOD = 20

    /** True when the mood word of [section] (a whole `PSSI` section) is too large to be a mood. */
    fun looksMasked(section: ByteArray): Boolean {
        if (section.size < MASK_START + 2) return false
        val mood = ((section[MASK_START].toInt() and 0xFF) shl 8) or (section[MASK_START + 1].toInt() and 0xFF)
        return mood > MAX_PLAUSIBLE_MOOD
    }

    /** [section] with the mask XORed over `0x12` to its end; masks an unmasked section and unmasks a masked one. */
    fun xor(section: ByteArray): ByteArray {
        val out = section.copyOf()
        if (out.size < LEN_ENTRIES_AT + 2) return out
        val lenEntries = ((out[LEN_ENTRIES_AT].toInt() and 0xFF) shl 8) or (out[LEN_ENTRIES_AT + 1].toInt() and 0xFF)
        for (i in MASK_START until out.size) {
            val key = (PATTERN[(i - MASK_START) % PATTERN.size] + lenEntries) and 0xFF
            out[i] = (out[i].toInt() xor key).toByte()
        }
        return out
    }
}
