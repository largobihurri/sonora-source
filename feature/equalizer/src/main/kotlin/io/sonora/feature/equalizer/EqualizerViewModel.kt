package io.sonora.feature.equalizer

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.equalizer.EqualizerMode
import io.sonora.core.domain.equalizer.EqualizerSettings
import io.sonora.core.domain.port.EqualizerRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The equalizer screen. Every change goes through [EqualizerRepository], which the player follows live: a band being
 * dragged is previewed (heard at once, not stored) and stored when released; anything else is stored straight away.
 * A change that cannot be stored is logged and dropped: the repository puts everyone back on the stored settings.
 */
@HiltViewModel
class EqualizerViewModel @Inject constructor(private val repository: EqualizerRepository) : ViewModel() {

    /**
     * The latest settings, from the repository or previewed here: a drag builds on the one before it even when
     * [settings] has not caught up yet (two quick drags, two fingers).
     */
    private var latest: EqualizerSettings? = null

    val settings: StateFlow<EqualizerSettings> = repository.settings
        .onEach { latest = it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EqualizerSettings())

    fun setEnabled(enabled: Boolean) = store { it.withEnabled(enabled) }

    fun selectMode(mode: EqualizerMode) = store { it.withMode(mode) }

    /** On: the preamp lowers everything by the highest boost. Off: no preamp, a limiter holds the peaks. */
    fun setAutomaticPreamp(automatic: Boolean) = store { it.withAutomaticPreamp(automatic) }

    /** [band] of the mode on screen follows the finger at [gainDb]. */
    fun dragBand(band: Int, gainDb: Float) {
        val current = latest ?: settings.value
        val next = current.withGain(current.mode, band, gainDb)
        latest = next
        repository.preview(next)
    }

    fun releaseBand(band: Int, gainDb: Float) = store { it.withGain(it.mode, band, gainDb) }

    /** A drag taken over by another gesture: [band] goes back to [gainDb], its gain before the drag. */
    fun cancelBand(band: Int, gainDb: Float) = store { it.withGain(it.mode, band, gainDb) }

    fun resetBand(band: Int) = store { it.withGain(it.mode, band, 0f) }

    /** Flattens the mode on screen. */
    fun reset() = store { it.reset(it.mode) }

    private fun store(transform: (EqualizerSettings) -> EqualizerSettings) {
        viewModelScope.launch {
            try {
                repository.update(transform)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Equalizer settings not saved", e)
            }
        }
    }

    private companion object {
        const val TAG = "SonoraEqualizer"
    }
}
