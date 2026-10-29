package io.sonora.core.domain.library

import java.text.Normalizer

/** Display cleanup shared by tag readers and the grouping rules. */
object TagText {
    private val CONTROL_OR_BOM = Regex("[\\p{Cntrl}\\uFEFF]")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Unicode NFC, control characters (NUL padding, BOM) dropped, whitespace runs collapsed, trimmed;
     * `null` when nothing is left.
     */
    fun clean(value: String?): String? = value
        ?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }
        ?.replace(CONTROL_OR_BOM, " ")
        ?.replace(WHITESPACE, " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
