package io.sonora.core.domain.library.marks

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PinOrderingTest {

    private val sorted = listOf("d", "a", "c", "b")

    @Test
    fun `no pins keeps the sort`() {
        val result = PinOrdering.apply(sorted, emptyList()) { it }
        assertThat(result.items).containsExactly("d", "a", "c", "b").inOrder()
        assertThat(result.pinnedKeys).isEmpty()
    }

    @Test
    fun `pinned items come first in pin order, the rest keep the sort`() {
        val result = PinOrdering.apply(sorted, pins = listOf("b", "a")) { it }
        assertThat(result.items).containsExactly("b", "a", "d", "c").inOrder()
        assertThat(result.isPinned("b")).isTrue()
        assertThat(result.isPinned("d")).isFalse()
    }

    @Test
    fun `pins of items missing from the list are ignored`() {
        val result = PinOrdering.apply(sorted, pins = listOf("gone", "c")) { it }
        assertThat(result.items).containsExactly("c", "d", "a", "b").inOrder()
        assertThat(result.pinnedKeys).containsExactly("c")
    }

    @Test
    fun `items sharing a pinned key move up together, keeping their own order`() {
        data class Entry(val id: Int, val uri: String)
        val entries = listOf(Entry(1, "x"), Entry(2, "y"), Entry(3, "x"), Entry(4, "z"))
        val result = PinOrdering.apply(entries, pins = listOf("x")) { it.uri }
        assertThat(result.items.map { it.id }).containsExactly(1, 3, 2, 4).inOrder()
    }

    @Test
    fun `a reversed sort still lists pins on top`() {
        val result = PinOrdering.apply(sorted.reversed(), pins = listOf("a")) { it }
        assertThat(result.items).containsExactly("a", "b", "c", "d").inOrder()
    }
}
