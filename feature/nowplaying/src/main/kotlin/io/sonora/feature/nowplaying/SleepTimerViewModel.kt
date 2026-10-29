package io.sonora.feature.nowplaying

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.playback.SleepCustomDuration
import io.sonora.core.domain.playback.SleepTimer
import io.sonora.core.domain.playback.SleepTimerOption
import io.sonora.core.domain.playback.SleepTimerState
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Sleep Timer panel and Now Playing's countdown badge: the playback service's timer ([state]), its options, and
 * the custom duration being chosen ([customMinutes]), kept while the screen lives.
 */
@HiltViewModel
class SleepTimerViewModel @Inject constructor(private val timer: SleepTimer) : ViewModel() {
    val state: StateFlow<SleepTimerState?> = timer.state

    private val custom = MutableStateFlow(SleepCustomDuration.DEFAULT_MINUTES)
    val customMinutes: StateFlow<Int> = custom.asStateFlow()

    fun start(option: SleepTimerOption) = timer.start(option)

    fun startCustom() = timer.start(SleepTimerOption.Minutes(custom.value))

    fun stepCustom(steps: Int) {
        custom.value = SleepCustomDuration.step(custom.value, steps)
    }

    fun extend() = timer.extend(EXTEND_MINUTES)

    fun cancel() = timer.cancel()

    companion object {
        /** What "Add … Minutes" gives the running timer. */
        const val EXTEND_MINUTES = 10
    }
}
