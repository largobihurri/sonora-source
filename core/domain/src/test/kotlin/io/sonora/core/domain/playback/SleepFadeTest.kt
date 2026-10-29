package io.sonora.core.domain.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The volume curve of the sleep timer's last ten seconds. */
class SleepFadeTest {

    @Test
    fun `full volume until the last ten seconds, silence at the end`() {
        assertThat(SleepFade.DURATION_MS).isEqualTo(10_000)
        assertThat(SleepFade.gain(null)).isEqualTo(1f)
        assertThat(SleepFade.gain(600_000)).isEqualTo(1f)
        assertThat(SleepFade.gain(10_000)).isEqualTo(1f)
        assertThat(SleepFade.gain(0)).isEqualTo(0f)
        assertThat(SleepFade.gain(-500)).isEqualTo(0f)
    }

    @Test
    fun `the fade falls steadily, half way at five seconds`() {
        val gains = (10_000L downTo 0L step 50L).map(SleepFade::gain)

        gains.zipWithNext().forEach { (before, after) -> assertThat(after).isAtMost(before) }
        assertThat(SleepFade.gain(5_000)).isWithin(1e-4f).of(0.5f)
    }

    @Test
    fun `the fade eases in and out, with no audible step at either end`() {
        // A 50 ms tick changes the gain by under 0.1% at both ends (a linear fade steps 0.5% on every tick); the
        // middle carries the change.
        assertThat(1f - SleepFade.gain(9_950)).isLessThan(0.001f)
        assertThat(SleepFade.gain(50)).isLessThan(0.001f)
        assertThat(SleepFade.gain(5_050) - SleepFade.gain(5_000)).isGreaterThan(0.007f)
    }
}
