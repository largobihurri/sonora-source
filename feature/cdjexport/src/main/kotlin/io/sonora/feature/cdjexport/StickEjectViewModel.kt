package io.sonora.feature.cdjexport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.export.RemovableVolume
import io.sonora.core.domain.export.RemovableVolumes
import io.sonora.feature.cdjexport.export.EjectScreen
import io.sonora.feature.cdjexport.export.EjectState
import io.sonora.feature.cdjexport.export.StickEject
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * "Eject stick": once Sonora has let go of the stick (the screen makes the other sections do so first), the screen
 * opens Android's storage settings where the stick is ejected, and this says when Android has let go of it too
 * ([StickEject]).
 */
@HiltViewModel
class StickEjectViewModel @Inject constructor(volumes: RemovableVolumes) : ViewModel() {

    private val _state = MutableStateFlow<EjectState>(EjectState.Idle)
    val state: StateFlow<EjectState> = _state.asStateFlow()

    /** The volumes as last reported. Main thread. */
    private var latest: List<RemovableVolume> = emptyList()

    init {
        viewModelScope.launch {
            volumes.volumes().collect { list ->
                latest = list
                _state.update { StickEject.onVolumes(it, list) }
            }
        }
    }

    /** Starts ejecting the chosen stick [chosenUuid] (or, with none, the only volume in use). */
    fun eject(chosenUuid: String?) {
        _state.value = StickEject.request(chosenUuid, latest)
    }

    /** The screen opened [screen] for the eject, or none would (`null`). */
    fun onScreenOpened(screen: EjectScreen?) = _state.update { StickEject.onScreenOpened(it, screen) }

    fun dismiss() {
        _state.value = EjectState.Idle
    }
}
