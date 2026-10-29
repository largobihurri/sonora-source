package io.sonora.feature.settings.dj

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.djmodel.DjModeSettings
import io.sonora.core.domain.conversion.PerformanceMode
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Settings' DJ Mode switch: the app-wide flag that also gates the analysis (Now Playing's "…" menu flips the same one),
 * and the performance the analysis may use, the converter's three modes.
 */
@HiltViewModel
class DjModeSettingsViewModel @Inject constructor(private val settings: DjModeSettings) : ViewModel() {

    val enabled: StateFlow<Boolean> = settings.enabled

    fun setEnabled(enabled: Boolean) = settings.setEnabled(enabled)

    val performance: StateFlow<PerformanceMode> = settings.performance

    fun setPerformance(performance: PerformanceMode) = settings.setPerformance(performance)
}
