package io.sonora.player.wear.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PagingTest {

    private val items = listOf(0, 1, 2, 3, 4)

    @Test
    fun `pages follow each other and the last one is short`() {
        assertThat(items.pageOf(0, 2)).containsExactly(0, 1).inOrder()
        assertThat(items.pageOf(1, 2)).containsExactly(2, 3).inOrder()
        assertThat(items.pageOf(2, 2)).containsExactly(4)
    }

    @Test
    fun `a page past the end is empty`() {
        assertThat(items.pageOf(3, 2)).isEmpty()
        assertThat(items.pageOf(0, 5).size).isEqualTo(5)
        assertThat(items.pageOf(1, 5)).isEmpty()
    }

    @Test
    fun `an unlimited page size gives the whole list and never overflows`() {
        assertThat(items.pageOf(0, Int.MAX_VALUE)).isEqualTo(items)
        assertThat(items.pageOf(1, Int.MAX_VALUE)).isEmpty()
        assertThat(items.pageOf(2, Int.MAX_VALUE)).isEmpty()
        assertThat(items.pageOf(Int.MAX_VALUE, Int.MAX_VALUE)).isEmpty()
    }

    @Test
    fun `an empty list has only empty pages`() {
        assertThat(emptyList<Int>().pageOf(0, 10)).isEmpty()
    }

    @Test
    fun `a negative page or a page size below one is empty`() {
        assertThat(items.pageOf(-1, 2)).isEmpty()
        assertThat(items.pageOf(0, 0)).isEmpty()
        assertThat(items.pageOf(0, -3)).isEmpty()
    }
}
