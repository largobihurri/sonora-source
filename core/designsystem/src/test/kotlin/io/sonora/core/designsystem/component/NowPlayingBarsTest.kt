package io.sonora.core.designsystem.component

import com.google.common.collect.Range
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NowPlayingBarsTest {

    @Test
    fun `paused bars rest at one fixed pose, whatever the phase`() {
        val resting = NowPlayingBarsMotion.heights(phase = 0f, animating = false)

        assertThat(resting).hasSize(NowPlayingBarsMotion.BAR_COUNT)
        assertThat(NowPlayingBarsMotion.heights(phase = 0.37f, animating = false)).isEqualTo(resting)
        assertThat(resting).isEqualTo(NowPlayingBarsMotion.RESTING)
    }

    @Test
    fun `playing bars bounce within their range, each at its own phase`() {
        val phases = (0 until 100).map { it / 100f }
        val frames = phases.map { NowPlayingBarsMotion.heights(it, animating = true) }

        frames.flatten().forEach { assertThat(it).isIn(Range.closed(NowPlayingBarsMotion.MIN_HEIGHT, 1f)) }
        // They move: every bar takes more than one height over a cycle...
        for (bar in 0 until NowPlayingBarsMotion.BAR_COUNT) {
            assertThat(frames.map { it[bar] }.distinct().size).isGreaterThan(1)
        }
        // ...and not in step: at a given moment the bars differ.
        assertThat(frames.count { it.distinct().size > 1 }).isGreaterThan(90)
    }

    @Test
    fun `the cycle loops seamlessly`() {
        val start = NowPlayingBarsMotion.heights(0f, animating = true)
        val end = NowPlayingBarsMotion.heights(1f, animating = true)

        start.zip(end).forEach { (a, b) -> assertThat(b).isWithin(1e-4f).of(a) }
    }

    // Live levels.

    @Test
    fun `a level maps onto the bar's height, silence at the minimum and 0 dBFS at the top`() {
        val levels = NowPlayingBarsLevels

        assertThat(levels.height(-96f)).isEqualTo(NowPlayingBarsMotion.MIN_HEIGHT)
        assertThat(levels.height(levels.FLOOR_DB)).isEqualTo(NowPlayingBarsMotion.MIN_HEIGHT)
        assertThat(levels.height(0f)).isEqualTo(1f)
        assertThat(levels.height(6f)).isEqualTo(1f)
        assertThat(levels.height(levels.FLOOR_DB / 2)).isWithin(1e-6f).of((NowPlayingBarsMotion.MIN_HEIGHT + 1f) / 2)
        assertThat(levels.height(-20f)).isGreaterThan(levels.height(-30f))
    }

    @Test
    fun `a bar warms to orange and then red as its level crosses the thresholds`() {
        val levels = NowPlayingBarsLevels

        assertThat(levels.heat(-30f)).isEqualTo(0f)
        assertThat(levels.heat(levels.ORANGE_DB - 0.1f)).isEqualTo(0f)
        assertThat(levels.heat(levels.ORANGE_DB)).isEqualTo(1f)
        assertThat(levels.heat(levels.RED_DB - 0.1f)).isEqualTo(1f)
        assertThat(levels.heat(levels.RED_DB)).isEqualTo(2f)
        assertThat(levels.heat(0f)).isEqualTo(2f)
        assertThat(levels.ORANGE_DB).isLessThan(levels.RED_DB)
    }

    @Test
    fun `the colour follows the heat, accent then orange then red`() {
        val levels = NowPlayingBarsLevels
        val accent = Color(0xFF0A84FF)

        assertThat(levels.color(accent, 0f)).isEqualTo(accent)
        assertThat(levels.color(accent, 1f)).isEqualTo(levels.ORANGE)
        assertThat(levels.color(accent, 2f)).isEqualTo(levels.RED)
        val between = levels.color(accent, 1.5f)
        assertThat(between).isNotEqualTo(levels.ORANGE)
        assertThat(between).isNotEqualTo(levels.RED)
    }

    @Test
    fun `the colour moves towards its heat smoothly, settling in a fraction of a second`() {
        val levels = NowPlayingBarsLevels

        val oneFrame = levels.approach(current = 0f, target = 2f, elapsedSeconds = 1 / 60f)
        assertThat(oneFrame).isGreaterThan(0f)
        assertThat(oneFrame).isLessThan(1f)
        assertThat(levels.approach(0f, 2f, elapsedSeconds = 0.5f)).isWithin(0.01f).of(2f)
        assertThat(levels.approach(2f, 0f, elapsedSeconds = 0.5f)).isWithin(0.01f).of(0f)
        assertThat(levels.approach(1f, 1f, elapsedSeconds = 0.016f)).isEqualTo(1f)
    }

    @Test
    fun `metered bars wait flat until the music's levels arrive, and never fall back to the bounce`() {
        for (bar in 0 until NowPlayingBarsMotion.BAR_COUNT) {
            // Playing with a meter but no levels yet (the song is still loading): flat, not bouncing.
            assertThat(NowPlayingBarsLevels.barHeight(bar, live = null, metered = true, phase = 0.37f, animating = true))
                .isEqualTo(NowPlayingBarsMotion.MIN_HEIGHT)
            // Live levels win.
            assertThat(NowPlayingBarsLevels.barHeight(bar, live = 0.8f, metered = true, phase = 0.37f, animating = true))
                .isEqualTo(0.8f)
            // Without a meter the glyph keeps its own motion, playing or paused.
            assertThat(NowPlayingBarsLevels.barHeight(bar, live = null, metered = false, phase = 0.37f, animating = true))
                .isEqualTo(NowPlayingBarsMotion.height(bar, 0.37f, true))
            assertThat(NowPlayingBarsLevels.barHeight(bar, live = null, metered = true, phase = 0f, animating = false))
                .isEqualTo(NowPlayingBarsMotion.height(bar, 0f, false))
        }
    }
}
