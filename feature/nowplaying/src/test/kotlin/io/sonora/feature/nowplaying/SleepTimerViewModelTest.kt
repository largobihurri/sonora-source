package io.sonora.feature.nowplaying

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.playback.SleepCustomDuration
import io.sonora.core.domain.playback.SleepTimer
import io.sonora.core.domain.playback.SleepTimerOption
import io.sonora.core.domain.playback.SleepTimerState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

/** The sleep timer panel's actions, handed to the service's timer. */
class SleepTimerViewModelTest {

    private class FakeSleepTimer : SleepTimer {
        override val state = MutableStateFlow<SleepTimerState?>(null)
        val calls = mutableListOf<String>()

        override fun start(option: SleepTimerOption) {
            calls += "start $option"
        }

        override fun extend(minutes: Int) {
            calls += "extend $minutes"
        }

        override fun cancel() {
            calls += "cancel"
        }
    }

    private val timer = FakeSleepTimer()
    private val viewModel = SleepTimerViewModel(timer)

    @Test
    fun `the panel shows the service's timer`() {
        val running = SleepTimerState(SleepTimerOption.EndOfSong, 42_000)
        timer.state.value = running

        assertThat(viewModel.state.value).isEqualTo(running)
    }

    @Test
    fun `options, extend and cancel go to the timer`() {
        viewModel.start(SleepTimerOption.Minutes(30))
        viewModel.extend()
        viewModel.cancel()

        assertThat(timer.calls).containsExactly(
            "start ${SleepTimerOption.Minutes(30)}",
            "extend ${SleepTimerViewModel.EXTEND_MINUTES}",
            "cancel",
        ).inOrder()
    }

    @Test
    fun `the custom duration steps and starts a timer of that length`() {
        assertThat(viewModel.customMinutes.value).isEqualTo(SleepCustomDuration.DEFAULT_MINUTES)

        viewModel.stepCustom(+2)
        viewModel.stepCustom(-1)
        viewModel.startCustom()

        assertThat(viewModel.customMinutes.value).isEqualTo(SleepCustomDuration.DEFAULT_MINUTES + 5)
        assertThat(timer.calls).containsExactly("start ${SleepTimerOption.Minutes(SleepCustomDuration.DEFAULT_MINUTES + 5)}")
    }
}
