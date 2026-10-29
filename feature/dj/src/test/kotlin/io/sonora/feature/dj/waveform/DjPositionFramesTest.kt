package io.sonora.feature.dj.waveform

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.ClockSample
import io.sonora.core.domain.playback.PlaybackClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The frame driver behind [rememberDjPosition]: frames only while playing, none at all while paused. */
@OptIn(ExperimentalCoroutinesApi::class)
class DjPositionFramesTest {

    private class FakeClock(var position: Long) : PlaybackClock {
        override fun read(into: ClockSample): Boolean {
            into.queueItemId = ITEM
            into.positionMs = position
            into.speed = 1f
            into.playing = true
            return true
        }
    }

    /** Hands out [frames] frames, then never another (a frame request then waits for good). */
    private class CountingFrameClock(private val frames: Int) : MonotonicFrameClock {
        var requests = 0

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            requests++
            if (requests > frames) awaitCancellation()
            return onFrame(requests * FRAME_NANOS)
        }
    }

    @Test
    fun `a paused screen asks for no frame and publishes the exact position once`() = runTest {
        val frames = CountingFrameClock(frames = 100)
        val slow = mutableLongStateOf(1_000L)
        val published = mutableListOf<Double>()
        val job = launch(frames) {
            followDjPosition(DjPositionSource(FakeClock(1_234L)), ITEM, playing = false, { slow.longValue }) { published += it }
        }
        advanceUntilIdle()
        assertThat(frames.requests).isEqualTo(0)
        assertThat(published).containsExactly(1_234.0)
        job.cancel()
    }

    @Test
    fun `a seek while paused publishes once more and still asks for no frame`() = runTest {
        val frames = CountingFrameClock(frames = 100)
        val clock = FakeClock(1_000L)
        val slow = mutableLongStateOf(1_000L)
        val published = mutableListOf<Double>()
        val job = launch(frames) {
            followDjPosition(DjPositionSource(clock), ITEM, playing = false, { slow.longValue }) { published += it }
        }
        advanceUntilIdle()
        clock.position = 60_000L
        slow.longValue = 60_000L
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
        assertThat(frames.requests).isEqualTo(0)
        assertThat(published).containsExactly(1_000.0, 60_000.0).inOrder()
        job.cancel()
    }

    @Test
    fun `a paused screen with an unchanged slow position stays quiet`() = runTest {
        val frames = CountingFrameClock(frames = 100)
        val slow = mutableLongStateOf(1_000L)
        val published = mutableListOf<Double>()
        val job = launch(frames) {
            followDjPosition(DjPositionSource(FakeClock(1_000L)), ITEM, playing = false, { slow.longValue }) { published += it }
        }
        advanceUntilIdle()
        slow.longValue = 1_000L
        Snapshot.sendApplyNotifications()
        advanceUntilIdle()
        assertThat(published).hasSize(1)
        assertThat(frames.requests).isEqualTo(0)
        job.cancel()
    }

    @Test
    fun `a playing screen publishes one position per frame`() = runTest {
        val frames = CountingFrameClock(frames = 5)
        val published = mutableListOf<Double>()
        val job = launch(frames) {
            followDjPosition(DjPositionSource(FakeClock(2_000L)), ITEM, playing = true, { 0L }) { published += it }
        }
        advanceUntilIdle()
        assertThat(published).hasSize(5)
        // The sixth request is the one left waiting.
        assertThat(frames.requests).isEqualTo(6)
        job.cancel()
    }

    @Test
    fun `a playing screen reports its clock stats once per second of frames`() = runTest {
        val frames = CountingFrameClock(frames = 130)
        val reports = mutableListOf<Int>()
        val source = DjPositionSource(FakeClock(2_000L), stats = DjClockStats())
        val job = launch(frames) {
            followDjPosition(source, ITEM, playing = true, { 0L }, report = { reports += it.frames }) { }
        }
        advanceUntilIdle()
        // 130 frames of 16.7 ms: two whole seconds, each reported once, with the frames it counted.
        assertThat(reports).containsExactly(61, 60).inOrder()
        job.cancel()
    }

    @Test
    fun `a paused screen never reports`() = runTest {
        val frames = CountingFrameClock(frames = 100)
        var reports = 0
        val source = DjPositionSource(FakeClock(2_000L), stats = DjClockStats())
        val job = launch(frames) {
            followDjPosition(source, ITEM, playing = false, { 0L }, report = { reports++ }) { }
        }
        advanceUntilIdle()
        assertThat(reports).isEqualTo(0)
        job.cancel()
    }

    private companion object {
        const val ITEM = 7L
        const val FRAME_NANOS = 16_666_667L
    }
}
