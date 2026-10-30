package io.sonora.feature.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.model.AccentMode
import io.sonora.core.domain.model.AccentPreset
import io.sonora.core.domain.model.AddFolderResult
import io.sonora.core.domain.model.AppLanguage
import io.sonora.core.domain.model.AppPreferences
import io.sonora.core.domain.model.AppearancePreferences
import io.sonora.core.domain.model.LibraryFolder
import io.sonora.core.domain.model.NowPlayingPreferences
import io.sonora.core.domain.model.NowPlayingRotation
import io.sonora.core.domain.model.ScanProgress
import io.sonora.core.domain.model.ThemeMode
import io.sonora.core.domain.port.VisualCollectionPreferencesRepository
import io.sonora.core.domain.port.LibraryFolderRepository
import io.sonora.core.domain.port.LibraryScanner
import io.sonora.core.domain.port.PreferencesRepository
import io.sonora.core.watchport.WatchSyncController
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val folders: List<LibraryFolder> = emptyList(),
    val progress: ScanProgress = ScanProgress.Idle,
)

/**
 * Library folders, scanning, language, appearance and the Now Playing buttons. Folder changes trigger a scan by
 * themselves (see [LibraryScanner]).
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val folderRepository: LibraryFolderRepository,
    private val scanner: LibraryScanner,
    private val preferences: PreferencesRepository,
    private val visualCollection: VisualCollectionPreferencesRepository,
    private val watch: WatchSyncController,
) : ViewModel() {

    /** Whether the Wear OS watch section shows (false in a build without the watch link). */
    val watchAvailable: Boolean get() = watch.isAvailable

    val state: StateFlow<SettingsUiState> = combine(folderRepository.folders(), scanner.progress, ::SettingsUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** The Now Playing switches; `null` until the stored values are read, so a switch never flips on its own. */
    val nowPlaying: StateFlow<NowPlayingPreferences?> = preferences.preferences
        .map { it.nowPlaying }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The appearance choices; `null` until the stored values are read. */
    val appearance: StateFlow<AppearancePreferences?> = preferences.preferences
        .map { it.appearance }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The app's language; `null` until the stored value is read. */
    val language: StateFlow<AppLanguage?> = preferences.preferences
        .map { it.language }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether visual collection hides albums without artwork; `null` until the stored value is read. */
    val hideAlbumsWithoutArtwork: StateFlow<Boolean?> = visualCollection.hideAlbumsWithoutArtwork
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether other apps and the adb shell may control Sonora (see `core:api`); `null` until the stored value is read. */
    val externalControl: StateFlow<Boolean?> = preferences.preferences
        .map { it.externalControlEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _notice = MutableStateFlow<FolderNotice?>(null)
    val notice: StateFlow<FolderNotice?> = _notice.asStateFlow()

    fun addFolder(treeUri: String) {
        viewModelScope.launch {
            _notice.value = try {
                when (val result = folderRepository.add(treeUri)) {
                    is AddFolderResult.Added -> null
                    is AddFolderResult.Rejected -> FolderNotice.Conflict(result.conflict)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                FolderNotice.AddFailed
            }
        }
    }

    fun removeFolder(folder: LibraryFolder) {
        viewModelScope.launch { folderRepository.remove(folder.treeUri) }
    }

    fun rescan() = scanner.requestScan()

    fun pickerMissing() {
        _notice.value = FolderNotice.PickerMissing
    }

    fun dismissNotice() {
        _notice.value = null
    }

    fun setShowLyricsButton(show: Boolean) = updateNowPlaying { it.copy(showLyricsButton = show) }

    fun setShowFileInfoButton(show: Boolean) = updateNowPlaying { it.copy(showFileInfoButton = show) }

    fun setNowPlayingRotation(rotation: NowPlayingRotation) = updateNowPlaying { it.copy(rotation = rotation) }

    /** Gravity mode: Now Playing stays upright and its groups hang level (see [NowPlayingPreferences.gravity]). */
    fun setGravity(enabled: Boolean) = updateNowPlaying { it.copy(gravity = enabled) }

    /** Visual collection lists only albums with artwork while on; the Albums list is unchanged. */
    fun setHideAlbumsWithoutArtwork(hide: Boolean) = save { visualCollection.setHideAlbumsWithoutArtwork(hide) }

    fun setTheme(theme: ThemeMode) = updateAppearance { it.copy(theme = theme) }

    fun setAccentMode(mode: AccentMode) = updateAppearance { it.copy(accentMode = mode) }

    fun setAccentPreset(preset: AccentPreset) = updateAppearance { it.copy(preset = preset) }

    fun setTranslucency(enabled: Boolean) = updateAppearance { it.copy(translucency = enabled) }

    /** Stores the language; the app applies it (recreating the screen) as soon as it is saved. */
    fun setLanguage(language: AppLanguage) = update { it.copy(language = language) }

    /** Turns External control on or off; while off, every external caller is denied. */
    fun setExternalControl(enabled: Boolean) = update { it.copy(externalControlEnabled = enabled) }

    private fun updateNowPlaying(change: (NowPlayingPreferences) -> NowPlayingPreferences) =
        update { it.copy(nowPlaying = change(it.nowPlaying)) }

    private fun updateAppearance(change: (AppearancePreferences) -> AppearancePreferences) =
        update { it.copy(appearance = change(it.appearance)) }

    /**
     * Saves a change. A failed save leaves the stored value, which the screen shows, untouched and explains itself;
     * cancellation is not an [IOException], so it still propagates.
     */
    private fun update(transform: (AppPreferences) -> AppPreferences) = save { preferences.update(transform) }

    /** Runs [write], explaining a failed save as [update] does. */
    private fun save(write: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                write()
            } catch (e: IOException) {
                Log.w(TAG, "Couldn't save a setting", e)
                _notice.value = FolderNotice.SaveFailed
            }
        }
    }

    private companion object {
        const val TAG = "SonoraSettings"
    }
}
