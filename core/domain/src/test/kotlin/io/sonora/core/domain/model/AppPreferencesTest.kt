package io.sonora.core.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppPreferencesTest {

    @Test
    fun `a fresh install hides the lyrics button and shows the file info button`() {
        val defaults = AppPreferences()

        assertThat(defaults.nowPlaying.showLyricsButton).isFalse()
        assertThat(defaults.nowPlaying.showFileInfoButton).isTrue()
    }

    @Test
    fun `a fresh install shows the landscape Now Playing when Now Playing is turned sideways`() {
        assertThat(AppPreferences().nowPlaying.rotation).isEqualTo(NowPlayingRotation.NOW_PLAYING_LANDSCAPE)
        assertThat(NowPlayingRotation.entries)
            .containsExactly(NowPlayingRotation.NOW_PLAYING_LANDSCAPE, NowPlayingRotation.ALBUM_VISUAL_COLLECTION).inOrder()
    }

    @Test
    fun `a fresh install keeps Now Playing's gravity mode off`() {
        assertThat(AppPreferences().nowPlaying.gravity).isFalse()
    }

    @Test
    fun `a fresh install shows the tablet sidebar`() {
        assertThat(AppPreferences().navigation.sidebarShown).isTrue()
    }

    @Test
    fun `a fresh install keeps external control off`() {
        assertThat(AppPreferences().externalControlEnabled).isFalse()
    }

    @Test
    fun `a fresh install keeps bit-perfect USB output off`() {
        assertThat(AppPreferences().audio.bitPerfectUsb).isFalse()
    }

    @Test
    fun `a fresh install follows the system theme with the Material You accent and Sonora pink as its preset`() {
        val defaults = AppPreferences().appearance

        assertThat(defaults.theme).isEqualTo(ThemeMode.SYSTEM)
        assertThat(defaults.accentMode).isEqualTo(AccentMode.MATERIAL_YOU)
        assertThat(defaults.preset).isEqualTo(AccentPreset.PINK)
    }

    @Test
    fun `a fresh install turns Translucency on`() {
        assertThat(AppPreferences().appearance.translucency).isTrue()
    }

    @Test
    fun `the system theme follows the device while light and dark are fixed`() {
        assertThat(ThemeMode.SYSTEM.isDark(systemDark = true)).isTrue()
        assertThat(ThemeMode.SYSTEM.isDark(systemDark = false)).isFalse()
        assertThat(ThemeMode.LIGHT.isDark(systemDark = true)).isFalse()
        assertThat(ThemeMode.DARK.isDark(systemDark = false)).isTrue()
    }

    @Test
    fun `the preset palette offers the hues from red to pink`() {
        assertThat(AccentPreset.entries).hasSize(9)
        assertThat(AccentPreset.entries.first()).isEqualTo(AccentPreset.RED)
        assertThat(AccentPreset.entries.last()).isEqualTo(AccentPreset.PINK)
    }

    @Test
    fun `a fresh install speaks Castellano, whatever the device language`() {
        assertThat(AppPreferences().language).isEqualTo(AppLanguage.SPANISH)
    }

    @Test
    fun `the languages are Castellano, English and Euskara, each tagged with its BCP 47 code`() {
        assertThat(AppLanguage.entries).containsExactly(AppLanguage.SPANISH, AppLanguage.ENGLISH, AppLanguage.BASQUE).inOrder()
        assertThat(AppLanguage.entries.map { it.tag }).containsExactly("es", "en", "eu").inOrder()
    }

    @Test
    fun `a language tag maps back to its language by its primary subtag`() {
        assertThat(AppLanguage.fromTag("eu")).isEqualTo(AppLanguage.BASQUE)
        assertThat(AppLanguage.fromTag("es-ES")).isEqualTo(AppLanguage.SPANISH)
        assertThat(AppLanguage.fromTag("EN-gb")).isEqualTo(AppLanguage.ENGLISH)
        assertThat(AppLanguage.fromTag("fr")).isNull()
        assertThat(AppLanguage.fromTag("")).isNull()
        assertThat(AppLanguage.fromTag(null)).isNull()
    }
}
