package io.sonora.feature.dj.controls

import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.core.domain.playback.TempoRange
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** How the DJ tempo section reads: "128.0" BPM, "+1.25%", "0%", "±10", "+0.1", in the locale's number style. */
object DjTempoText {

    /** Shown for an unknown value (no BPM, no key). */
    const val NO_VALUE = "—"

    private const val MINUS = "−"

    /** [bpm] with exactly one decimal and no grouping ("128.0", "128,0"); [NO_VALUE] when unknown or not above 0. */
    fun bpm(bpm: Double?, locale: Locale): String {
        if (bpm == null || !(bpm > 0.0) || bpm.isInfinite()) return NO_VALUE
        return oneDecimal(locale).format(bpm)
    }

    /**
     * The tempo [percent] off normal, with two decimals, in [locale]'s percent style, signed next to the number with a
     * true minus sign: "+1.25%", "−3.00%", "+1,25 %" (Spanish), "% +1,25" (Basque). 0 (and an unknown value) reads
     * "+0.00%", as the readout always shows a signed tempo (D2.7b); a value that rounds to 0 never shows "−0.00%".
     */
    fun tempoPercent(percent: Double, locale: Locale): String {
        val format = percentFormat(locale, fractionDigits = 2)
        val value = if (percent.isNaN() || abs(percent) < HALF_HUNDREDTH) 0.0 else percent
        if (format !is DecimalFormat) return format.format(value / 100.0)
        val prefix = format.positivePrefix
        format.positivePrefix = "$prefix+"
        format.negativePrefix = "$prefix$MINUS"
        format.negativeSuffix = format.positiveSuffix
        return format.format(value / 100.0)
    }

    /** Below this (in percent) a tempo shows as 0 with two decimals. */
    private const val HALF_HUNDREDTH = 0.005

    /** The reset button's label, 0 % in [locale]'s style: "0%", "0 %". */
    fun zeroPercent(locale: Locale): String = percentFormat(locale, fractionDigits = 0).format(0.0)

    /** The nudge step, 0.1 BPM, as a number: "0.1", "0,1". */
    fun nudgeStep(locale: Locale): String = oneDecimal(locale).format(NUDGE_BPM)

    /** A nudge button's label: "−0.1" for a negative [direction], "+0.1" otherwise. */
    fun nudgeLabel(direction: Int, locale: Locale): String = (if (direction < 0) MINUS else "+") + nudgeStep(locale)

    /** A symmetric range as "±6", "±10", "±16"; null for [TempoRange.WIDE], which has a word of its own. */
    fun rangeLabel(range: TempoRange, locale: Locale): String? {
        if (range == TempoRange.WIDE) return null
        val percent = (range.maxBp - PlaybackTempo.NORMAL_SPEED_BASIS_POINTS) / 100
        return "±" + NumberFormat.getIntegerInstance(locale).format(percent)
    }

    /** The BPM a nudge button moves the tempo by. */
    const val NUDGE_BPM = 0.1

    private fun oneDecimal(locale: Locale): NumberFormat = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
        isGroupingUsed = false
    }

    private fun percentFormat(locale: Locale, fractionDigits: Int): NumberFormat = NumberFormat.getPercentInstance(locale).apply {
        minimumFractionDigits = fractionDigits
        maximumFractionDigits = fractionDigits
    }
}
