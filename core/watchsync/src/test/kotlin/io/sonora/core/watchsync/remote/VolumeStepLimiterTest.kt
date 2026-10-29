package io.sonora.core.watchsync.remote

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VolumeStepLimiterTest {
    private var now = 0L
    private val limiter = VolumeStepLimiter(maxStepsPerMessage = 4, maxRaisePerWindow = 6, windowMs = 1_000, now = { now })

    @Test
    fun `a small request goes through unchanged`() {
        assertThat(limiter.allow(2)).isEqualTo(2)
        assertThat(limiter.allow(-3)).isEqualTo(-3)
    }

    @Test
    fun `zero stays zero`() {
        assertThat(limiter.allow(0)).isEqualTo(0)
    }

    @Test
    fun `one message never moves more than the per message cap`() {
        assertThat(limiter.allow(100)).isEqualTo(4)
        assertThat(limiter.allow(-100)).isEqualTo(-4)
    }

    @Test
    fun `raising is limited to a budget per window`() {
        assertThat(limiter.allow(4)).isEqualTo(4)
        assertThat(limiter.allow(4)).isEqualTo(2) // 6 raises in the window so far
        assertThat(limiter.allow(1)).isEqualTo(0)
    }

    @Test
    fun `the raise budget comes back as the window moves on`() {
        limiter.allow(4)
        limiter.allow(4)
        now = 999
        assertThat(limiter.allow(1)).isEqualTo(0)
        now = 1_001
        assertThat(limiter.allow(4)).isEqualTo(4)
    }

    @Test
    fun `lowering is never held back by the raise budget`() {
        limiter.allow(4)
        limiter.allow(4)

        assertThat(limiter.allow(-4)).isEqualTo(-4)
    }

    @Test
    fun `lowering does not give raise budget back`() {
        limiter.allow(4)
        limiter.allow(2)
        limiter.allow(-4)

        assertThat(limiter.allow(1)).isEqualTo(0)
    }
}
