package io.sonora.core.data.dub

import android.content.SharedPreferences
import androidx.core.content.edit
import io.sonora.core.dubmodel.DubModeSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [DubModeSettings] kept in [preferences] under [KEY] (default off), the only key of its file. The only writer is this
 * object. The file is not part of Sonora's settings backup, like DJ mode's flag.
 *
 * Turning DUB mode on then calls [turnOtherLayoutOff] (the app turns the DJ layout off there), so the two layouts are
 * not left on together; turning it off leaves the DJ layout as it is. The new layout goes on before the other goes off,
 * so a switch never reads "both off": the deck's loop, kept by both layouts, would let a held loop go in that moment.
 *
 * A process that died between the two writes leaves both flags stored on. The first read repairs it: when DUB mode is
 * stored on and [otherLayoutOn] (the DJ flag) is on too, DJ wins and DUB mode is turned off and stored off.
 */
class SharedPreferencesDubModeSettings(
    private val preferences: SharedPreferences,
    otherLayoutOn: () -> Boolean = { false },
    private val turnOtherLayoutOff: () -> Unit = {},
) : DubModeSettings {

    private val state = MutableStateFlow(readRepaired(otherLayoutOn))

    override val enabled: StateFlow<Boolean> = state.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY, enabled) }
        state.value = enabled
        if (enabled) turnOtherLayoutOff()
    }

    private fun readRepaired(otherLayoutOn: () -> Boolean): Boolean {
        val stored = preferences.getBoolean(KEY, false)
        if (!stored || !otherLayoutOn()) return stored
        preferences.edit { putBoolean(KEY, false) }
        return false
    }

    companion object {
        // The file and key names are persisted: never rename them.
        const val FILE_NAME: String = "sonora_dub_mode"
        const val KEY: String = "dub_mode_enabled"
    }
}
