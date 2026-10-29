package io.sonora.core.data.preferences

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.sonora.core.domain.model.AppPreferences
import io.sonora.core.domain.model.AppearancePreferences
import io.sonora.core.domain.model.AudioPreferences
import io.sonora.core.domain.model.ConverterPreferences
import io.sonora.core.domain.model.NavigationPreferences
import io.sonora.core.domain.model.NowPlayingPreferences
import io.sonora.core.domain.port.PreferencesRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [PreferencesRepository] stored in a preferences DataStore. Only values the user changed are written, so a key
 * missing from the file reads as the model's default, and changing a default later reaches every user who never
 * touched that setting. An unreadable file reads as the defaults instead of failing the app.
 */
@Singleton
class DataStorePreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : PreferencesRepository {

    override val preferences: Flow<AppPreferences> = dataStore.data
        .catch { e ->
            if (e !is IOException) throw e
            Log.w(TAG, "Preferences unreadable; using defaults", e)
            emit(emptyPreferences())
        }
        .map(PreferenceKeys::read)
        .distinctUntilChanged()

    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        dataStore.edit { stored -> PreferenceKeys.write(stored, transform(PreferenceKeys.read(stored))) }
    }

    private companion object {
        const val TAG = "SonoraPreferences"
    }
}

/** The DataStore key of each [AppPreferences] field. Key names and stored enum names are persisted: never rename one. */
internal object PreferenceKeys {
    private val SHOW_LYRICS_BUTTON = booleanPreferencesKey("now_playing.show_lyrics_button")
    private val SHOW_FILE_INFO_BUTTON = booleanPreferencesKey("now_playing.show_file_info_button")
    private val NOW_PLAYING_ROTATION = stringPreferencesKey("now_playing.rotation")
    private val NOW_PLAYING_GRAVITY = booleanPreferencesKey("now_playing.gravity")
    private val THEME = stringPreferencesKey("appearance.theme")
    private val ACCENT_MODE = stringPreferencesKey("appearance.accent_mode")
    private val ACCENT_PRESET = stringPreferencesKey("appearance.accent_preset")
    private val TRANSLUCENCY = booleanPreferencesKey("appearance.liquid_glass")
    private val LANGUAGE = stringPreferencesKey("app.language")
    private val BIT_PERFECT_USB = booleanPreferencesKey("audio.bit_perfect_usb")
    private val SIDEBAR_SHOWN = booleanPreferencesKey("navigation.sidebar_shown")
    private val EXTERNAL_CONTROL = booleanPreferencesKey("external_control.enabled")
    private val CONVERTER_OUTPUT_PROFILE = stringPreferencesKey("converter.output_profile_id")

    fun read(stored: Preferences): AppPreferences {
        val nowPlaying = NowPlayingPreferences()
        val appearance = AppearancePreferences()
        val audio = AudioPreferences()
        val navigation = NavigationPreferences()
        val converter = ConverterPreferences()
        val defaults = AppPreferences()
        return AppPreferences(
            nowPlaying = NowPlayingPreferences(
                showLyricsButton = stored[SHOW_LYRICS_BUTTON] ?: nowPlaying.showLyricsButton,
                showFileInfoButton = stored[SHOW_FILE_INFO_BUTTON] ?: nowPlaying.showFileInfoButton,
                rotation = stored.enum(NOW_PLAYING_ROTATION, nowPlaying.rotation),
                gravity = stored[NOW_PLAYING_GRAVITY] ?: nowPlaying.gravity,
            ),
            appearance = AppearancePreferences(
                theme = stored.enum(THEME, appearance.theme),
                accentMode = stored.enum(ACCENT_MODE, appearance.accentMode),
                preset = stored.enum(ACCENT_PRESET, appearance.preset),
                translucency = stored[TRANSLUCENCY] ?: appearance.translucency,
            ),
            audio = AudioPreferences(bitPerfectUsb = stored[BIT_PERFECT_USB] ?: audio.bitPerfectUsb),
            language = stored.enum(LANGUAGE, defaults.language),
            navigation = NavigationPreferences(sidebarShown = stored[SIDEBAR_SHOWN] ?: navigation.sidebarShown),
            externalControlEnabled = stored[EXTERNAL_CONTROL] ?: defaults.externalControlEnabled,
            converter = ConverterPreferences(outputProfileId = stored[CONVERTER_OUTPUT_PROFILE] ?: converter.outputProfileId),
        )
    }

    /** Writes the fields of [value] that differ from what [stored] reads as. */
    fun write(stored: MutablePreferences, value: AppPreferences) {
        val current = read(stored)
        stored.setIfChanged(SHOW_LYRICS_BUTTON, value.nowPlaying.showLyricsButton, current.nowPlaying.showLyricsButton)
        stored.setIfChanged(SHOW_FILE_INFO_BUTTON, value.nowPlaying.showFileInfoButton, current.nowPlaying.showFileInfoButton)
        stored.setIfChanged(NOW_PLAYING_ROTATION, value.nowPlaying.rotation.name, current.nowPlaying.rotation.name)
        stored.setIfChanged(NOW_PLAYING_GRAVITY, value.nowPlaying.gravity, current.nowPlaying.gravity)
        stored.setIfChanged(THEME, value.appearance.theme.name, current.appearance.theme.name)
        stored.setIfChanged(ACCENT_MODE, value.appearance.accentMode.name, current.appearance.accentMode.name)
        stored.setIfChanged(ACCENT_PRESET, value.appearance.preset.name, current.appearance.preset.name)
        stored.setIfChanged(TRANSLUCENCY, value.appearance.translucency, current.appearance.translucency)
        stored.setIfChanged(BIT_PERFECT_USB, value.audio.bitPerfectUsb, current.audio.bitPerfectUsb)
        stored.setIfChanged(LANGUAGE, value.language.name, current.language.name)
        stored.setIfChanged(SIDEBAR_SHOWN, value.navigation.sidebarShown, current.navigation.sidebarShown)
        stored.setIfChanged(EXTERNAL_CONTROL, value.externalControlEnabled, current.externalControlEnabled)
        stored.setIfChanged(CONVERTER_OUTPUT_PROFILE, value.converter.outputProfileId, current.converter.outputProfileId)
    }

    private fun <T> MutablePreferences.setIfChanged(key: Preferences.Key<T>, value: T, current: T) {
        if (value != current) this[key] = value
    }

    /** The enum stored by name under [key]; [default] when unset or no longer known (e.g. a removed preset). */
    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
        this[key]?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
}
