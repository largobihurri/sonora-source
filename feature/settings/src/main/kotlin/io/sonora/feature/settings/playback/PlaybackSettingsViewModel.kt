package io.sonora.feature.settings.playback

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.playback.CrossfadeRepository
import io.sonora.core.domain.playback.ReplayGainMode
import io.sonora.core.domain.playback.ReplayGainRepository
import io.sonora.core.domain.playback.ReplayGainSettings
import java.io.IOException
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Playback section of Settings: volume normalisation's mode and pre-amp, which the player follows live, and the
 * crossfade length, which the playback service follows from the next song on.
 */
@HiltViewModel
class PlaybackSettingsViewModel @Inject constructor(
    private val replayGain: ReplayGainRepository,
    private val crossfade: CrossfadeRepository,
) : ViewModel() {

    /** The stored settings; `null` until read, so a choice never jumps on its own. */
    val settings: StateFlow<ReplayGainSettings?> = replayGain.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _saveFailed = MutableStateFlow(false)

    /** Whether a change could not be saved (the screen keeps showing the stored value and explains why). */
    val saveFailed: StateFlow<Boolean> = _saveFailed.asStateFlow()

    fun setMode(mode: ReplayGainMode) = update { it.copy(mode = mode) }

    /** Stores the pre-amp in [PREAMP_STEP_DB] steps (the settings keep it within its range). */
    fun setPreamp(db: Float) = update { it.copy(preampDb = (db / PREAMP_STEP_DB).roundToInt() * PREAMP_STEP_DB) }

    /** The stored crossfade length in seconds (0 is off); `null` until read, so the slider never jumps on its own. */
    val crossfadeSeconds: StateFlow<Int?> = crossfade.seconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Stores [seconds]; a failed save leaves the stored length, which the slider shows, as it was. */
    fun setCrossfadeSeconds(seconds: Int) {
        viewModelScope.launch {
            try {
                crossfade.setSeconds(seconds)
            } catch (e: IOException) {
                Log.w(TAG, "Couldn't save the crossfade length", e)
            }
        }
    }

    fun dismissSaveFailed() {
        _saveFailed.value = false
    }

    private fun update(transform: (ReplayGainSettings) -> ReplayGainSettings) {
        viewModelScope.launch {
            try {
                replayGain.update(transform)
            } catch (e: IOException) {
                Log.w(TAG, "Couldn't save a playback setting", e)
                _saveFailed.value = true
            }
        }
    }

    companion object {
        /** The pre-amp slider's step. */
        const val PREAMP_STEP_DB = 0.5f

        private const val TAG = "SonoraSettings"
    }
}
