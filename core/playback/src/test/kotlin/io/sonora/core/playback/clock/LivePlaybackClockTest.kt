package io.sonora.core.playback.clock

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.ClockReadFailure
import io.sonora.core.domain.playback.ClockSample
import io.sonora.core.domain.playback.LoopClockSource
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The service's player read as a clock: the queue item, the renderer position, the speed and whether it moves. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LivePlaybackClockTest {

    /** A player standing on a plain state; [update] swaps the state and lets the listeners hear about it. */
    private class FakePlayer(looper: Looper = Looper.getMainLooper()) : SimpleBasePlayer(looper) {
        var items: List<String> = listOf("10:77")
        var index = 0
        var positionMs = 0L
        var speed = 1f
        var shouldPlay = true
        var engineState = Player.STATE_READY

        override fun getState(): State {
            val playlist = items.map { id ->
                MediaItemData.Builder(id).setMediaItem(MediaItem.Builder().setMediaId(id).build()).build()
            }
            return State.Builder()
                .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
                .setPlaylist(playlist)
                .setCurrentMediaItemIndex(index)
                .setContentPositionMs(positionMs)
                .setPlaybackParameters(PlaybackParameters(speed))
                .setPlayWhenReady(shouldPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(engineState)
                .build()
        }

        fun update(change: FakePlayer.() -> Unit) {
            change()
            invalidateState()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private val loopSource = object : LoopClockSource {
        var looping = false

        override fun readInto(into: ClockSample) {
            into.looping = looping
            into.loopInMs = 1_000.0
            into.loopOutMs = 3_000.0
        }
    }
    private val clock = LivePlaybackClock(loopSource)
    private val sample = ClockSample()

    private fun attachedPlayer(): FakePlayer = FakePlayer().also {
        it.update { positionMs = 12_345L }
        clock.attach(it)
    }

    @Test
    fun `the loop being heard is part of the reading`() {
        attachedPlayer()

        loopSource.looping = true
        assertThat(clock.read(sample)).isTrue()
        assertThat(sample.looping).isTrue()
        assertThat(sample.loopInMs).isEqualTo(1_000.0)
        assertThat(sample.loopOutMs).isEqualTo(3_000.0)

        loopSource.looping = false
        clock.read(sample)
        assertThat(sample.looping).isFalse()
    }

    @Test
    fun `a clock without a loop source reads no loop`() {
        val plain = LivePlaybackClock()
        plain.attach(attachedPlayer().let { it })
        sample.looping = true

        plain.read(sample)

        assertThat(sample.looping).isFalse()
    }

    @Test
    fun `nothing attached, there is no clock`() {
        assertThat(clock.read(sample)).isFalse()
    }

    @Test
    fun `reads the queue item, the position, the speed and whether it plays`() {
        attachedPlayer().update { speed = 1.25f }

        assertThat(clock.read(sample)).isTrue()
        assertThat(sample.queueItemId).isEqualTo(77L)
        assertThat(sample.positionMs).isEqualTo(12_345L)
        assertThat(sample.speed).isEqualTo(1.25f)
        assertThat(sample.playing).isTrue()
    }

    @Test
    fun `paused or buffering is not playing`() {
        val player = attachedPlayer()

        player.update { shouldPlay = false }
        assertThat(clock.read(sample)).isTrue()
        assertThat(sample.playing).isFalse()

        player.update {
            shouldPlay = true
            engineState = Player.STATE_BUFFERING
        }
        assertThat(clock.read(sample)).isTrue()
        assertThat(sample.playing).isFalse()
    }

    @Test
    fun `another queue item is read as its own id, a repeated track apart from itself`() {
        val player = attachedPlayer()
        player.update { items = listOf("10:77", "11:78", "10:79") }

        clock.read(sample)
        assertThat(sample.queueItemId).isEqualTo(77L)

        player.update { index = 1 }
        clock.read(sample)
        assertThat(sample.queueItemId).isEqualTo(78L)

        player.update { index = 2 }
        clock.read(sample)
        assertThat(sample.queueItemId).isEqualTo(79L)
    }

    @Test
    fun `a media id without a queue item id reads as no item`() {
        attachedPlayer().update { items = listOf("abc") }

        assertThat(clock.read(sample)).isTrue()
        assertThat(sample.queueItemId).isEqualTo(ClockSample.NO_ITEM)
    }

    @Test
    fun `an empty queue has no clock and leaves the sample untouched`() {
        attachedPlayer().update {
            items = emptyList()
            engineState = Player.STATE_IDLE // Media3 only allows an empty playlist when idle or ended.
        }
        sample.positionMs = 99L

        assertThat(clock.read(sample)).isFalse()
        assertThat(sample.positionMs).isEqualTo(99L)
    }

    @Test
    fun `off the player's thread there is no clock`() {
        attachedPlayer()
        val result = AtomicBoolean(true)

        val thread = Thread { result.set(clock.read(ClockSample())) }
        thread.start()
        thread.join()

        assertThat(result.get()).isFalse()
    }

    @Test
    fun `a player on another looper is not read from the main thread`() {
        val worker = android.os.HandlerThread("clock-test").apply { start() }
        try {
            clock.attach(FakePlayer(worker.looper))

            assertThat(clock.read(sample)).isFalse()
        } finally {
            worker.quit()
        }
    }

    @Test
    fun `detached, there is no clock again`() {
        attachedPlayer()
        assertThat(clock.read(sample)).isTrue()

        clock.detach()

        assertThat(clock.read(sample)).isFalse()
    }

    @Test
    fun `each refusal says why, and a good read clears it`() {
        assertThat(clock.read(sample)).isFalse()
        assertThat(clock.lastFailure).isEqualTo(ClockReadFailure.NO_PLAYER)

        val player = attachedPlayer()
        assertThat(clock.read(sample)).isTrue()
        assertThat(clock.lastFailure).isEqualTo(ClockReadFailure.NONE)

        val thread = Thread { clock.read(ClockSample()) }
        thread.start()
        thread.join()
        assertThat(clock.lastFailure).isEqualTo(ClockReadFailure.WRONG_THREAD)

        player.update {
            items = emptyList()
            engineState = Player.STATE_IDLE
        }
        assertThat(clock.read(sample)).isFalse()
        assertThat(clock.lastFailure).isEqualTo(ClockReadFailure.NO_ITEM)
    }
}
