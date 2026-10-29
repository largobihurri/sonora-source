package io.sonora.core.designsystem.text

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Test

class LocaleFormatsTest {

    private val english = Locale.forLanguageTag("en")
    private val spanish = Locale.forLanguageTag("es")
    private val basque = Locale.forLanguageTag("eu")

    /** Reads "N byte(s)" the way the English plural resource does, in [locale]. */
    private fun bytes(locale: Locale) = object : Strings {
        override val locale = locale
        override fun get(id: Int, vararg args: Any) = error("not used")
        override fun plural(id: Int, count: Int, vararg args: Any) =
            "${args[0]} ${if (count == 1) "byte" else "bytes"}"
    }

    @Test
    fun `integers group thousands the way the locale does`() {
        assertThat(LocaleFormats.integer(1_260, english)).isEqualTo("1,260")
        assertThat(LocaleFormats.integer(1_260, spanish)).isEqualTo("1.260")
        assertThat(LocaleFormats.integer(12, basque)).isEqualTo("12")
    }

    @Test
    fun `decimals use the locale's separator and drop trailing zeros`() {
        assertThat(LocaleFormats.decimal(44.1, maxFractionDigits = 3, locale = english)).isEqualTo("44.1")
        assertThat(LocaleFormats.decimal(44.1, maxFractionDigits = 3, locale = spanish)).isEqualTo("44,1")
        assertThat(LocaleFormats.decimal(48.0, maxFractionDigits = 3, locale = basque)).isEqualTo("48")
        assertThat(LocaleFormats.decimal(11.025, maxFractionDigits = 3, locale = basque)).isEqualTo("11,025")
    }

    @Test
    fun `file sizes use binary units with one decimal in the locale's format, the unit never wrapped away`() {
        val en = bytes(english)
        assertThat(LocaleFormats.fileSize(0, en)).isEqualTo("0 bytes")
        assertThat(LocaleFormats.fileSize(1, en)).isEqualTo("1 byte")
        assertThat(LocaleFormats.fileSize(1_023, en)).isEqualTo("1,023 bytes")
        assertThat(LocaleFormats.fileSize(1_536, en)).isEqualTo("1.5\u00A0KiB")
        assertThat(LocaleFormats.fileSize(44_040_192, en)).isEqualTo("42.0\u00A0MiB")
        // Rounds up into the next unit instead of showing "1024.0 KiB".
        assertThat(LocaleFormats.fileSize(1_048_575, en)).isEqualTo("1.0\u00A0MiB")
        assertThat(LocaleFormats.fileSize(3L * 1024 * 1024 * 1024 + 300L * 1024 * 1024, en)).isEqualTo("3.3\u00A0GiB")

        assertThat(LocaleFormats.fileSize(1_536, bytes(spanish))).isEqualTo("1,5\u00A0KiB")
        assertThat(LocaleFormats.fileSize(44_040_192, bytes(basque))).isEqualTo("42,0\u00A0MiB")
        assertThat(LocaleFormats.fileSize(1_023, bytes(spanish))).isEqualTo("1.023 bytes")
    }

    @Test
    fun `dates and times follow the locale`() {
        val ms = LocalDateTime.of(2026, 10, 3, 14, 30).toInstant(ZoneOffset.UTC).toEpochMilli()

        assertThat(LocaleFormats.dateTime(ms, ZoneOffset.UTC, english)).isEqualTo("Oct 3, 2026, 2:30 PM")
        assertThat(LocaleFormats.time(ms, ZoneOffset.UTC, english)).isEqualTo("2:30 PM")
        assertThat(LocaleFormats.dateTime(ms, ZoneOffset.UTC, spanish)).isEqualTo("3 oct 2026, 14:30")
        assertThat(LocaleFormats.time(ms, ZoneOffset.UTC, spanish)).isEqualTo("14:30")
        val basqueDate = LocaleFormats.dateTime(ms, ZoneOffset.UTC, basque)
        assertThat(basqueDate).contains("2026")
        assertThat(basqueDate).contains("14:30")
        assertThat(basqueDate).doesNotContain("Oct")
    }

    @Test
    fun `durations read as minutes and seconds, with hours past an hour`() {
        assertThat(LocaleFormats.duration(0)).isEqualTo("0:00")
        assertThat(LocaleFormats.duration(205_400)).isEqualTo("3:25")
        assertThat(LocaleFormats.duration(3_723_000)).isEqualTo("1:02:03")
    }
}
