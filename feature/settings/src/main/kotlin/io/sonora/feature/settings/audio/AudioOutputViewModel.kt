package io.sonora.feature.settings.audio

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.playback.output.AudioOutputMonitor
import io.sonora.core.domain.playback.output.AudioOutputSnapshot
import io.sonora.core.domain.playback.output.BitPerfectMode
import io.sonora.core.domain.port.PreferencesRepository
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Audio output section shows: the outputs right now and whether the user switched bit-perfect USB output on. */
data class AudioOutputUiState(val outputs: AudioOutputSnapshot, val bitPerfectEnabled: Boolean) {
    /** Bit-perfect output is working: switched on, and the USB device that is heard offers it. */
    val armed: Boolean get() = BitPerfectMode.armed(bitPerfectEnabled, outputs)

    /** The switch is shown only where Android can do it (Android 14+). */
    val bitPerfectSupported: Boolean get() = outputs.mixerApi
}

/**
 * The Audio output section of Settings: what the phone is sending sound to, live while the section shows, and the
 * bit-perfect USB output switch. The playback service follows the stored switch; effects it turns off are shown as such
 * by [AudioOutputUiState.armed].
 */
@HiltViewModel
class AudioOutputViewModel @Inject constructor(
    monitor: AudioOutputMonitor,
    private val preferences: PreferencesRepository,
) : ViewModel() {

    /** The outputs and the switch; `null` until both are read, so the section never flashes "None" or a wrong switch. */
    val state: StateFlow<AudioOutputUiState?> = combine(monitor.snapshots, preferences.preferences.map { it.audio.bitPerfectUsb }, ::AudioOutputUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setBitPerfect(enabled: Boolean) {
        viewModelScope.launch {
            try {
                preferences.update { it.copy(audio = it.audio.copy(bitPerfectUsb = enabled)) }
            } catch (e: IOException) {
                Log.w(TAG, "Couldn't save the bit-perfect setting", e)
            }
        }
    }

    private companion object {
        const val TAG = "SonoraSettings"
    }
}
