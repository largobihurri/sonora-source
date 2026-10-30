package io.sonora.feature.settings

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.AccentMode
import io.sonora.core.domain.model.AccentPreset
import io.sonora.core.domain.model.AddFolderResult
import io.sonora.core.domain.model.AppLanguage
import io.sonora.core.domain.model.AppPreferences
import io.sonora.core.domain.model.AppearancePreferences
import io.sonora.core.domain.model.FolderConflict
import io.sonora.core.domain.model.LibraryFolder
import io.sonora.core.domain.model.NowPlayingPreferences
import io.sonora.core.domain.model.NowPlayingRotation
import io.sonora.core.domain.model.ScanProgress
import io.sonora.core.domain.model.ThemeMode
import io.sonora.core.domain.port.VisualCollectionPreferencesRepository
import io.sonora.core.domain.port.LibraryFolderRepository
import io.sonora.core.domain.port.LibraryScanner
import io.sonora.core.domain.port.PreferencesRepository
import io.sonora.core.watchport.UnavailableWatchSync
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // setMain, resetMain
class SettingsViewModelTest {

    private val music = LibraryFolder("content://tree/music", "Music", 1)

    /** Answers every add with [result], or throws [failure] when set. */
    private class FakeFolders(var result: AddFolderResult, var failure: Exception? = null) : LibraryFolderRepository {
        override fun folders(): Flow<List<LibraryFolder>> = flowOf(emptyList())
        override suspend fun add(treeUri: String): AddFolderResult = failure?.let { throw it } ?: result
        override suspend fun remove(treeUri: String) = Unit
    }

    private val scanner = object : LibraryScanner {
        override val progress: StateFlow<ScanProgress> = MutableStateFlow(ScanProgress.Idle)
        override fun requestScan() = Unit
    }

