package io.sonora.feature.dj.waveform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TileLruTest {

    /** Puts with nothing protected (an empty protected range). */
    private fun TileLru<String>.putFree(key: Int, value: String?): Int = put(key, value, protectFirst = 0, protectLast = -1)

    @Test
    fun `a full cache evicts the least recently used tile`() {
        val lru = TileLru<String>(capacity = 3)
        assertThat(lru.putFree(1, "a")).isEqualTo(TileLru.NO_KEY)
        assertThat(lru.putFree(2, "b")).isEqualTo(TileLru.NO_KEY)
        assertThat(lru.putFree(3, "c")).isEqualTo(TileLru.NO_KEY)
        assertThat(lru.putFree(4, "d")).isEqualTo(1)
        assertThat(lru.get(1)).isNull()
        assertThat(lru.get(4)).isEqualTo("d")
        assertThat(lru.size).isEqualTo(3)
    }

    @Test
    fun `reading a tile makes it the most recently used`() {
        val lru = TileLru<String>(capacity = 3)
        lru.putFree(1, "a")
        lru.putFree(2, "b")
        lru.putFree(3, "c")
        lru.get(1)
        assertThat(lru.putFree(4, "d")).isEqualTo(2)
        lru.get(3)
        assertThat(lru.putFree(5, "e")).isEqualTo(1)
    }

    @Test
    fun `eviction follows the order of last use`() {
        val lru = TileLru<String>(capacity = 4)
        for (key in 1..4) lru.putFree(key, "t$key")
        for (key in listOf(3, 1, 4, 2)) lru.get(key)
        val evicted = (10..13).map { lru.putFree(it, "n$it") }
        assertThat(evicted).containsExactly(3, 1, 4, 2).inOrder()
    }

    @Test
    fun `protected tiles survive while an unprotected one can go`() {
        val lru = TileLru<String>(capacity = 3)
        lru.putFree(1, "a")
        lru.putFree(2, "b")
        lru.putFree(9, "z")
        // 1 and 2 are the oldest but wanted (1..5): 9 goes.
        assertThat(lru.put(3, "c", protectFirst = 1, protectLast = 5)).isEqualTo(9)
        assertThat(lru.get(1)).isEqualTo("a")
        assertThat(lru.get(2)).isEqualTo("b")
    }

    @Test
    fun `when every tile is protected the least recently used one goes`() {
        val lru = TileLru<String>(capacity = 2)
        lru.putFree(1, "a")
        lru.putFree(2, "b")
        assertThat(lru.put(3, "c", protectFirst = 1, protectLast = 3)).isEqualTo(1)
    }

    @Test
    fun `replacing a tile keeps the others`() {
        val lru = TileLru<String>(capacity = 2)
        lru.putFree(1, null)
        lru.putFree(2, "b")
        assertThat(lru.contains(1)).isTrue()
        assertThat(lru.get(1)).isNull()
        assertThat(lru.putFree(1, "a")).isEqualTo(TileLru.NO_KEY)
        assertThat(lru.get(1)).isEqualTo("a")
        assertThat(lru.get(2)).isEqualTo("b")
        assertThat(lru.size).isEqualTo(2)
    }

    @Test
    fun `a pending tile counts as present but reads as null`() {
        val lru = TileLru<String>(capacity = 2)
        lru.putFree(7, null)
        assertThat(lru.contains(7)).isTrue()
        assertThat(lru.get(7)).isNull()
        assertThat(lru.contains(8)).isFalse()
    }

    @Test
    fun `remove and clear empty the slots`() {
        val lru = TileLru<String>(capacity = 3)
        lru.putFree(1, "a")
        lru.putFree(2, "b")
        lru.remove(1)
        assertThat(lru.contains(1)).isFalse()
        assertThat(lru.size).isEqualTo(1)
        lru.clear()
        assertThat(lru.size).isEqualTo(0)
        assertThat(lru.contains(2)).isFalse()
        assertThat(lru.putFree(3, "c")).isEqualTo(TileLru.NO_KEY)
    }

    @Test
    fun `negative and large tile indices are keys like any other`() {
        val lru = TileLru<String>(capacity = 2)
        lru.putFree(-4, "neg")
        lru.putFree(3_999, "far")
        assertThat(lru.get(-4)).isEqualTo("neg")
        assertThat(lru.get(3_999)).isEqualTo("far")
    }

    @Test
    fun `forEach visits every ready tile`() {
        val lru = TileLru<String>(capacity = 3)
        lru.putFree(1, "a")
        lru.putFree(2, null)
        lru.putFree(3, "c")
        val seen = mutableListOf<Pair<Int, String>>()
        lru.forEachReady { key, value -> seen += key to value }
        assertThat(seen).containsExactly(1 to "a", 3 to "c")
    }
}
