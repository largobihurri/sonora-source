package io.sonora.core.djanalysis

import android.content.SharedPreferences
import androidx.core.content.edit
import io.sonora.core.djmodel.DjModeSettings
import io.sonora.core.domain.conversion.PerformanceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [DjModeSettings] kept in [preferences]: the flag under [KEY] (default off) and the analysis performance under
 * [PERFORMANCE_KEY] (the [PerformanceMode] name, [PerformanceMode.DEFAULT] when absent or unreadable). The only writer
 * is this object, and the file is not part of the app's backup, like the flag.
 *
 * The DJ layout is exclusive with the DUB layout: turning DJ mode on then calls [turnOtherLayoutOff] (the app turns
 * DUB mode off there), so the two are not left on together; turning it off leaves DUB mode as it is. The new layout
 * goes on before the other goes off, so a switch never reads "both off": the deck's loop, kept by both layouts, would
 * let a held loop go in that moment.
 */
class SharedPreferencesDjModeSettings(
    private val preferences: SharedPreferences,
    private val turnOtherLayoutOff: () -> Unit = {},
) : DjModeSettings {

    private val state = MutableStateFlow(preferences.getBoolean(KEY, false))
    private val performanceState = MutableStateFlow(readPerformance())

    override val enabled: StateFlow<Boolean> = state.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY, enabled) }
        state.value = enabled
        if (enabled) turnOtherLayoutOff()
    }

    override val performance: StateFlow<PerformanceMode> = performanceState.asStateFlow()

    override fun setPerformance(performance: PerformanceMode) {
        preferences.edit { putString(PERFORMANCE_KEY, performance.name) }
        performanceState.value = performance
    }

    private fun readPerformance(): PerformanceMode =
        PerformanceMode.entries.firstOrNull { it.name == preferences.getString(PERFORMANCE_KEY, null) } ?: PerformanceMode.DEFAULT

    companion object {
        const val FILE_NAME: String = "sonora_dj_mode"
        const val KEY: String = "dj_mode_enabled"
        const val PERFORMANCE_KEY: String = "dj_analysis_performance"
    }
}
