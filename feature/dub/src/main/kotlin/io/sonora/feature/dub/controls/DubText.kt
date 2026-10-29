package io.sonora.feature.dub.controls

import io.sonora.core.designsystem.text.LocaleFormats
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The DUB controls' numbers, formatted for a locale: frequencies ("80 Hz", "1.2 kHz"), levels ("+6 dB", "-∞ dB") and
 * resonance ("2.4"). A number and its unit are joined by a no-break space, so a narrow label never strands the unit.
 */
object DubText {
    private const val UNIT_SPACE = ' '

    /** Whole hertz below 1 kHz, kilohertz with at most one decimal from there; unusable input reads as 0 Hz. */
    fun frequency(hz: Float, locale: Locale): String {
        val rounded = if (hz.isFinite() && hz > 0f) hz.roundToInt() else 0
        if (rounded < 1_000) return "${LocaleFormats.integer(rounded, locale)}${UNIT_SPACE}Hz"
        val khz = (hz / 100f).roundToInt() / 10.0
        return "${LocaleFormats.decimal(khz, 1, locale)}${UNIT_SPACE}kHz"
    }

    /** A signed level with at most one decimal ("0 dB" has no sign); minus infinity, or NaN, reads "-∞ dB". */
    fun decibels(db: Double, locale: Locale): String {
        if (db.isNaN() || db == Double.NEGATIVE_INFINITY) return "-∞${UNIT_SPACE}dB"
        val tenths = (db * 10.0).roundToInt()
        val number = LocaleFormats.decimal(tenths / 10.0, 1, locale)
        return when {
            tenths == 0 -> "0${UNIT_SPACE}dB"
            tenths > 0 -> "+$number${UNIT_SPACE}dB"
            else -> "$number${UNIT_SPACE}dB"
        }
    }

    /** A filter Q with at most one decimal. */
    fun q(q: Float, locale: Locale): String {
        val tenths = if (q.isFinite()) (q * 10f).roundToInt() else 0
        return LocaleFormats.decimal(tenths / 10.0, 1, locale)
    }
}
