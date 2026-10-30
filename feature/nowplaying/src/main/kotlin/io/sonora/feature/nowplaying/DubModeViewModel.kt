package io.sonora.feature.nowplaying

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.dubmodel.DubModeSettings
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Now Playing's DUB mode switch: one flag for the whole app, also in Settings. Turning it on turns DJ mode off (and DJ
 * mode turns it off), so Now Playing shows at most one of the two layouts.
 */
@HiltViewModel
class DubModeViewModel @Inject constructor(private val settings: DubModeSettings) : ViewModel() {
    /** Whether this build offers DUB mode; without it the "…" menu has no DUB row. */
    val offered: Boolean = settings.offered

    val enabled: StateFlow<Boolean> = settings.enabled

    fun setEnabled(enabled: Boolean) = settings.setEnabled(enabled)
}
