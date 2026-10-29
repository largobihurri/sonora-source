package io.sonora.core.domain.playback.output

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Sample rates as short text in kilohertz that never breaks across lines. */
object Kilohertz {
    private const val NBSP = " "
    private const val LISTED_RATES = 3

    /** A rate in kilohertz without trailing zeros, in the locale's number format: 44100 gives "44.1" (or "44,1"). */
    fun format(rate: Int, locale: Locale): String =
        DecimalFormat("0.##", DecimalFormatSymbols(locale)).apply { roundingMode = RoundingMode.HALF_UP }.format(rate / 1_000.0)

    /**
     * Up to three distinct rates listed ("44.1 · 48 · 96 kHz"), more as a range ("44.1–192 kHz"), or `null` when there are
     * none. Spaces are non-breaking so the text stays on one line.
     */
    fun label(rates: List<Int>, locale: Locale): String? {
        val sorted = rates.filter { it > 0 }.distinct().sorted()
        if (sorted.isEmpty()) return null
        val body = if (sorted.size <= LISTED_RATES) {
            sorted.joinToString("$NBSP·$NBSP") { format(it, locale) }
        } else {
            "${format(sorted.first(), locale)}–${format(sorted.last(), locale)}"
        }
        return "$body${NBSP}kHz"
    }
}
