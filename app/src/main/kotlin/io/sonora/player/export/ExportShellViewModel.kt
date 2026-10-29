package io.sonora.player.export

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.feature.cdjexport.ExportPlaylistRequest
import io.sonora.feature.cdjexport.ExportRequests
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Lets the app shell follow export requests from other screens: it shows the Export tab while one is pending. */
@HiltViewModel
class ExportShellViewModel @Inject constructor(requests: ExportRequests) : ViewModel() {
    val pending: StateFlow<ExportPlaylistRequest?> = requests.pending
}
