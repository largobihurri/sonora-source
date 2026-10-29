package io.sonora.core.data.preferences

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.di.createSettingsDataStore
import io.sonora.core.domain.model.AccentMode
import io.sonora.core.domain.model.AccentPreset
import io.sonora.core.domain.model.AppLanguage
import io.sonora.core.domain.model.AppPreferences
import io.sonora.core.domain.model.AppearancePreferences
import io.sonora.core.domain.model.AudioPreferences
import io.sonora.core.domain.model.ConverterPreferences
import io.sonora.core.domain.model.NavigationPreferences
import io.sonora.core.domain.model.NowPlayingPreferences
import io.sonora.core.domain.model.NowPlayingRotation
import io.sonora.core.domain.model.ThemeMode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The adapter against a real preferences DataStore file in a temporary folder, built as the app builds it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataStorePreferencesRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file by lazy { File(folder.root, "settings.preferences_pb") }

    /** Runs [block] with the app's DataStore over [file]; it is closed afterwards so the file can be reopened. */
    private fun <T> withStore(block: suspend (androidx.datastore.core.DataStore<Preferences>) -> T): T = runBlocking {
        val job = Job()
        val dataStore = createSettingsDataStore(CoroutineScope(Dispatchers.IO + job)) { file }
        try {
            block(dataStore)
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun <T> withRepository(block: suspend (DataStorePreferencesRepository) -> T): T =
        withStore { block(DataStorePreferencesRepository(it)) }

    /** The key names actually written to the file. */
    private fun storedKeys(): Set<String> = withStore { store -> store.data.first().asMap().keys.map { it.name }.toSet() }

    @Test
    fun `nothing stored yet reads as the defaults`() {
        val stored = withRepository { it.preferences.first() }

        assertThat(stored).isEqualTo(AppPreferences())
        assertThat(stored.nowPlaying.showLyricsButton).isFalse()
        assertThat(stored.nowPlaying.showFileInfoButton).isTrue()
        assertThat(stored.appearance)
            .isEqualTo(AppearancePreferences(ThemeMode.SYSTEM, AccentMode.MATERIAL_YOU, AccentPreset.PINK))
    }

    @Test
    fun `changes survive reopening the store`() {
        withRepository { repository ->
            repository.update { it.copy(nowPlaying = it.nowPlaying.copy(showLyricsButton = true)) }
            repository.update { it.copy(nowPlaying = it.nowPlaying.copy(showFileInfoButton = false)) }
        }

        val reopened = withRepository { it.preferences.first() }

        assertThat(reopened.nowPlaying).isEqualTo(NowPlayingPreferences(showLyricsButton = true, showFileInfoButton = false))
    }

    @Test
    fun `the Now Playing rotation choice survives reopening the store under its own key`() {
        assertThat(withRepository { it.preferences.first() }.nowPlaying.rotation).isEqualTo(NowPlayingRotation.NOW_PLAYING_LANDSCAPE)

        withRepository { repository ->
            repository.update { it.copy(nowPlaying = it.nowPlaying.copy(rotation = NowPlayingRotation.ALBUM_COVER_FLOW)) }
        }

        val reopened = withRepository { it.preferences.first() }.nowPlaying
        assertThat(reopened).isEqualTo(NowPlayingPreferences(rotation = NowPlayingRotation.ALBUM_COVER_FLOW))
        assertThat(storedKeys()).containsExactly("now_playing.rotation")
    }

    @Test
    fun `gravity mode reads as off until turned on, and the choice survives reopening the store under its own key`() {
        assertThat(withRepository { it.preferences.first() }.nowPlaying.gravity).isFalse()

        withRepository { repository -> repository.update { it.copy(nowPlaying = it.nowPlaying.copy(gravity = true)) } }

        val reopened = withRepository { it.preferences.first() }.nowPlaying
        assertThat(reopened).isEqualTo(NowPlayingPreferences(gravity = true))
        assertThat(storedKeys()).containsExactly("now_playing.gravity")

        withRepository { repository -> repository.update { it.copy(nowPlaying = it.nowPlaying.copy(gravity = false)) } }
        assertThat(withRepository { it.preferences.first() }.nowPlaying.gravity).isFalse()
    }

    @Test
    fun `bit-perfect USB output reads as off until turned on, and the choice survives reopening the store under its own key`() {
        assertThat(withRepository { it.preferences.first() }.audio.bitPerfectUsb).isFalse()

        withRepository { repository -> repository.update { it.copy(audio = it.audio.copy(bitPerfectUsb = true)) } }

        assertThat(withRepository { it.preferences.first() }.audio).isEqualTo(AudioPreferences(bitPerfectUsb = true))
        assertThat(storedKeys()).containsExactly("audio.bit_perfect_usb")

        withRepository { repository -> repository.update { it.copy(audio = it.audio.copy(bitPerfectUsb = false)) } }
        assertThat(withRepository { it.preferences.first() }.audio.bitPerfectUsb).isFalse()
    }

    @Test
    fun `the converter's batch-wide output reads as the XDJ-700 until chosen, and the choice survives reopening the store under its own key`() {
        assertThat(withRepository { it.preferences.first() }.converter).isEqualTo(ConverterPreferences(outputProfileId = "xdj-700"))

        withRepository { repository -> repository.update { it.copy(converter = ConverterPreferences(outputProfileId = "out:flac:24:keep")) } }

        assertThat(withRepository { it.preferences.first() }.converter.outputProfileId).isEqualTo("out:flac:24:keep")
        assertThat(storedKeys()).containsExactly("converter.output_profile_id")

        withRepository { repository -> repository.update { it.copy(converter = ConverterPreferences()) } }
        assertThat(withRepository { it.preferences.first() }.converter.outputProfileId).isEqualTo("xdj-700")
    }

    @Test
    fun `external control reads as off until turned on, and the choice survives reopening the store under its own key`() {
        assertThat(withRepository { it.preferences.first() }.externalControlEnabled).isFalse()
        assertThat(storedKeys()).isEmpty()

        withRepository { repository -> repository.update { it.copy(externalControlEnabled = true) } }

        assertThat(withRepository { it.preferences.first() }.externalControlEnabled).isTrue()
        assertThat(storedKeys()).containsExactly("external_control.enabled")

        withRepository { repository -> repository.update { it.copy(externalControlEnabled = false) } }
        assertThat(withRepository { it.preferences.first() }.externalControlEnabled).isFalse()
    }

    @Test
    fun `the tablet sidebar reads as shown until hidden, and the choice survives reopening the store under its own key`() {
        assertThat(withRepository { it.preferences.first() }.navigation.sidebarShown).isTrue()
        assertThat(storedKeys()).isEmpty()

        withRepository { repository -> repository.update { it.copy(navigation = NavigationPreferences(sidebarShown = false)) } }

        assertThat(withRepository { it.preferences.first() }.navigation).isEqualTo(NavigationPreferences(sidebarShown = false))
        assertThat(storedKeys()).containsExactly("navigation.sidebar_shown")

        withRepository { repository -> repository.update { it.copy(navigation = NavigationPreferences(sidebarShown = true)) } }
        assertThat(withRepository { it.preferences.first() }.navigation.sidebarShown).isTrue()
    }

    @Test
    fun `an unknown stored rotation reads as the landscape Now Playing`() {
        withStore { store -> store.edit { it[stringPreferencesKey("now_playing.rotation")] = "SPIN" } }

        assertThat(withRepository { it.preferences.first() }.nowPlaying.rotation).isEqualTo(NowPlayingRotation.NOW_PLAYING_LANDSCAPE)
    }

    @Test
    fun `appearance changes survive reopening the store`() {
        val chosen = AppearancePreferences(ThemeMode.DARK, AccentMode.PRESET, AccentPreset.TEAL)
        withRepository { repository -> repository.update { it.copy(appearance = chosen) } }

        val reopened = withRepository { it.preferences.first() }

        assertThat(reopened.appearance).isEqualTo(chosen)
        assertThat(reopened.nowPlaying).isEqualTo(NowPlayingPreferences())
    }

    @Test
    fun `Translucency reads as on until turned off, and the choice survives reopening the store`() {
        assertThat(withRepository { it.preferences.first() }.appearance.translucency).isTrue()

        withRepository { repository -> repository.update { it.copy(appearance = it.appearance.copy(translucency = false)) } }

        val reopened = withRepository { it.preferences.first() }.appearance
        assertThat(reopened.translucency).isFalse()
        assertThat(reopened.copy(translucency = true)).isEqualTo(AppearancePreferences())
        assertThat(storedKeys()).containsExactly("appearance.liquid_glass")

        withRepository { repository -> repository.update { it.copy(appearance = it.appearance.copy(translucency = true)) } }
        assertThat(withRepository { it.preferences.first() }.appearance.translucency).isTrue()
    }

    @Test
    fun `only the settings that differ from what is stored are written to the file`() {
        withRepository { repository ->
            repository.update { it } // nothing changed
            repository.update { it.copy(appearance = it.appearance.copy(theme = ThemeMode.SYSTEM)) } // the default again
        }
        assertThat(storedKeys()).isEmpty()

        withRepository { repository ->
            repository.update { it.copy(appearance = it.appearance.copy(accentMode = AccentMode.ARTWORK)) }
        }
        assertThat(storedKeys()).containsExactly("appearance.accent_mode")

        withRepository { repository ->
            repository.update { it.copy(nowPlaying = it.nowPlaying.copy(showLyricsButton = true)) }
        }
        assertThat(storedKeys()).containsExactly("appearance.accent_mode", "now_playing.show_lyrics_button")
    }

    @Test
    fun `an unknown stored name reads as the default`() {
        withStore { store ->
            store.edit {
                it[stringPreferencesKey("appearance.theme")] = "SEPIA"
                it[stringPreferencesKey("appearance.accent_preset")] = "MAUVE"
                it[stringPreferencesKey("appearance.accent_mode")] = "PRESET"
            }
        }

        val read = withRepository { it.preferences.first() }.appearance

        assertThat(read).isEqualTo(AppearancePreferences(ThemeMode.SYSTEM, AccentMode.PRESET, AccentPreset.PINK))
    }

    @Test
    fun `a corrupt file is replaced by the defaults and can be written again`() {
        file.writeBytes(ByteArray(64) { 0xFF.toByte() }) // not a preferences protobuf

        val read = withRepository { it.preferences.first() }
        assertThat(read).isEqualTo(AppPreferences())

        withRepository { repository -> repository.update { it.copy(appearance = it.appearance.copy(theme = ThemeMode.LIGHT)) } }
        assertThat(withRepository { it.preferences.first() }.appearance.theme).isEqualTo(ThemeMode.LIGHT)
    }

    @Test
    fun `collectors see each change`() {
        val seen = withRepository { repository ->
            coroutineScope {
                val values = async { repository.preferences.take(2).toList() }
                repository.preferences.first() // stored value read before the change
                repository.update { it.copy(nowPlaying = it.nowPlaying.copy(showLyricsButton = true)) }
                values.await()
            }
        }

        assertThat(seen.map { it.nowPlaying.showLyricsButton }).containsExactly(false, true).inOrder()
    }

    @Test
    fun `the language reads as Castellano until chosen, and the choice survives reopening the store`() {
        assertThat(withRepository { it.preferences.first() }.language).isEqualTo(AppLanguage.SPANISH)

        withRepository { repository -> repository.update { it.copy(language = AppLanguage.BASQUE) } }

        assertThat(withRepository { it.preferences.first() }.language).isEqualTo(AppLanguage.BASQUE)
        assertThat(storedKeys()).containsExactly("app.language")

        withRepository { repository -> repository.update { it.copy(language = AppLanguage.ENGLISH) } }
        assertThat(withRepository { it.preferences.first() }.language).isEqualTo(AppLanguage.ENGLISH)
    }

    @Test
    fun `an unknown stored language reads as Castellano`() {
        withStore { store -> store.edit { it[stringPreferencesKey("app.language")] = "KLINGON" } }

        assertThat(withRepository { it.preferences.first() }.language).isEqualTo(AppLanguage.SPANISH)
    }
}
