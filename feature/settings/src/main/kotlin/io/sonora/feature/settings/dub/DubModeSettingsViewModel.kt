package io.sonora.feature.settings.dub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.dubmodel.DubModeSettings
import io.sonora.core.dubmodel.DubSetup
import io.sonora.core.dubmodel.DubSetupRepository
import io.sonora.core.dubmodel.PadBehaviour
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings' DUB Mode: the app-wide flag (Now Playing's "…" menu flips the same one; turning it on turns DJ mode off),
 * how the pads answer a touch, and a reset of the stored effect setup (echo, filter lock, preamp) to its defaults.
 */
@HiltViewModel
class DubModeSettingsViewModel @Inject constructor(
    private val settings: DubModeSettings,
    private val setups: DubSetupRepository,
) : ViewModel() {

    val enabled: StateFlow<Boolean> = settings.enabled

    fun setEnabled(enabled: Boolean) = settings.setEnabled(enabled)

    val padBehaviour: StateFlow<PadBehaviour> =
        setups.setup.map { it.padBehaviour }.stateIn(viewModelScope, SharingStarted.Eagerly, PadBehaviour.HOLD_DOUBLE_TAP_LATCH)

    fun setPadBehaviour(behaviour: PadBehaviour) {
        viewModelScope.launch { setups.update { it.copy(padBehaviour = behaviour) } }
    }

    /** Every stored DUB setting back to its default; DUB mode stays as it is. */
    fun resetSetup() {
        viewModelScope.launch { setups.update { DubSetup() } }
    }
}
