package io.sonora.core.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.stats.Listening
import io.sonora.core.domain.stats.ListeningMeter
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The service player's events, as [PlayCountRecorder] gets them, turned into recorded plays. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayCountRecorderTest {

    /** The few player properties the recorder reads; anything else is a test error. */
    private class FakePlayer {
        var current: MediaItem? = null
        var playing = false
        var durationMs = C.TIME_UNSET
        var parameters = PlaybackParameters.DEFAULT
        val listeners = mutableListOf<Player.Listener>()

        val player: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "getCurrentMediaItem" -> current
                    "isPlaying" -> playing
                    "getDuration" -> durationMs
                    "getPlaybackParameters" -> parameters
                    "addListener" -> listeners.add(args[0] as Player.Listener).let { null }
                    "removeListener" -> listeners.remove(args[0] as Player.Listener).let { null }
                    else -> throw UnsupportedOperationException(method.name)
                }
            },
        ) as Player
    }

    /** A song with no duration in the library (a file the scan could not time). */
    private fun song(id: Int) = MediaItem.Builder().setMediaId("entry-$id").setUri("content://doc/$id").build()

    private val fake = FakePlayer()
    private val recorded = mutableListOf<String>()
    private val listened = mutableListOf<Listening>()
    private var playbackIds = 0

    /** The recorder as the service builds it: following on one scope, writing on another that outlives it. */
    private fun TestScope.recorder(
        following: CoroutineScope,
        writes: CoroutineScope,
        record: suspend (String) -> Unit = { recorded += it },
    ) = PlayCountRecorder(
        fake.player,
        PlayCountTracker { testScheduler.currentTime },
        following,
        writes,
        meter = ListeningMeter({ testScheduler.currentTime }, { WALL_START + testScheduler.currentTime }, { "p${++playbackIds}" }),
        listened = { listened += it },
        record = record,
    )

    private fun TestScope.scope() = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())

    @Test
    fun `a gapless next song with no library duration counts at half its length`() = runTest {
        val following = scope()
        val recorder = recorder(following, scope())
        fake.current = song(1)
        fake.playing = true
        fake.durationMs = 60_000
        recorder.attach()
        recorder.onPlaybackStateChanged(Player.STATE_READY)
        advanceTimeBy(40_000)
        runCurrent()
        assertThat(recorded).containsExactly("content://doc/1")

        // The next song was prepared ahead: it starts with no READY, its length already known.
        fake.current = song(2)
        fake.durationMs = 100_000
        recorder.onMediaItemTransition(fake.current, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        advanceTimeBy(50_001)
        runCurrent()

        assertThat(recorded).containsExactly("content://doc/1", "content://doc/2").inOrder()
        following.cancel()
    }

    @Test
    fun `a song played at double speed counts once half of it was heard`() = runTest {
        val following = scope()
        val recorder = recorder(following, scope())
        fake.parameters = PlaybackParameters(2f)
        fake.current = song(1)
        fake.playing = true
        fake.durationMs = 120_000
        recorder.attach()
        recorder.onPlaybackStateChanged(Player.STATE_READY)

        advanceTimeBy(29_999)
        runCurrent()
        assertThat(recorded).isEmpty()
        advanceTimeBy(2)
        runCurrent()
        assertThat(recorded).containsExactly("content://doc/1")
        following.cancel()
    }

    @Test
    fun `a speed change mid-song moves the next check`() = runTest {
        val following = scope()
        val recorder = recorder(following, scope())
        fake.current = song(1)
        fake.playing = true
        fake.durationMs = 120_000
        recorder.attach()
        recorder.onPlaybackStateChanged(Player.STATE_READY)
        advanceTimeBy(20_000)

        fake.parameters = PlaybackParameters(2f)
        recorder.onPlaybackParametersChanged(fake.parameters)
        // 20 s of the 60 s threshold heard at 1x: the other 40 s take 20 s at 2x.
        advanceTimeBy(19_999)
        runCurrent()
        assertThat(recorded).isEmpty()
        advanceTimeBy(2)
        runCurrent()
        assertThat(recorded).containsExactly("content://doc/1")
        following.cancel()
    }

    @Test
    fun `a length learnt after the song started applies at once`() = runTest {
        val following = scope()
        val recorder = recorder(following, scope())
        fake.current = song(1)
        fake.playing = true
        recorder.attach()

        advanceTimeBy(10_000)
        fake.durationMs = 100_000
        recorder.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
        advanceTimeBy(40_001)
        runCurrent()

        assertThat(recorded).containsExactly("content://doc/1")
        following.cancel()
    }

    @Test
    fun `a play reached as the service stops is still recorded`() = runTest {
        val following = scope()
        val recorder = recorder(following, scope())
        fake.current = song(1)
        fake.playing = true
        fake.durationMs = 60_000
        recorder.attach()
        recorder.onPlaybackStateChanged(Player.STATE_READY)
        // Half the song is heard exactly now; the check that would count it has not run yet.
        advanceTimeBy(30_000)

        recorder.detach()
        following.cancel()
        runCurrent()

        assertThat(recorded).containsExactly("content://doc/1")
        assertThat(fake.listeners).isEmpty()
    }

    @Test
    fun `a play being written when the service stops is still written`() = runTest {
        val following = scope()
        val recorder = recorder(following, scope()) { delay(50); recorded += it }
        fake.current = song(1)
        fake.playing = true
        fake.durationMs = 60_000
        recorder.attach()
        recorder.onPlaybackStateChanged(Player.STATE_READY)
        advanceTimeBy(30_010)

        recorder.detach()
        following.cancel()
        advanceTimeBy(100)

        assertThat(recorded).containsExactly("content://doc/1")
    }

    @Test
    fun `a skipped song keeps the time heard, and the next one starts its own playback`() = runTest {
        val following = scope()
        val recorder = recorder(following, scope())
        fake.current = song(1)
        fake.playing = true
        fake.durationMs = 200_000
        recorder.attach()
        advanceTimeBy(30_000)

        fake.current = song(2)
        recorder.onMediaItemTransition(fake.current, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        advanceTimeBy(5_000)
        recorder.detach()
        following.cancel()
        runCurrent()

        assertThat(recorded).isEmpty()
        assertThat(listened).containsExactly(
            Listening("p1", "content://doc/1", WALL_START, 30_000, counted = false),
            Listening("p2", "content://doc/2", WALL_START + 30_000, 5_000, counted = false),
        ).inOrder()
    }

    @Test
    fun `a play shows as counted when it counts, and its playback ends with all the time heard`() = runTest {
        val following = scope()
        val recorder = recorder(following, scope())
        fake.current = song(1)
        fake.playing = true
        fake.durationMs = 60_000
        recorder.attach()
        recorder.onPlaybackStateChanged(Player.STATE_READY)
        advanceTimeBy(30_001)
        runCurrent()

        assertThat(listened).containsExactly(Listening("p1", "content://doc/1", WALL_START, 30_000, counted = true))

        advanceTimeBy(20_000)
        fake.current = song(2)
        recorder.onMediaItemTransition(fake.current, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        runCurrent()

        assertThat(listened.last()).isEqualTo(Listening("p1", "content://doc/1", WALL_START, 50_001, counted = true))
        following.cancel()
    }

    @Test
    fun `a pause saves the time heard so far, and stopping the service saves the rest`() = runTest {
        val following = scope()
        val recorder = recorder(following, scope())
        fake.current = song(1)
        fake.playing = true
        fake.durationMs = 600_000
        recorder.attach()
        advanceTimeBy(12_000)

        fake.playing = false
        recorder.onIsPlayingChanged(false)
        runCurrent()
        assertThat(listened).containsExactly(Listening("p1", "content://doc/1", WALL_START, 12_000, counted = false))

        fake.playing = true
        recorder.onIsPlayingChanged(true)
        advanceTimeBy(8_000)
        recorder.detach()
        following.cancel()
        runCurrent()

        assertThat(listened.last()).isEqualTo(Listening("p1", "content://doc/1", WALL_START, 20_000, counted = false))
    }

    private companion object {
        const val WALL_START = 1_790_000_000_000L
    }
}
