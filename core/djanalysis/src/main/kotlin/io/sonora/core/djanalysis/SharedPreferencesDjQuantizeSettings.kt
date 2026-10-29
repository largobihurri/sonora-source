package io.sonora.core.djanalysis

import android.content.SharedPreferences
import androidx.core.content.edit
import io.sonora.core.djmodel.DjQuantizeSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [DjQuantizeSettings] kept in [preferences] (the DJ mode file, [SharedPreferencesDjModeSettings.FILE_NAME]) under
 * [KEY]: on when absent, like a CDJ. It is a separate class from the DJ mode settings so the older port keeps its
 * shape; the file is not part of the app's backup, like the DJ mode flag.
 */
class SharedPreferencesDjQuantizeSettings(private val preferences: SharedPreferences) : DjQuantizeSettings {

    private val state = MutableStateFlow(preferences.getBoolean(KEY, true))

    override val quantize: StateFlow<Boolean> = state.asStateFlow()

    override fun setQuantize(on: Boolean) {
        preferences.edit { putBoolean(KEY, on) }
        state.value = on
    }

    companion object {
        const val KEY: String = "dj_quantize"
    }
}
