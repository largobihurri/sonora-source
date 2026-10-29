package io.sonora.core.djmodel

/**
 * The colours a cue can have: sixteen hues in the order of rekordbox's colour picker (warm to cool, then neutral).
 *
 * The RGB values and the order are Sonora's own: rekordbox writes an explicit RGB with every cue (PCO2) next to a
 * colour code of its own, and the USB export writes the RGB of the cue and the code of the nearest colour rekordbox
 * knows. The defaults follow the CDJ's: pad A green, memory cues red.
 */
object CuePalette {

    /** The number of colours: palette indices are 1..[SIZE]. */
    const val SIZE: Int = 16

    /** Every colour, in palette order (index 1 first). */
    val COLORS: List<CueColor> = listOf(
        0xFF4FA3, // 1 pink
        0xFF3B30, // 2 red
        0xFF9500, // 3 orange
        0xFFD60A, // 4 yellow
        0xA6E22E, // 5 lime
        0x28E214, // 6 green
        0x1FD6A5, // 7 mint
        0x30C8E8, // 8 aqua
        0x3A9BFF, // 9 sky
        0x305AFF, // 10 blue
        0x6A4CFF, // 11 indigo
        0xB04CFF, // 12 purple
        0xE040FB, // 13 magenta
        0xFF6B81, // 14 rose
        0xC8915A, // 15 tan
        0xE6E6E6, // 16 white
    ).mapIndexed { i, rgb -> CueColor(i + 1, rgb) }

    /** The colour of palette [index], or `null` outside 1..[SIZE]. */
    fun byIndex(index: Int): CueColor? = COLORS.getOrNull(index - 1)

    /** The palette index of each pad's default colour: A green, B red, C orange, D yellow, E aqua, F blue, G purple, H pink. */
    private val HOT_DEFAULTS = intArrayOf(6, 2, 3, 4, 8, 10, 12, 1)

    /** The colour a new hot cue on [slot] (0..7) gets. */
    fun defaultHot(slot: Int): CueColor = COLORS[HOT_DEFAULTS[slot] - 1]

    /** The colour a new memory cue gets. */
    val MEMORY_DEFAULT: CueColor = COLORS[1]
}
