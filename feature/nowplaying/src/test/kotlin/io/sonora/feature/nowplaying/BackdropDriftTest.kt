package io.sonora.feature.nowplaying

import androidx.compose.runtime.MonotonicFrameClock
import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // advanceTimeBy, currentTime
class BackdropDriftTest {

    private val period = BackdropDrift.PERIOD_NANOS
    private val quarter = (PI / 2).toFloat()

    @Test
    fun `the first frame keeps the phase`() {
        val drift = BackdropDrift()
        assertThat(drift.frame(123_456_789L)).isEqualTo(0f)
    }

    @Test
    fun `a quarter of the period turns the phase a quarter turn`() {
        val drift = BackdropDrift()
        drift.frame(1_000L)
        assertThat(drift.frame(1_000L + period / 4)).isWithin(1e-4f).of(quarter)
    }

    @Test
    fun `the phase wraps after a whole period`() {
        val drift = BackdropDrift()
        drift.frame(0L)
        assertThat(drift.frame(period + period / 4)).isWithin(1e-4f).of(quarter)
    }

    @Test
    fun `a pause holds the phase and the next frame goes on from it without a jump`() {
        val drift = BackdropDrift()
        drift.frame(0L)
        drift.frame(period / 4)
        drift.pause()
        assertThat(drift.phase).isWithin(1e-4f).of(quarter)
        // An hour later the first frame after the pause adds nothing.
        assertThat(drift.frame(period / 4 + 3_600_000_000_000L)).isWithin(1e-4f).of(quarter)
        assertThat(drift.frame(period / 4 + 3_600_000_000_000L + period / 4)).isWithin(1e-4f).of(2 * quarter)
    }

    @Test
    fun `a longer animation duration scale slows the drift and 0 stops it`() {
        val drift = BackdropDrift()
        drift.frame(0L, durationScale = 2f)
        assertThat(drift.frame(period / 2, durationScale = 2f)).isWithin(1e-4f).of(quarter)
        assertThat(drift.frame(period, durationScale = 0f)).isWithin(1e-4f).of(quarter)
    }

    /** A frame clock on the test's virtual time (a display frame whenever one is asked for) that counts the frames. */
    private class VirtualClock(private val scope: TestScope) : MonotonicFrameClock {
        var frames = 0

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            yield()
            frames++
            return onFrame(scope.currentTime * 1_000_000L)
        }
    }

    /** A tick every 250 ms, as the player's position changes while it plays. */
    private val ticks = flow {
        while (true) {
            delay(250L)
            emit(Unit)
        }
    }

    @Test
    fun `the drift takes one step per tick, not one on every frame of the display`() = runTest {
        val clock = VirtualClock(this)
        val phases = mutableListOf<Float>()
        val loop = launch(clock) { followBackdropDrift(BackdropDrift(), durationScale = 1f, ticks) { phases += it } }

        advanceTimeBy(1_000L)
        runCurrent()

        assertThat(clock.frames).isEqualTo(4)
        assertThat(phases).hasSize(4)
        loop.cancel()
    }

    @Test
    fun `without a tick (nothing plays) the drift asks for no frame at all`() = runTest {
        val clock = VirtualClock(this)
        val loop = launch(clock) { followBackdropDrift(BackdropDrift(), durationScale = 1f, emptyFlow<Unit>().onCompletion { awaitCancellation() }) {} }

        advanceTimeBy(10_000L)
        runCurrent()

        assertThat(clock.frames).isEqualTo(0)
        loop.cancel()
    }

    @Test
    fun `a step that waited longer still advances the phase by the time that passed`() = runTest {
        val clock = VirtualClock(this)
        val phases = mutableListOf<Float>()
        val loop = launch(clock) { followBackdropDrift(BackdropDrift(), durationScale = 1f, ticks) { phases += it } }

        advanceTimeBy(6_000L) // a quarter of the 24 s period
        runCurrent()

        // The first step only sets the time base, so what has been drifted is what passed since it.
        val elapsedNanos = (6_000L - 250L) * 1_000_000L
        assertThat(phases.last()).isWithin(0.02f).of((2 * PI * elapsedNanos / period).toFloat())
        loop.cancel()
    }
}
