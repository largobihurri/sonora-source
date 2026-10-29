package io.sonora.core.domain.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FadeCurveTest {

    @Test
    fun `the outgoing song starts at full level and ends silent, the incoming one the other way round`() {
        assertThat(FadeCurve.fadeOut(0f)).isEqualTo(1f)
        assertThat(FadeCurve.fadeOut(1f)).isWithin(1e-6f).of(0f)
        assertThat(FadeCurve.fadeIn(0f)).isEqualTo(0f)
        assertThat(FadeCurve.fadeIn(1f)).isWithin(1e-6f).of(1f)
    }

    @Test
    fun `the summed power stays constant all through the fade`() {
        for (step in 0..100) {
            val t = step / 100f
            val out = FadeCurve.fadeOut(t)
            val inc = FadeCurve.fadeIn(t)
            assertThat(out * out + inc * inc).isWithin(1e-5f).of(1f)
        }
    }

    @Test
    fun `halfway each song plays at minus three decibels`() {
        assertThat(FadeCurve.fadeOut(0.5f)).isWithin(1e-5f).of(0.70710677f)
        assertThat(FadeCurve.fadeIn(0.5f)).isWithin(1e-5f).of(0.70710677f)
    }

    @Test
    fun `the gains move one way only`() {
        val steps = (0..50).map { it / 50f }
        steps.zipWithNext().forEach { (a, b) ->
            assertThat(FadeCurve.fadeOut(b)).isAtMost(FadeCurve.fadeOut(a))
            assertThat(FadeCurve.fadeIn(b)).isAtLeast(FadeCurve.fadeIn(a))
        }
    }

    @Test
    fun `progress outside the fade is held at its ends`() {
        assertThat(FadeCurve.fadeOut(-0.5f)).isEqualTo(1f)
        assertThat(FadeCurve.fadeIn(-0.5f)).isEqualTo(0f)
        assertThat(FadeCurve.fadeOut(2f)).isWithin(1e-6f).of(0f)
        assertThat(FadeCurve.fadeIn(2f)).isWithin(1e-6f).of(1f)
    }

    @Test
    fun `progress is the elapsed share of the fade, held between zero and one`() {
        assertThat(FadeCurve.progress(elapsedMs = 1_500, durationMs = 6_000)).isEqualTo(0.25f)
        assertThat(FadeCurve.progress(elapsedMs = -10, durationMs = 6_000)).isEqualTo(0f)
        assertThat(FadeCurve.progress(elapsedMs = 9_000, durationMs = 6_000)).isEqualTo(1f)
        assertThat(FadeCurve.progress(elapsedMs = 0, durationMs = 0)).isEqualTo(1f)
    }
}
