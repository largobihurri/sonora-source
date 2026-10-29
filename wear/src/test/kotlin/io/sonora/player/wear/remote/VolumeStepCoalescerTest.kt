package io.sonora.player.wear.remote

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VolumeStepCoalescerTest {
    private val coalescer = VolumeStepCoalescer(intervalMs = 100, maxPerMessage = 4, maxPending = 10)

    @Test
    fun `the first turn goes out at once`() {
        coalescer.add(1)

        assertThat(coalescer.take(now = 0)).isEqualTo(1)
        assertThat(coalescer.hasPending).isFalse()
    }

    @Test
    fun `turns inside the interval wait and are sent together`() {
        coalescer.add(1)
        coalescer.take(now = 0)

        coalescer.add(1)
        coalescer.add(1)
        assertThat(coalescer.take(now = 50)).isEqualTo(0)
        assertThat(coalescer.hasPending).isTrue()
        assertThat(coalescer.take(now = 100)).isEqualTo(2)
    }

    @Test
    fun `turning back cancels the steps still waiting`() {
        coalescer.add(1)
        coalescer.take(now = 0)

        coalescer.add(3)
        coalescer.add(-3)

        assertThat(coalescer.take(now = 100)).isEqualTo(0)
        assertThat(coalescer.hasPending).isFalse()
    }

    @Test
    fun `one message never carries more than the cap, the rest follows`() {
        coalescer.add(9)

        assertThat(coalescer.take(now = 0)).isEqualTo(4)
        assertThat(coalescer.take(now = 50)).isEqualTo(0)
        assertThat(coalescer.take(now = 100)).isEqualTo(4)
        assertThat(coalescer.take(now = 200)).isEqualTo(1)
        assertThat(coalescer.hasPending).isFalse()
    }

    @Test
    fun `a long spin cannot queue up a long ramp after the crown stops`() {
        repeat(100) { coalescer.add(1) }
        var sent = 0
        var time = 0L
        while (coalescer.hasPending) {
            sent += coalescer.take(time)
            time += 100
        }

        assertThat(sent).isEqualTo(10)
    }

    @Test
    fun `lowering is held to the same limits`() {
        coalescer.add(-9)

        assertThat(coalescer.take(now = 0)).isEqualTo(-4)
    }
}
