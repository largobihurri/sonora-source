package io.sonora.core.watchsync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WatchNodesTest {

    @Test
    fun `no nodes means no watch`() {
        assertThat(WatchNodes.pick(emptyList())).isNull()
    }

    @Test
    fun `a nearby node is preferred to a farther one`() {
        val far = WatchNode("1", "Far watch", isNearby = false)
        val near = WatchNode("2", "Near watch", isNearby = true)

        assertThat(WatchNodes.pick(listOf(far, near))).isEqualTo(near)
    }

    @Test
    fun `among equals the first by name wins, so the choice does not flip between calls`() {
        val b = WatchNode("1", "B watch", isNearby = true)
        val a = WatchNode("2", "A watch", isNearby = true)

        assertThat(WatchNodes.pick(listOf(b, a))).isEqualTo(a)
        assertThat(WatchNodes.pick(listOf(a, b))).isEqualTo(a)
    }
}
