package io.sonora.tools.calibration

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class MarkdownTableTest {

    @Test
    fun `renders header separator and rows with padded columns`() {
        val table = MarkdownTable.render(
            headers = listOf("Format", "n"),
            rows = listOf(listOf("mp3", "12"), listOf("flac", "3")),
            rightAligned = setOf(1),
        )

        assertThat(table).isEqualTo(
            """
            | Format |  n |
            | :----- | -: |
            | mp3    | 12 |
            | flac   |  3 |

            """.trimIndent(),
        )
    }

    @Test
    fun `pipes and line breaks in cells are escaped`() {
        val table = MarkdownTable.render(listOf("a"), listOf(listOf("x|y\nz")))
        assertThat(table).contains("| x\\|y z |")
    }

    @Test
    fun `a table without rows still has a header`() {
        val table = MarkdownTable.render(listOf("a", "b"), emptyList())
        assertThat(table.lines().filter { it.isNotEmpty() }).hasSize(2)
    }

    @Test
    fun `a row of the wrong width is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MarkdownTable.render(listOf("a", "b"), listOf(listOf("only one")))
        }
    }

    @Test
    fun `numbers format with fixed decimals and NaN as n a`() {
        assertThat(MarkdownTable.number(1.23456, 2)).isEqualTo("1.23")
        assertThat(MarkdownTable.number(-0.5, 1)).isEqualTo("-0.5")
        assertThat(MarkdownTable.number(3.0, 0)).isEqualTo("3")
        assertThat(MarkdownTable.number(Double.NaN, 2)).isEqualTo("n/a")
    }

    @Test
    fun `numbers use a dot whatever the default locale`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertThat(MarkdownTable.number(1.5, 1)).isEqualTo("1.5")
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `a summary renders as a row`() {
        val row = MarkdownTable.summaryRow("mp3", Stats.summarize(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)), decimals = 1)
        assertThat(row).containsExactly("mp3", "5", "3.0", "3.0", "4.8", "1.0", "5.0").inOrder()
        assertThat(MarkdownTable.SUMMARY_HEADERS).containsExactly("", "n", "mean", "median", "p95", "min", "max").inOrder()
    }
}
