package io.sonora.feature.nowplaying

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.core.domain.playback.PlaybackTempoRepository
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Now Playing's Speed & Pitch: the stored [tempo] (one setting for the whole app, which the player follows) and
 * whether its panel is open. Every change is stored at once; the player hears it from the store.
 */
@HiltViewModel
class PlaybackTempoViewModel @Inject constructor(private val store: PlaybackTempoRepository) : ViewModel() {

    val tempo: StateFlow<PlaybackTempo> = store.tempo.stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackTempo.DEFAULT)

    private val open = MutableStateFlow(false)
    val panelOpen: StateFlow<Boolean> = open.asStateFlow()

    /** The speed slider moved to [speed] (a factor); it snaps to the 0.05× grid, stored only when that changes it. */
    fun setSpeed(speed: Float) {
        if (tempo.value.withSpeed(speed) != tempo.value) update { it.withSpeed(speed) }
    }

    fun stepSpeed(steps: Int) = update { it.stepSpeed(steps) }

    /** The pitch slider moved to [semitones]; it snaps to the nearest semitone, stored only when that changes it. */
    fun setSemitones(semitones: Float) {
        val snapped = semitones.roundToInt()
        if (tempo.value.withSemitones(snapped) != tempo.value) update { it.withSemitones(snapped) }
    }

    fun stepPitch(steps: Int) = update { it.stepPitch(steps) }

    /** Back to normal speed and pitch. */
    fun reset() = update { PlaybackTempo.DEFAULT }

    fun openPanel() {
        open.value = true
    }

    fun closePanel() {
        open.value = false
    }

    private fun update(transform: (PlaybackTempo) -> PlaybackTempo) {
        viewModelScope.launch {
            try {
                store.update(transform)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Speed and pitch not stored", e)
            }
        }
    }

    private companion object {
        const val TAG = "SonoraTempo"
    }
}
