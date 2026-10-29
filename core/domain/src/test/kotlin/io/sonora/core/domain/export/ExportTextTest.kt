package io.sonora.core.domain.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExportTextTest {

    @Test
    fun `text is cleaned and blank text is missing`() {
        assertThat(ExportText.clamp("  Daft\u0000  Punk\n")).isEqualTo("Daft Punk")
        assertThat(ExportText.clamp(" \t ")).isNull()
        assertThat(ExportText.clamp(null)).isNull()
    }

    @Test
    fun `long text is cut without splitting a surrogate pair`() {
        assertThat(ExportText.clamp("é".repeat(1000))).hasLength(ExportText.MAX_TEXT_CHARS)
        assertThat(ExportText.clamp("ab🎵", maxChars = 3)).isEqualTo("ab")
    }

    @Test
    fun `implausible years and track numbers are dropped`() {
        assertThat(ExportText.year(2025)).isEqualTo(2025)
        assertThat(ExportText.year(0)).isNull()
        assertThat(ExportText.year(70_000)).isNull()
        assertThat(ExportText.year(-3)).isNull()
        assertThat(ExportText.trackNumber(7)).isEqualTo(7)
        assertThat(ExportText.trackNumber(0)).isNull()
        assertThat(ExportText.trackNumber(Int.MAX_VALUE)).isNull()
        assertThat(ExportText.trackNumber(null)).isNull()
    }

    @Test
    fun `the year of a tag date is its first four digits`() {
        assertThat(ExportText.yearOf("2019-04-01")).isEqualTo(2019)
        assertThat(ExportText.yearOf(" 1997 ")).isEqualTo(1997)
        assertThat(ExportText.yearOf("April 2001")).isEqualTo(2001)
        assertThat(ExportText.yearOf("99")).isNull()
        assertThat(ExportText.yearOf(null)).isNull()
    }
}
