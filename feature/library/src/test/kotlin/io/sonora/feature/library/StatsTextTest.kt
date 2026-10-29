package io.sonora.feature.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StatsTextTest {

    @Test
    fun `listening time is whole hours and the minutes left, rounded down`() {
        assertThat(ListeningTime.of(0)).isEqualTo(ListeningTime(0, 0))
        assertThat(ListeningTime.of(59_999)).isEqualTo(ListeningTime(0, 0))
        assertThat(ListeningTime.of(3_599_999)).isEqualTo(ListeningTime(0, 59))
        assertThat(ListeningTime.of(3_600_000)).isEqualTo(ListeningTime(1, 0))
        assertThat(ListeningTime.of(100 * 3_600_000L + 7 * 60_000L + 59_000)).isEqualTo(ListeningTime(100, 7))
    }

    @Test
    fun `no ranked item can share a key with a heading, whatever it is called`() {
        val kinds = listOf("artists", "albums", "songs")
        val headings = listOf(StatsKeys.HEADER) + kinds.map(StatsKeys::title)
        val names = listOf("title", "header", "artists:title", "songs:title", "item", "")
        val items = kinds.flatMap { kind -> names.map { StatsKeys.item(kind, it) } }

        assertThat(items).containsNoneIn(headings)
        assertThat((headings + items).toSet()).hasSize(headings.size + items.size)
    }
}
