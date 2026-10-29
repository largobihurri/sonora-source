package io.sonora.core.domain.analysis

/**
 * The 24 major and minor keys, numbered as qm-dsp's key detector reports them: [index] 1..12 are the major keys on
 * C, Db, D, Eb, E, F, F#, G, Ab, A, Bb, B; 13..24 the minor keys on the same roots.
 *
 * Keys are shown and exported in the alphanumeric (Camelot-style) notation that rekordbox calls "Alphanumeric":
 * [alphanumeric] is `1A`..`12A` for minor and `1B`..`12B` for major keys. Neighbouring numbers are a fifth apart and a
 * minor key shares its number with its relative major (8A = A minor, 8B = C major), which is what DJs mix by.
 */
enum class MusicalKey(
    /** The qm-dsp key index, 1..24: the stable value to persist. */
    val index: Int,
    /** The alphanumeric name (`8A`, `8B`, ...), as shown on the players. */
    val alphanumeric: String,
) {
    C_MAJOR(1, "8B"),
    D_FLAT_MAJOR(2, "3B"),
    D_MAJOR(3, "10B"),
    E_FLAT_MAJOR(4, "5B"),
    E_MAJOR(5, "12B"),
    F_MAJOR(6, "7B"),
    F_SHARP_MAJOR(7, "2B"),
    G_MAJOR(8, "9B"),
    A_FLAT_MAJOR(9, "4B"),
    A_MAJOR(10, "11B"),
    B_FLAT_MAJOR(11, "6B"),
    B_MAJOR(12, "1B"),
    C_MINOR(13, "5A"),
    C_SHARP_MINOR(14, "12A"),
    D_MINOR(15, "7A"),
    E_FLAT_MINOR(16, "2A"),
    E_MINOR(17, "9A"),
    F_MINOR(18, "4A"),
    F_SHARP_MINOR(19, "11A"),
    G_MINOR(20, "6A"),
    G_SHARP_MINOR(21, "1A"),
    A_MINOR(22, "8A"),
    B_FLAT_MINOR(23, "3A"),
    B_MINOR(24, "10A"),
    ;

    val isMinor: Boolean get() = index > MAJOR_KEY_COUNT

    companion object {
        private const val MAJOR_KEY_COUNT = 12

        private val byAlphanumeric: Map<String, MusicalKey> = entries.associateBy { it.alphanumeric }

        /** The key for a qm-dsp index, or `null` outside 1..24 (qm-dsp reports 0 when it found no key). */
        fun fromIndex(index: Int): MusicalKey? = entries.getOrNull(index - 1)

        /** Parses an alphanumeric name such as `8A` (case-insensitive, surrounding blanks ignored); `null` if unknown. */
        fun fromAlphanumeric(text: String): MusicalKey? = byAlphanumeric[text.trim().uppercase()]
    }
}
