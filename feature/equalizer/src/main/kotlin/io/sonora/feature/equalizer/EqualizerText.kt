package io.sonora.feature.equalizer

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

/** Labels and axes of the equalizer screen, kept out of the composables so they run in JVM tests. */
internal object EqualizerText {
    /** The response curve's range: the audible band. */
    const val LOWEST_HZ = 20.0
    const val HIGHEST_HZ = 20_000.0

    /** A band's nominal frequency as hardware equalizers print it: `31`, `500`, `1k`, `1.4k` (decimal mark of [locale]). */
    fun frequency(nominalHz: Double, locale: Locale): String =
        if (nominalHz < 1_000) {
            pattern("0", locale).format(nominalHz)
        } else {
            pattern("0.#", locale).format(nominalHz / 1_000) + "k"
        }

    /** A gain with its sign and one decimal: `+3.5`, `−12.0` (a true minus sign), `0.0`. */
    fun gain(gainDb: Float, locale: Locale): String {
        val magnitude = pattern("0.0", locale).format(kotlin.math.abs(gainDb.toDouble()))
        return when {
            gainDb > 0 -> "+$magnitude"
            gainDb < 0 -> "−$magnitude"
            else -> magnitude
        }
    }

    /** [count] frequencies spread evenly on a log scale from [LOWEST_HZ] to [HIGHEST_HZ]. */
    fun curveFrequencies(count: Int): DoubleArray {
        val step = ln(HIGHEST_HZ / LOWEST_HZ) / (count - 1)
        return DoubleArray(count) { LOWEST_HZ * exp(step * it) }
    }

    /** Where [frequencyHz] sits across the curve, from 0 (20 Hz) to 1 (20 kHz). */
    fun position(frequencyHz: Double): Float = (ln(frequencyHz / LOWEST_HZ) / ln(HIGHEST_HZ / LOWEST_HZ)).toFloat()

    private fun pattern(pattern: String, locale: Locale) = DecimalFormat(pattern, DecimalFormatSymbols.getInstance(locale))
}
