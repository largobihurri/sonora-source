package io.sonora.core.domain.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Every lyric text below is invented for the tests. */
class LyricsTest {

    private fun synced(text: String) = Lyrics.parse(text) as Lyrics.Synced

    @Test
    fun `timed lines become synced lyrics with their times in milliseconds`() {
        val lyrics = synced(
            """
            [00:01.50]First test line
            [00:04.25]Second test line
            [01:02.123]Third test line
            [02:03]Fourth test line
            """.trimIndent(),
        )

        assertThat(lyrics.lines).containsExactly(
            LyricLine(1_500, "First test line"),
            LyricLine(4_250, "Second test line"),
            LyricLine(62_123, "Third test line"),
            LyricLine(123_000, "Fourth test line"),
        ).inOrder()
    }

    @Test
    fun `a line with several stamps repeats at each of them and the output is sorted`() {
        val lyrics = synced(
            """
            [00:10.00][00:30.00]Chorus test line
            [00:20.00]Verse test line
            """.trimIndent(),
        )

        assertThat(lyrics.lines).containsExactly(
            LyricLine(10_000, "Chorus test line"),
            LyricLine(20_000, "Verse test line"),
            LyricLine(30_000, "Chorus test line"),
        ).inOrder()
    }

    @Test
    fun `lines sharing a time keep their file order`() {
        val lyrics = synced("[00:05.00]Upper test line\n[00:05.00]Lower test line")

        assertThat(lyrics.lines.map { it.text }).containsExactly("Upper test line", "Lower test line").inOrder()
    }

    @Test
    fun `metadata tags are ignored and a positive offset shows lines earlier`() {
        val lyrics = synced(
            """
            [ar:Test Artist]
            [ti:Test Title]
            [al:Test Album]
            [by:Someone]
            [length:03:00]
            [offset:+500]
            [00:01.00]First test line
            [00:00.20]Early test line
            """.trimIndent(),
        )

        assertThat(lyrics.lines).containsExactly(
            LyricLine(0, "Early test line"), // never before the start
            LyricLine(500, "First test line"),
        ).inOrder()
    }

    @Test
    fun `a negative offset shows lines later`() {
        val lyrics = synced("[offset:-250]\n[00:01.00]First test line")

        assertThat(lyrics.lines.single().timeMs).isEqualTo(1_250)
    }

    @Test
    fun `enhanced word stamps are stripped to the line`() {
        val lyrics = synced("[00:01.00]<00:01.00>First <00:01.40>test <00:01.80>line")

        assertThat(lyrics.lines.single()).isEqualTo(LyricLine(1_000, "First test line"))
    }

    @Test
    fun `blank timed lines stay as pauses and untimed or invalid lines are skipped`() {
        val lyrics = synced(
            """

            [00:01.00]First test line
            a line without a stamp
            [00:03.00]
            [aa:bb.cc]Broken stamp line
            [00:05.00]   Last test line
            """.trimIndent(),
        )

        assertThat(lyrics.lines).containsExactly(
            LyricLine(1_000, "First test line"),
            LyricLine(3_000, ""),
            LyricLine(5_000, "Last test line"),
        ).inOrder()
    }

    @Test
    fun `windows line endings and a byte order mark are accepted`() {
        val lyrics = synced("﻿[00:01.00]First test line\r\n[00:02.00]Second test line\r\n")

        assertThat(lyrics.lines.map { it.text }).containsExactly("First test line", "Second test line").inOrder()
    }

    @Test
    fun `text without stamps is plain lyrics, trimmed`() {
        val lyrics = Lyrics.parse("\n  First test line\nSecond test line\n\nThird test line  \n")

        assertThat(lyrics).isEqualTo(Lyrics.Plain("First test line\nSecond test line\n\nThird test line"))
    }

    @Test
    fun `plain text with only metadata tags keeps no tags`() {
        val lyrics = Lyrics.parse("[ar:Test Artist]\nFirst test line\nSecond test line")

        assertThat(lyrics).isEqualTo(Lyrics.Plain("First test line\nSecond test line"))
    }

    @Test
    fun `blank or tag-only text has no lyrics`() {
        assertThat(Lyrics.parse("")).isNull()
        assertThat(Lyrics.parse("  \n \r\n")).isNull()
        assertThat(Lyrics.parse("[ar:Test Artist]\n[ti:Test Title]")).isNull()
    }

    @Test
    fun `the active line is the last one that started, none before the first`() {
        val lyrics = Lyrics.Synced(
            listOf(LyricLine(1_000, "a"), LyricLine(2_000, "b"), LyricLine(2_000, "c"), LyricLine(5_000, "d")),
        )

        assertThat(lyrics.activeLineIndex(0)).isEqualTo(-1)
        assertThat(lyrics.activeLineIndex(999)).isEqualTo(-1)
        assertThat(lyrics.activeLineIndex(1_000)).isEqualTo(0)
        assertThat(lyrics.activeLineIndex(1_999)).isEqualTo(0)
        assertThat(lyrics.activeLineIndex(2_000)).isEqualTo(2)
        assertThat(lyrics.activeLineIndex(4_999)).isEqualTo(2)
        assertThat(lyrics.activeLineIndex(60_000)).isEqualTo(3)
        assertThat(Lyrics.Synced(emptyList()).activeLineIndex(1_000)).isEqualTo(-1)
    }
}
