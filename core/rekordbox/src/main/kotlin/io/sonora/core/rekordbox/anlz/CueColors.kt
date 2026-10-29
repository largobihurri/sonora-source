package io.sonora.core.rekordbox.anlz

/**
 * rekordbox's cue colours: the 62 codes of a hot cue and the 8 colours of a memory cue, and the nearest of each to an
 * arbitrary RGB.
 *
 * A hot cue's `PCP2` entry holds its explicit RGB next to a colour code (1..62; 0 for none); the CDJ-2000NXS2 and later
 * draw the code, rekordbox itself the RGB. The codes run round the hue wheel from blue (1) through cyan, green,
 * yellow, orange and red to pink and violet (62). [HOT_CUE_DISPLAY_RGB] lists the colour the players show for each
 * code, the values Deep Symmetry's beat-link reads from rekordbox exports in `CueList.findRekordboxColor` (a table of
 * facts about Pioneer's palette; checked here against the codes and RGBs of the cues in a rekordbox 6 export, whose
 * explicit RGBs are the saturated versions of the same hues, e.g. code 0x20 with `ffe800`, 0x16 with `1aff00`, 0x31 with
 * `ff00a1`). [hotCueCode] picks the code whose display colour is nearest the cue's RGB.
 *
 * A memory cue or loop is coloured by a row of the `colors` table of `export.pdb` (the same table coloured tracks use):
 * 1 Pink, 2 Red, 3 Orange, 4 Yellow, 5 Green, 6 Aqua, 7 Blue, 8 Purple, 0 for none. [MEMORY_RGB] holds a nominal RGB
 * for each (rekordbox shows them by name, so the values are only used to find the nearest).
 */
object CueColors {

    /** The display colour of codes 1..62, index 0 being code 1. */
    val HOT_CUE_DISPLAY_RGB: List<Int> = listOf(
        0x305AFF, 0x5073FF, 0x508CFF, 0x50A0FF, 0x50B4FF, 0x50B0F2, 0x50AEE8, 0x45ACDB,
        0x00E0FF, 0x19DAF0, 0x32D2E6, 0x21B4B9, 0x20AAA0, 0x1FA392, 0x19A08C, 0x14A584,
        0x14AA7D, 0x10B176, 0x30D26E, 0x37DE5A, 0x3CEB50, 0x28E214, 0x7DC13D, 0x8CC832,
        0x9BD723, 0xA5E116, 0xA5DC0A, 0xAAD208, 0xB4C805, 0xB4BE04, 0xBAB404, 0xC3AF04,
        0xE1AA00, 0xFFA000, 0xFF9600, 0xFF8C00, 0xFF7500, 0xE0641B, 0xE0461E, 0xE0301E,
        0xE02823, 0xE62828, 0xFF376F, 0xFF2D6F, 0xFF127B, 0xF51E8C, 0xEB2DA0, 0xE637B4,
        0xDE44CF, 0xDE448D, 0xE630B4, 0xE619DC, 0xE600FF, 0xDC00FF, 0xCC00FF, 0xB432FF,
        0xB93CFF, 0xC542FF, 0xAA5AFF, 0xAA72FF, 0x8272FF, 0x6473FF,
    )

    /** The names of the memory colours 1..8 (rows of the `colors` table). */
    val MEMORY_NAMES: List<String> = listOf("Pink", "Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple")

    /** A nominal RGB for each memory colour 1..8, index 0 being 1. */
    val MEMORY_RGB: List<Int> = listOf(0xFF1AA1, 0xE62828, 0xFF8C00, 0xFFE800, 0x28E214, 0x00C4FF, 0x305AFF, 0xAA5AFF)

    /** The display colour of hot cue colour [code] (1..62). */
    fun hotCueRgb(code: Int): Int {
        require(code in 1..HOT_CUE_DISPLAY_RGB.size) { "colour code must be 1..${HOT_CUE_DISPLAY_RGB.size}: $code" }
        return HOT_CUE_DISPLAY_RGB[code - 1]
    }

    /** The code (1..62) whose display colour is nearest [rgb] (`0xRRGGBB`). */
    fun hotCueCode(rgb: Int): Int = nearest(rgb, HOT_CUE_DISPLAY_RGB) + 1

    /** The nominal RGB of memory colour [id] (1..8). */
    fun memoryRgb(id: Int): Int {
        require(id in 1..MEMORY_RGB.size) { "memory colour must be 1..${MEMORY_RGB.size}: $id" }
        return MEMORY_RGB[id - 1]
    }

    /** The memory colour (1..8) nearest [rgb]. */
    fun memoryColorId(rgb: Int): Int = nearest(rgb, MEMORY_RGB) + 1

    /** Index of the colour of [palette] nearest [rgb]: the "redmean" distance, which follows the eye's weights well. */
    private fun nearest(rgb: Int, palette: List<Int>): Int {
        var best = 0
        var bestDistance = Double.MAX_VALUE
        palette.forEachIndexed { index, candidate ->
            val d = distance(rgb, candidate)
            if (d < bestDistance) {
                best = index
                bestDistance = d
            }
        }
        return best
    }

    private fun distance(a: Int, b: Int): Double {
        val dr = (a shr 16 and 0xFF) - (b shr 16 and 0xFF)
        val dg = (a shr 8 and 0xFF) - (b shr 8 and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        val meanRed = ((a shr 16 and 0xFF) + (b shr 16 and 0xFF)) / 2.0
        return (2 + meanRed / 256) * dr * dr + 4.0 * dg * dg + (2 + (255 - meanRed) / 256) * db * db
    }
}
