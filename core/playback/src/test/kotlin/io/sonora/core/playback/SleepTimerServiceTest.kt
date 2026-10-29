package io.sonora.core.playback

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.SleepEntry
import io.sonora.core.domain.playback.SleepPlayhead
import io.sonora.core.domain.playback.SleepTimerOption
import io.sonora.core.domain.playback.SleepTimerState
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

/** The service's sleep timer driving a fake player, on the test scheduler's virtual clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerServiceTest {

    /** The player as the timer sees it; playback moves only when a test moves it. */
    private class FakePlayback : SleepPlayback {
        var entries = listOf(SleepEntry("a", 200_000))
        var index = 0
        var positionMs = 0L
        var paused = false
        var pauses = 0
        var pausesAtEnd = false
        var entryEnded: (() -> Unit)? = null
        override var volume = 0.8f

        override fun playhead() = SleepPlayhead(entries, index, positionMs)

        override fun pause() {
            paused = true
            pauses++
        }

        override fun setPauseAtEndOfEntry(pause: Boolean) {
            pausesAtEnd = pause
        }

        override fun setOnEntryEnded(listener: (() -> Unit)?) {
            entryEnded = listener
        }

        /** Playback reached the end of the entry and paused there, as asked. */
        fun endEntry() {
            positionMs = entries[index].durationMs ?: positionMs
            paused = true
            entryEnded?.invoke()
        }
    }

    private val fake = FakePlayback()

    private fun TestScope.timer(): Pair<SleepTimerService, CoroutineScope> {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val timer = SleepTimerService { testScheduler.currentTime }
        timer.attach(fake, scope)
        return timer to scope
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `a clock timer counts down and pauses when it runs out`() = runTest {
        val (timer, scope) = timer()
        timer.start(SleepTimerOption.Minutes(15))
        runCurrent()
        assertThat(timer.state.value).isEqualTo(SleepTimerState(SleepTimerOption.Minutes(15), 900_000))

        advance(300_000)
        assertThat(timer.state.value?.remainingMs).isEqualTo(600_000)
        assertThat(fake.paused).isFalse()

        advance(600_000)
        assertThat(fake.paused).isTrue()
        assertThat(timer.state.value).isNull()
        scope.cancel()
    }

    @Test
    fun `the remaining time is shown in whole seconds, rounded up`() = runTest {
        val (timer, scope) = timer()
        timer.start(SleepTimerOption.Minutes(15))
        advance(1_500)

        assertThat(timer.state.value?.remainingMs).isEqualTo(899_000)
        scope.cancel()
    }

    @Test
    fun `the last ten seconds fade the volume out along the curve`() = runTest {
        val (timer, scope) = timer()
        timer.start(SleepTimerOption.Minutes(15))
        advance(889_000)
        assertThat(fake.volume).isEqualTo(0.8f)

        advance(1_000) // 10 s left: the fade starts
        assertThat(fake.volume).isWithin(1e-4f).of(0.8f)
        advance(5_000) // half way
        assertThat(fake.volume).isWithin(1e-3f).of(0.4f)
        advance(4_900) // 100 ms left
        assertThat(fake.volume).isLessThan(0.001f)
        assertThat(fake.paused).isFalse()
        scope.cancel()
    }

    @Test
    fun `the volume comes back once playback has paused`() = runTest {
        val (timer, scope) = timer()
        timer.start(SleepTimerOption.Minutes(15))
        advance(900_000)
        assertThat(fake.paused).isTrue()

        advance(SleepTimerService.RESTORE_DELAY_MS)
        assertThat(fake.volume).isEqualTo(0.8f)
        assertThat(timer.state.value).isNull()
        scope.cancel()
    }

    @Test
    fun `cancel stops the timer and restores the volume, without pausing`() = runTest {
        val (timer, scope) = timer()
        timer.start(SleepTimerOption.Minutes(15))
        advance(895_000) // 5 s left, half volume
        assertThat(fake.volume).isLessThan(0.5f)

        timer.cancel()
        assertThat(fake.volume).isEqualTo(0.8f)
        assertThat(timer.state.value).isNull()
        advance(60_000)
        assertThat(fake.paused).isFalse()
        assertThat(fake.volume).isEqualTo(0.8f)
        scope.cancel()
    }

    @Test
    fun `extending during the fade restores the volume and moves the end`() = runTest {
        val (timer, scope) = timer()
        timer.start(SleepTimerOption.Minutes(15))
        advance(895_000)

        timer.extend(10)
        runCurrent()
        assertThat(fake.volume).isEqualTo(0.8f)
        assertThat(timer.state.value?.remainingMs).isEqualTo(605_000)

        advance(600_000)
        assertThat(fake.paused).isFalse()
        advance(5_000)
        assertThat(fake.paused).isTrue()
        scope.cancel()
    }

    @Test
    fun `end of song pauses where the song ends, fading its last seconds`() = runTest {
        val (timer, scope) = timer()
        fake.positionMs = 150_000
        timer.start(SleepTimerOption.EndOfSong)
        runCurrent()
        assertThat(fake.pausesAtEnd).isTrue()
        assertThat(timer.state.value).isEqualTo(SleepTimerState(SleepTimerOption.EndOfSong, 50_000))

        // The clock alone never ends it: only playback does.
        advance(3_600_000)
        assertThat(timer.state.value).isNotNull()
        fake.positionMs = 195_000
        advance(1_000)
        assertThat(fake.volume).isWithin(1e-3f).of(0.4f)

        fake.endEntry()
        assertThat(timer.state.value).isNull()
        assertThat(fake.pausesAtEnd).isFalse()
        advance(SleepTimerService.RESTORE_DELAY_MS)
        assertThat(fake.volume).isEqualTo(0.8f)
        scope.cancel()
    }

    @Test
    fun `end of album pauses only at the end of the album's last song`() = runTest {
        val (timer, scope) = timer()
        fake.entries = listOf(SleepEntry("a", 100_000), SleepEntry("a", 100_000), SleepEntry("b", 100_000))
        timer.start(SleepTimerOption.EndOfAlbum)
        runCurrent()
        assertThat(fake.pausesAtEnd).isFalse()
        assertThat(timer.state.value?.remainingMs).isEqualTo(200_000)

        fake.index = 1 // the player moved on by itself
        fake.positionMs = 0
        advance(1_000)
        assertThat(fake.pausesAtEnd).isTrue()

        fake.endEntry()
        assertThat(timer.state.value).isNull()
        scope.cancel()
    }

    @Test
    fun `an entry ending while no song timer waits for it changes nothing`() = runTest {
        val (timer, scope) = timer()
        timer.start(SleepTimerOption.Minutes(30))
        runCurrent()

        fake.endEntry()
        assertThat(timer.state.value).isNotNull()
        scope.cancel()
    }

    @Test
    fun `a new timer replaces the running one`() = runTest {
        val (timer, scope) = timer()
        timer.start(SleepTimerOption.EndOfSong)
        runCurrent()
        timer.start(SleepTimerOption.Minutes(45))
        runCurrent()

        assertThat(fake.pausesAtEnd).isFalse()
        assertThat(timer.state.value).isEqualTo(SleepTimerState(SleepTimerOption.Minutes(45), 2_700_000))
        scope.cancel()
    }

    @Test
    fun `the service stopping drops the timer and restores the volume`() = runTest {
        val (timer, scope) = timer()
        timer.start(SleepTimerOption.Minutes(15))
        advance(895_000)

        timer.detach()
        assertThat(fake.volume).isEqualTo(0.8f)
        assertThat(fake.entryEnded).isNull()
        assertThat(timer.state.value).isNull()
        scope.cancel()
    }

    @Test
    fun `without the service nothing starts`() = runTest {
        val timer = SleepTimerService { testScheduler.currentTime }

        timer.start(SleepTimerOption.Minutes(15))
        assertThat(timer.state.value).isNull()
    }
}
