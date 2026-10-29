package io.sonora.core.data.preferences

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchHistoryRulesTest {

    @Test
    fun `a new search goes first`() {
        assertThat(SearchHistoryRules.record(listOf("abba", "queen"), "muse")).containsExactly("muse", "abba", "queen").inOrder()
    }

    @Test
    fun `searching again moves the entry first, ignoring case, with the latest spelling`() {
        assertThat(SearchHistoryRules.record(listOf("abba", "Queen", "muse"), "queen"))
            .containsExactly("queen", "abba", "muse").inOrder()
    }

    @Test
    fun `a search is trimmed and its line breaks and runs of spaces become one space`() {
        assertThat(SearchHistoryRules.record(emptyList(), "  daft \n  punk ")).containsExactly("daft punk")
    }

    @Test
    fun `a blank search changes nothing`() {
        val history = listOf("abba")
        assertThat(SearchHistoryRules.record(history, " \n ")).isEqualTo(history)
    }

    @Test
    fun `only the 20 most recent searches are kept`() {
        val history = (1..20).map { "q$it" }

        val recorded = SearchHistoryRules.record(history, "new")

        assertThat(recorded).hasSize(SearchHistoryRules.LIMIT)
        assertThat(recorded.first()).isEqualTo("new")
        assertThat(recorded).doesNotContain("q20")
        assertThat(recorded.last()).isEqualTo("q19")
    }

    @Test
    fun `removing forgets the entry ignoring case and keeps the others in order`() {
        assertThat(SearchHistoryRules.remove(listOf("abba", "Queen", "muse"), "QUEEN")).containsExactly("abba", "muse").inOrder()
    }

    @Test
    fun `removing an unknown entry changes nothing`() {
        assertThat(SearchHistoryRules.remove(listOf("abba"), "muse")).containsExactly("abba")
    }

    @Test
    fun `stored history round-trips and an empty or missing value reads as no history`() {
        val history = listOf("daft punk", "AC/DC", "Sigur Rós")

        assertThat(SearchHistoryRules.decode(SearchHistoryRules.encode(history))).isEqualTo(history)
        assertThat(SearchHistoryRules.decode(null)).isEmpty()
        assertThat(SearchHistoryRules.decode("")).isEmpty()
    }

    @Test
    fun `a stored value longer than the limit or with duplicates is cleaned when read`() {
        val stored = SearchHistoryRules.encode(listOf("abba", "ABBA") + (1..25).map { "q$it" })

        val read = SearchHistoryRules.decode(stored)

        assertThat(read).hasSize(SearchHistoryRules.LIMIT)
        assertThat(read.take(2)).containsExactly("abba", "q1").inOrder()
    }
}
