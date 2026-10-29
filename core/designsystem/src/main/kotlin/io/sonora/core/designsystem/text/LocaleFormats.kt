package io.sonora.core.designsystem.text

import io.sonora.core.designsystem.R
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Numbers, file sizes, dates and durations formatted for a locale, so they follow the app's language. */
object LocaleFormats {
    fun integer(value: Long, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(value)

    fun integer(value: Int, locale: Locale): String = integer(value.toLong(), locale)

    /** [value] with at most [maxFractionDigits] decimals and no trailing zeros: `44,1` in Spanish. */
    fun decimal(value: Double, maxFractionDigits: Int, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = maxFractionDigits
            isGroupingUsed = true
        }.format(value)

    /**
     * Binary units with one decimal (`512 bytes`, `1.5 KiB`, `42.0 MiB`); the byte count is a plural of [strings]. The
     * number and its unit are joined by a no-break space ([UNIT_SPACE]), so a wrapped line never leaves the unit alone.
     */
    fun fileSize(bytes: Long, strings: Strings): String {
        if (bytes < 1024) {
            return strings.plural(R.plurals.ds_file_size_bytes, bytes.toInt(), integer(bytes, strings.locale))
        }
        var value = bytes / 1024.0
        var unit = 0
        while (unit < SIZE_UNITS.lastIndex && Math.round(value * 10) >= 1024 * 10) {
            value /= 1024
            unit++
        }
        val number = NumberFormat.getNumberInstance(strings.locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }.format(value)
        return "$number$UNIT_SPACE${SIZE_UNITS[unit]}"
    }

    /** Joins a number to its unit (`210,6 KiB`, `44,1 kHz`): a no-break space, so the two never end up on different lines. */
    const val UNIT_SPACE = '\u00A0'

    /** Medium date and short time, such as `3 oct 2026, 14:30`. */
    fun dateTime(ms: Long, zone: ZoneId, locale: Locale): String =
        format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT), ms, zone, locale)

    /** Short time, such as `14:30` or `2:30 PM`. */
    fun time(ms: Long, zone: ZoneId, locale: Locale): String =
        format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT), ms, zone, locale)

    /** `3:25`, or `1:02:03` past an hour; the same digits in every language. */
    fun duration(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0) / 1000
        val hours = totalSeconds / 3600
        val minutes = totalSeconds / 60 % 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    /** Narrow and no-break spaces (CLDR's "2:30 PM") become plain spaces, so text wraps and compares predictably. */
    private fun format(formatter: DateTimeFormatter, ms: Long, zone: ZoneId, locale: Locale): String =
        formatter.withLocale(locale).withZone(zone).format(Instant.ofEpochMilli(ms))
            .replace(' ', ' ')
            .replace(' ', ' ')

    private val SIZE_UNITS = listOf("KiB", "MiB", "GiB", "TiB")
}
