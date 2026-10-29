package io.sonora.player.ui

import io.sonora.core.domain.model.AccentMode
import io.sonora.core.domain.model.AccentPreset
import io.sonora.core.domain.model.AppPreferences
import io.sonora.core.domain.model.AppearancePreferences
import io.sonora.core.domain.model.NowPlayingPreferences
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.ThemeMode
import io.sonora.core.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The theme inputs MainActivity waits for before drawing; collected on an unconfined scope, so every emit is seen. */
class AppearanceInputsTest {

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val preferences = MutableSharedFlow<AppPreferences>(replay = 1)
    private val playback = MutableStateFlow(PlaybackState())
    private val inputs = AppearanceInputs(scope, preferences, playback)

    private fun track(artwork: String?) = Track(
        id = 1, uri = "content://doc/1", title = "T", artist = "A", album = "B", albumArtist = null, durationMs = 1_000,
        trackNumber = null, discNumber = null, year = null, albumId = 1, artworkUri = artwork, mimeType = "audio/flac",
        sizeBytes = 1,
    )

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `nothing is known until the settings are read, then the stored theme comes first`() {
        assertNull(inputs.appearance.value)

        val stored = AppearancePreferences(ThemeMode.DARK, AccentMode.PRESET, AccentPreset.TEAL)
        preferences.tryEmit(AppPreferences(appearance = stored))

        assertEquals(stored, inputs.appearance.value)
    }

    @Test
    fun `other settings changing do not re-emit the appearance`() {
        val seen = mutableListOf<AppearancePreferences?>()
        scope.launch { inputs.appearance.collect { seen += it } }

        preferences.tryEmit(AppPreferences())
        preferences.tryEmit(AppPreferences(nowPlaying = NowPlayingPreferences(showLyricsButton = true)))
        preferences.tryEmit(AppPreferences(appearance = AppearancePreferences(theme = ThemeMode.LIGHT)))

        assertEquals(listOf(null, AppearancePreferences(), AppearancePreferences(theme = ThemeMode.LIGHT)), seen)
    }

    @Test
    fun `the artwork follows the playing track and ignores position ticks`() {
        val seen = mutableListOf<String?>()
        scope.launch { inputs.artworkUri.collect { seen += it } }

        playback.value = PlaybackState(currentTrack = track("file:///a.jpg"), isPlaying = true)
        repeat(20) { tick -> playback.value = playback.value.copy(positionMs = tick * 250L) }
        playback.value = playback.value.copy(isPlaying = false)
        playback.value = PlaybackState(currentTrack = track("file:///b.jpg"))

        assertEquals(listOf(null, "file:///a.jpg", "file:///b.jpg"), seen)
    }
}
