package io.sonora.core.playback

import android.os.Looper
import androidx.media3.common.PlaybackParameters
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.core.domain.playback.TempoRange
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The session player following the stored speed and pitch, as the playback service wires it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackTempoFollowerTest {

    @Test
    fun `a tempo becomes the player's speed and pitch factor`() {
        val parameters = PlaybackTempo.ofPercent(125, 2).toPlaybackParameters()

        assertThat(parameters.speed).isEqualTo(1.25f)
        assertThat(parameters.pitch).isEqualTo(PlaybackTempo.pitchFactorOf(2))
        assertThat(PlaybackTempo.DEFAULT.toPlaybackParameters()).isEqualTo(PlaybackParameters.DEFAULT)
    }

    @Test
    fun `the player gets the stored tempo at once and every change after`() = runTest(UnconfinedTestDispatcher()) {
        val player = SessionPlayer.build(RuntimeEnvironment.getApplication())
        val stored = MutableStateFlow(PlaybackTempo.ofPercent(150, -3))

        val following = followTempo(player, stored, backgroundScope)
        assertThat(player.playbackParameters).isEqualTo(PlaybackParameters(1.5f, PlaybackTempo.pitchFactorOf(-3)))

        stored.value = stored.value.withSpeed(0.8f)
        advanceTimeBy(TEMPO_THROTTLE_MS + 1)
        assertThat(player.playbackParameters).isEqualTo(PlaybackParameters(0.8f, PlaybackTempo.pitchFactorOf(-3)))

        stored.value = PlaybackTempo.DEFAULT
        advanceTimeBy(TEMPO_THROTTLE_MS + 1)
        assertThat(player.playbackParameters).isEqualTo(PlaybackParameters.DEFAULT)

        following.cancel()
        player.release()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `with Master Tempo off the pitch follows the speed like a turntable`() {
        val parameters = PlaybackTempo(10_612, masterTempo = false).toPlaybackParameters()

        assertThat(parameters.speed).isEqualTo(1.0612f)
        assertThat(parameters.pitch).isEqualTo(1.0612f)
    }

    @Test
    fun `with Master Tempo off the pitch equals the speed at every basis point`() {
        for (bp in PlaybackTempo.MIN_SPEED_BASIS_POINTS..PlaybackTempo.MAX_SPEED_BASIS_POINTS) {
            val parameters = PlaybackTempo(bp, masterTempo = false, range = TempoRange.WIDE).toPlaybackParameters()
            assertThat(parameters.pitch).isEqualTo(parameters.speed)
        }
    }

    @Test
    fun `with Master Tempo on the speed alone changes and the pitch is the semitone shift`() {
        val parameters = PlaybackTempo(10_612, semitones = 3, masterTempo = true).toPlaybackParameters()

        assertThat(parameters.speed).isEqualTo(1.0612f)
        assertThat(parameters.pitch).isEqualTo(PlaybackTempo.pitchFactorOf(3))
    }

    @Test
    fun `a fast drag is throttled to one change per period and ends on the last value`() = runTest {
        val source = MutableStateFlow(0)
        val seen = mutableListOf<Pair<Long, Int>>()
        backgroundScope.launch { source.throttleLatest(TEMPO_THROTTLE_MS).collect { seen += currentTime to it } }
        runCurrent()

        // 120 Hz for a second: a value every 8 ms.
        for (i in 1..125) {
            source.value = i
            advanceTimeBy(8)
        }
        advanceTimeBy(TEMPO_THROTTLE_MS + 1)

        assertThat(seen.first()).isEqualTo(0L to 0)
        assertThat(seen.last().second).isEqualTo(125)
        assertThat(seen.size).isAtMost(1_000 / TEMPO_THROTTLE_MS.toInt() + 3)
        assertThat(seen.size).isAtLeast(20)
        seen.zipWithNext().forEach { (a, b) -> assertThat(b.first - a.first).isAtLeast(TEMPO_THROTTLE_MS) }
        assertThat(seen.map { it.second }).isInOrder()
    }

    @Test
    fun `a lone change is applied at once and an idle flow applies nothing more`() = runTest {
        val source = MutableStateFlow(0)
        val seen = mutableListOf<Pair<Long, Int>>()
        backgroundScope.launch { source.throttleLatest(TEMPO_THROTTLE_MS).collect { seen += currentTime to it } }
        runCurrent()
        advanceTimeBy(500)

        source.value = 7
        runCurrent()
        advanceTimeBy(500)

        assertThat(seen).containsExactly(0L to 0, 500L to 7).inOrder()
    }

    @Test
    fun `the player ends on the last tempo of a fast drag`() = runTest(UnconfinedTestDispatcher()) {
        val player = SessionPlayer.build(RuntimeEnvironment.getApplication())
        val stored = MutableStateFlow(PlaybackTempo.DEFAULT)
        val following = followTempo(player, stored, backgroundScope)

        // 120 Hz: a new tempo every 8 ms.
        for (bp in 10_001..10_200 step 5) {
            stored.value = PlaybackTempo(bp)
            advanceTimeBy(8)
        }
        advanceTimeBy(TEMPO_THROTTLE_MS + 1)

        assertThat(player.playbackParameters).isEqualTo(PlaybackTempo(10_196).toPlaybackParameters())

        following.cancel()
        player.release()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
