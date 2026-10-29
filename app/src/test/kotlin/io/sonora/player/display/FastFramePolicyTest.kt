package io.sonora.player.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastFramePolicyTest {

    @Test
    fun `a still screen leaves the rate to the system`() {
        assertFalse(FastFramePolicy().fast(now = 1_000))
    }

    @Test
    fun `a finger on the screen asks for the fastest rate until it lifts and a tail after`() {
        val policy = FastFramePolicy()
        policy.touch(down = true, now = 1_000)
        assertTrue(policy.fast(60_000))
        assertNull(policy.endsAt())

        policy.touch(down = false, now = 60_000)
        assertTrue(policy.fast(60_000 + FastFramePolicy.TOUCH_TAIL_MS - 1))
        assertFalse(policy.fast(60_000 + FastFramePolicy.TOUCH_TAIL_MS))
        assertEquals(60_000 + FastFramePolicy.TOUCH_TAIL_MS, policy.endsAt())
    }

    @Test
    fun `a display turn asks for the fastest rate while Now Playing turns`() {
        val policy = FastFramePolicy()
        policy.turned(now = 5_000)
        assertTrue(policy.fast(5_000 + 420))
        assertFalse(policy.fast(5_000 + FastFramePolicy.TURN_MS))
    }

    @Test
    fun `a turn during a touch tail never shortens it`() {
        val policy = FastFramePolicy()
        policy.touch(down = false, now = 1_000)
        policy.turned(now = 1_100)
        assertEquals(1_000 + FastFramePolicy.TOUCH_TAIL_MS, policy.endsAt())
    }
}
