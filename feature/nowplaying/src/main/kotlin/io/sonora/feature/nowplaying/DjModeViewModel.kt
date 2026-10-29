package io.sonora.feature.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.djmodel.AnalysisStatus
import io.sonora.core.djmodel.DjAnalysisStatusSource
import io.sonora.core.djmodel.DjModeSettings
import io.sonora.core.domain.port.PlaybackController
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Now Playing's DJ mode: whether it is [enabled] (one switch for the whole app, also in Settings) and, while it is on,
 * the analysis [playingStatus] of the song playing. Nothing reads the analysis while DJ mode is off.
 */
@HiltViewModel
class DjModeViewModel internal constructor(
    private val settings: DjModeSettings,
    status: DjAnalysisStatusSource,
    playingUri: Flow<String?>,
) : ViewModel() {

    @Inject
    constructor(settings: DjModeSettings, status: DjAnalysisStatusSource, playback: PlaybackController) :
        this(settings, status, playback.state.map { it.currentTrack?.uri?.takeIf(String::isNotEmpty) }.distinctUntilChanged())

    val enabled: StateFlow<Boolean> = settings.enabled

    /**
     * The analysis state of the song playing; null while DJ mode is off, nothing plays, or the analysis has not
     * reported that song yet (the chip reads that as waiting).
     */
    @OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
    val playingStatus: StateFlow<AnalysisStatus?> = settings.enabled
        .flatMapLatest { on ->
            if (on) combine(playingUri, status.status) { uri, all -> uri?.let(all::get) } else flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setEnabled(enabled: Boolean) = settings.setEnabled(enabled)
}
