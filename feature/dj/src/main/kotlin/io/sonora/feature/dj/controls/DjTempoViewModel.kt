package io.sonora.feature.dj.controls

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.playback.PlaybackTempoRepository
import javax.inject.Inject

/** The DJ tempo section's view model: a [DjTempoController] over the app's tempo setting, in the view model's scope. */
@HiltViewModel
class DjTempoViewModel @Inject constructor(store: PlaybackTempoRepository) : ViewModel() {

    val controller = DjTempoController(store, viewModelScope) { Log.w(TAG, "Tempo not stored", it) }

    private companion object {
        const val TAG = "SonoraDjTempo"
    }
}
