package io.sonora.core.playback

import androidx.media3.common.FlagSet
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.AbLoop
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [AbLoopWatcher] on a fake player whose position follows the test clock while playing. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AbLoopWatcherTest {

    /** A player playing at normal speed on the test clock; seeks are recorded and reported like Media3 does. */
    private class FakePlayer(private val now: () -> Long) {
        var current: MediaItem? = entry(7)
        var playing = false
        var durationMs = 180_000L
        private var baseMs = 0L
        private var sinceMs = 0L
        val seeks = mutableListOf<Pair<Long, Long>>() // (position when seeking, target)
        val listeners = mutableListOf<Player.Listener>()

        val positionMs: Long get() = baseMs + if (playing) now() - sinceMs else 0L

        fun moveTo(positionMs: Long) {
            baseMs = positionMs
            sinceMs = now()
        }

        fun playOrPause(value: Boolean) {
            moveTo(positionMs)
            playing = value
            events()
        }

        fun events() = listeners.toList().forEach { it.onEvents(player, Player.Events(FlagSet.Builder().build())) }

        /** A seek as any controller makes it: the position jumps and listeners hear a seek discontinuity. */
        fun seek(targetMs: Long) {
            val old = info(positionMs)
            moveTo(targetMs)
            listeners.toList().forEach { it.onPositionDiscontinuity(old, info(targetMs), Player.DISCONTINUITY_REASON_SEEK) }
        }

        private fun info(positionMs: Long) =
            Player.PositionInfo(null, 0, current, null, 0, positionMs, positionMs, -1, -1)

        val player: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "getCurrentMediaItem" -> current
                    "isPlaying" -> playing
                    "getDuration" -> durationMs
                    "getCurrentPosition" -> positionMs
                    "seekTo" -> {
                        val target = args.last() as Long
                        seeks += positionMs to target
                        seek(target)
                        null
                    }
                    "addListener" -> listeners.add(args[0] as Player.Listener).let { null }
                    "removeListener" -> listeners.remove(args[0] as Player.Listener).let { null }
                    else -> throw UnsupportedOperationException(method.name)
                }
            },
        ) as Player
    }

    private companion object {
        fun entry(itemId: Long): MediaItem = MediaItem.Builder().setMediaId(QueueMediaId.format(1, itemId)).build()
    }

    private val scopes = mutableListOf<CoroutineScope>()

    /** A scope on the test clock that each test cancels at its end, so no watcher outlives it. */
    private fun TestScope.scope() = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()).also { scopes += it }

    private fun finish() = scopes.forEach { it.cancel() }

    /** A watcher on a playing fake with the loop set from 10 s to 12 s of entry 7. */
    private fun TestScope.looping(): Pair<FakePlayer, AbLoopWatcher> {
        val fake = FakePlayer { testScheduler.currentTime }
        val watcher = AbLoopWatcher(scope())
        watcher.attach(fake.player)
        watcher.toggle()
        fake.moveTo(10_000)
        watcher.tap()
        fake.moveTo(11_000)
        watcher.tap()
        watcher.moveEnd(12_000)
        fake.playOrPause(true)
        return fake to watcher
    }

    @Test
    fun `taps take the player's position and entry`() = runTest {
        val fake = FakePlayer { testScheduler.currentTime }
        val watcher = AbLoopWatcher(scope())
        watcher.attach(fake.player)
        watcher.toggle()
        fake.moveTo(10_000)
        watcher.tap()
        fake.moveTo(13_000)
        watcher.tap()

        assertThat(watcher.loop.value).isEqualTo(AbLoop(enabled = true, startMs = 10_000, endMs = 13_000, itemId = 7))
        finish()
    }

    @Test
    fun `playback loops back to A before passing B`() = runTest {
        val (fake, watcher) = looping()
        advanceTimeBy(6_000)
        runCurrent()

        assertThat(fake.seeks.size).isAtLeast(2)
        for ((at, target) in fake.seeks) {
            assertThat(target).isEqualTo(10_000)
            assertThat(at).isIn(12_000 - AbLoop.PRE_SEEK_MARGIN_MS until 12_000L)
        }
        assertThat(watcher.loop.value.isLooping).isTrue()
        finish()
    }

    @Test
    fun `a manual seek outside the span clears the loop and stops the jumps`() = runTest {
        val (fake, watcher) = looping()
        advanceTimeBy(500)
        fake.seek(11_000) // inside: kept
        assertThat(watcher.loop.value.isLooping).isTrue()

        fake.seek(60_000)
        val seeks = fake.seeks.size
        advanceTimeBy(5_000)
        runCurrent()

        assertThat(watcher.loop.value).isEqualTo(AbLoop(enabled = true))
        assertThat(fake.seeks).hasSize(seeks)
        finish()
    }

    @Test
    fun `a track change clears the loop`() = runTest {
        val (fake, watcher) = looping()
        fake.current = entry(8)
        fake.events()

        assertThat(watcher.loop.value).isEqualTo(AbLoop(enabled = true))
        finish()
    }

    @Test
    fun `nothing jumps while paused`() = runTest {
        val (fake, _) = looping()
        fake.playOrPause(false)
        fake.moveTo(11_990)
        advanceTimeBy(5_000)
        runCurrent()

        assertThat(fake.seeks).isEmpty()
        finish()
    }

    @Test
    fun `detaching stops the jumps and clears the points`() = runTest {
        val (fake, watcher) = looping()
        watcher.detach()
        advanceTimeBy(5_000)
        runCurrent()

        assertThat(fake.seeks).isEmpty()
        assertThat(fake.listeners).isEmpty()
        assertThat(watcher.loop.value).isEqualTo(AbLoop(enabled = true))
        finish()
    }

    @Test
    fun `not allowed (DJ mode), the loop is gone, its mode is off and the taps do nothing`() = runTest {
        val (fake, watcher) = looping()

        watcher.setAllowed(false)
        assertThat(watcher.loop.value).isEqualTo(AbLoop())
        advanceTimeBy(5_000)
        runCurrent()
        assertThat(fake.seeks).isEmpty()

        watcher.toggle()
        watcher.tap()
        watcher.moveStart(1_000)
        assertThat(watcher.loop.value).isEqualTo(AbLoop())

        watcher.setAllowed(true)
        watcher.toggle()
        assertThat(watcher.loop.value.enabled).isTrue()
        finish()
    }
}