    /** In-memory preferences store; every save fails with [failure] when set. */
    private class FakePreferences : PreferencesRepository {
        val stored = MutableStateFlow(AppPreferences())
        var failure: Exception? = null
        override val preferences: Flow<AppPreferences> = stored
        override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
            failure?.let { throw it }
            stored.value = transform(stored.value)
        }
    }

    /** In-memory visual collection settings; every save fails with [failure] when set. */
    private class FakeVisualCollection : VisualCollectionPreferencesRepository {
        val stored = MutableStateFlow(false)
        var failure: Exception? = null
        override val hideAlbumsWithoutArtwork: Flow<Boolean> = stored
        override suspend fun setHideAlbumsWithoutArtwork(hide: Boolean) {
            failure?.let { throw it }
            stored.value = hide
        }
    }

    private val folders = FakeFolders(AddFolderResult.Added(music))
    private val preferences = FakePreferences()
    private val visualCollection = FakeVisualCollection()
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = SettingsViewModel(folders, scanner, preferences, visualCollection, UnavailableWatchSync)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an added folder shows no notice and clears an earlier one`() {
        viewModel.pickerMissing()

        viewModel.addFolder(music.treeUri)

        assertThat(viewModel.notice.value).isNull()
    }

    @Test
    fun `a rejected folder explains the conflict`() {
        val conflict = FolderConflict.InsideExisting(music)
        folders.result = AddFolderResult.Rejected(conflict)

        viewModel.addFolder("content://tree/music%2FRock")

        assertThat(viewModel.notice.value).isEqualTo(FolderNotice.Conflict(conflict))
    }

    @Test
    fun `a folder that cannot be added shows the add failure until dismissed`() {
        folders.failure = SecurityException("no persistable grant")

        viewModel.addFolder(music.treeUri)

        assertThat(viewModel.notice.value).isEqualTo(FolderNotice.AddFailed)
        viewModel.dismissNotice()
        assertThat(viewModel.notice.value).isNull()
    }

    @Test
    fun `the Now Playing switches show the stored preferences, lyrics off and file info on by default`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.nowPlaying.collect {} } // as the screen does

            assertThat(viewModel.nowPlaying.value).isEqualTo(NowPlayingPreferences(showLyricsButton = false, showFileInfoButton = true))
            preferences.stored.value = AppPreferences(NowPlayingPreferences(showLyricsButton = true, showFileInfoButton = false))
            assertThat(viewModel.nowPlaying.value).isEqualTo(NowPlayingPreferences(showLyricsButton = true, showFileInfoButton = false))
        }

    @Test
    fun `the Now Playing rotation choice is stored and keeps the switches`() {
        viewModel.setShowLyricsButton(true)
        viewModel.setNowPlayingRotation(NowPlayingRotation.ALBUM_VISUAL_COLLECTION)

        assertThat(preferences.stored.value.nowPlaying)
            .isEqualTo(NowPlayingPreferences(showLyricsButton = true, rotation = NowPlayingRotation.ALBUM_VISUAL_COLLECTION))
    }

    @Test
    fun `the gravity switch is off by default and turning it on stores only that choice`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.nowPlaying.collect {} }
            assertThat(viewModel.nowPlaying.value?.gravity).isFalse()

            viewModel.setGravity(true)

            assertThat(viewModel.nowPlaying.value?.gravity).isTrue()
            assertThat(preferences.stored.value).isEqualTo(AppPreferences(NowPlayingPreferences(gravity = true)))
        }

    @Test
    fun `the external control switch is off by default and turning it on stores only that choice`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.externalControl.collect {} }
            assertThat(viewModel.externalControl.value).isFalse()

            viewModel.setExternalControl(true)

            assertThat(viewModel.externalControl.value).isTrue()
            assertThat(preferences.stored.value).isEqualTo(AppPreferences(externalControlEnabled = true))

            viewModel.setExternalControl(false)

            assertThat(viewModel.externalControl.value).isFalse()
            assertThat(preferences.stored.value).isEqualTo(AppPreferences())
        }

    @Test
    fun `the external control switch shows the stored value and stays unknown until it is read`() =
        runTest(UnconfinedTestDispatcher()) {
            preferences.stored.value = AppPreferences(externalControlEnabled = true)
            assertThat(viewModel.externalControl.value).isNull()

            backgroundScope.launch { viewModel.externalControl.collect {} }

            assertThat(viewModel.externalControl.value).isTrue()
        }

    @Test
    fun `a failed save of the external control switch keeps it off and explains itself`() {
        preferences.failure = IOException("disk full")

        viewModel.setExternalControl(true)

        assertThat(preferences.stored.value.externalControlEnabled).isFalse()
        assertThat(viewModel.notice.value).isEqualTo(FolderNotice.SaveFailed)
    }

    @Test
    fun `flipping a switch stores only that preference`() {
        viewModel.setShowLyricsButton(true)
        viewModel.setShowFileInfoButton(false)
        viewModel.setShowLyricsButton(false)

        assertThat(preferences.stored.value.nowPlaying)
            .isEqualTo(NowPlayingPreferences(showLyricsButton = false, showFileInfoButton = false))
    }

    @Test
    fun `the appearance shows the stored choice, the system theme and Material You by default`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.appearance.collect {} }

            assertThat(viewModel.appearance.value).isEqualTo(AppearancePreferences(ThemeMode.SYSTEM, AccentMode.MATERIAL_YOU, AccentPreset.PINK))
            preferences.stored.value = AppPreferences(appearance = AppearancePreferences(ThemeMode.DARK, AccentMode.ARTWORK, AccentPreset.BLUE))
            assertThat(viewModel.appearance.value).isEqualTo(AppearancePreferences(ThemeMode.DARK, AccentMode.ARTWORK, AccentPreset.BLUE))
        }

    @Test
    fun `each setter updates its own field and leaves the other groups alone`() {
        viewModel.setShowLyricsButton(true)
        viewModel.setTheme(ThemeMode.LIGHT)
        viewModel.setAccentMode(AccentMode.PRESET)
        viewModel.setAccentPreset(AccentPreset.TEAL)

        assertThat(preferences.stored.value).isEqualTo(
            AppPreferences(
                nowPlaying = NowPlayingPreferences(showLyricsButton = true),
                appearance = AppearancePreferences(ThemeMode.LIGHT, AccentMode.PRESET, AccentPreset.TEAL),
            ),
        )
    }

    @Test
    fun `the Translucency switch is on by default and turning it off stores only that choice`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.appearance.collect {} }
            assertThat(viewModel.appearance.value?.translucency).isTrue()

            viewModel.setTranslucency(false)

            assertThat(viewModel.appearance.value?.translucency).isFalse()
            assertThat(preferences.stored.value).isEqualTo(AppPreferences(appearance = AppearancePreferences(translucency = false)))
        }

    @Test
    fun `a setting that cannot be saved keeps the stored value and explains instead of crashing`() = runTest(UnconfinedTestDispatcher()) {
        backgroundScope.launch { viewModel.nowPlaying.collect {} }
        preferences.failure = IOException("disk full")

        viewModel.setShowLyricsButton(true)
        viewModel.setTheme(ThemeMode.DARK)

        assertThat(viewModel.nowPlaying.value).isEqualTo(NowPlayingPreferences())
        assertThat(preferences.stored.value).isEqualTo(AppPreferences())
        assertThat(viewModel.notice.value).isEqualTo(FolderNotice.SaveFailed)
    }

    @Test
    fun `the language shows the stored choice, Castellano by default, and a new choice is stored`() = runTest(UnconfinedTestDispatcher()) {
        backgroundScope.launch { viewModel.language.collect {} }

        assertThat(viewModel.language.value).isEqualTo(AppLanguage.SPANISH)

        viewModel.setLanguage(AppLanguage.BASQUE)

        assertThat(preferences.stored.value.language).isEqualTo(AppLanguage.BASQUE)
        assertThat(viewModel.language.value).isEqualTo(AppLanguage.BASQUE)
    }

    @Test
    fun `the visual collection artwork switch is off by default and turning it on stores only that choice`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.hideAlbumsWithoutArtwork.collect {} } // as the screen does
            assertThat(viewModel.hideAlbumsWithoutArtwork.value).isFalse()

            viewModel.setHideAlbumsWithoutArtwork(true)

            assertThat(viewModel.hideAlbumsWithoutArtwork.value).isTrue()
            assertThat(visualCollection.stored.value).isTrue()
            assertThat(preferences.stored.value).isEqualTo(AppPreferences())
        }

    @Test
    fun `a visual collection artwork switch that cannot be saved keeps the stored value and explains`() =
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { viewModel.hideAlbumsWithoutArtwork.collect {} }
            visualCollection.failure = IOException("disk full")

            viewModel.setHideAlbumsWithoutArtwork(true)

            assertThat(viewModel.hideAlbumsWithoutArtwork.value).isFalse()
            assertThat(viewModel.notice.value).isEqualTo(FolderNotice.SaveFailed)
        }
}
