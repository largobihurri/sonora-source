package io.sonora.core.domain.export

import io.sonora.core.domain.library.TagText

/**
 * Tag values made fit for a device library: text cleaned like the library's tags and bounded so that every row of
 * `export.pdb` fits a page, numbers dropped when they make no sense, so a bad tag never stops an export.
 */
object ExportText {
    /** Longest title, artist, album or genre, in UTF-16 units. */
    const val MAX_TEXT_CHARS = 256

    /** Cleaned [text] (NFC, no control characters, single spaces) cut to [maxChars]; `null` when blank. */
    fun clamp(text: String?, maxChars: Int = MAX_TEXT_CHARS): String? =
        TagText.clean(text)?.let { FatNames.cut(it, maxChars).trimEnd() }?.takeIf { it.isNotEmpty() }

    /** A plausible year (1..9999), else `null`. */
    fun year(value: Int?): Int? = value?.takeIf { it in PLAUSIBLE }

    /** A plausible track number (1..9999), else `null`. */
    fun trackNumber(value: Int?): Int? = value?.takeIf { it in PLAUSIBLE }

    /** The first four-digit year in a tag date such as `2019-04-01` or `2019`, else `null`. */
    fun yearOf(date: String?): Int? =
        date?.let { FOUR_DIGITS.find(it)?.value?.toInt() }?.let(::year)

    private val PLAUSIBLE = 1..9999
    private val FOUR_DIGITS = Regex("(?<!\\d)\\d{4}(?!\\d)")
}
