package io.sonora.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.model.AppPreferences
import io.sonora.core.domain.model.AppearancePreferences
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.domain.port.PreferencesRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What the app theme is built from: the appearance settings and, for "follow artwork", the playing artwork. */
@HiltViewModel
class AppearanceViewModel @Inject constructor(
    preferences: PreferencesRepository,
    playback: PlaybackController,
) : ViewModel() {
    private val inputs = AppearanceInputs(viewModelScope, preferences.preferences, playback.state)

    /** `null` until the settings are read, so the first frame never shows the wrong theme. */
    val appearance: StateFlow<AppearancePreferences?> = inputs.appearance

    /** The playing track's artwork; position ticks do not re-emit. */
    val artworkUri: StateFlow<String?> = inputs.artworkUri
}

/** [AppearanceViewModel]'s flows over any [scope], so they are testable without Android's main thread. */
internal class AppearanceInputs(scope: CoroutineScope, preferences: Flow<AppPreferences>, playback: Flow<PlaybackState>) {
    val appearance: StateFlow<AppearancePreferences?> = preferences
        .map { it.appearance }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    val artworkUri: StateFlow<String?> = playback
        .map { it.currentTrack?.artworkUri }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)
}
