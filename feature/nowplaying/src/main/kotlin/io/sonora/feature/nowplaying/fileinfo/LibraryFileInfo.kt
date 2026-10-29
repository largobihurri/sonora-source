package io.sonora.feature.nowplaying.fileinfo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentCompositeKeyHashCode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.TrackFileInfoReader
import io.sonora.feature.nowplaying.EditTagsSheet
import io.sonora.feature.nowplaying.FileInfoPanel
import io.sonora.feature.nowplaying.FileInfoSheet
import io.sonora.feature.nowplaying.TagEditorState
import io.sonora.feature.nowplaying.TagEditorViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** File Info for a song picked in the library, which need not be playing (so there is no decoded format). */
@HiltViewModel
class LibraryFileInfoViewModel @Inject constructor(private val reader: TrackFileInfoReader) : ViewModel() {

    private val _sheet = MutableStateFlow<FileInfoSheet>(FileInfoSheet.Hidden)
    val sheet: StateFlow<FileInfoSheet> = _sheet.asStateFlow()
    private var job: Job? = null

    /** Reads [track]'s file and shows its details; again after a tag edit, with the saved details. */
    fun show(track: Track) {
        job?.cancel()
        _sheet.value = FileInfoSheet.Loading
        job = viewModelScope.launch {
            _sheet.value = try {
                FileInfoSheet.Loaded(reader.read(track), decoded = null, track = track)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                FileInfoSheet.Unreadable
            }
        }
    }

    fun close() {
        job?.cancel()
        job = null
        _sheet.value = FileInfoSheet.Hidden
    }
}

/**
 * The view-model keys of one [LibraryFileInfo] call site. Its callers (the Library and Search tabs) share one
 * ViewModelStoreOwner, so fixed keys would give them one panel and one editor between them: a song shown in one tab
 * would show in the other, and closing either would close both.
 */
internal data class LibraryFileInfoKeys(val fileInfo: String, val tagEditor: String) {
    companion object {
        /** The keys for the call site whose composite key hash is [callSite]. */
        fun at(callSite: Long): LibraryFileInfoKeys {
            val site = callSite.toString(Character.MAX_RADIX)
            return LibraryFileInfoKeys("library-file-info-$site", "library-tag-editor-$site")
        }
    }
}

/**
 * "View Credits and Metadata" from a library context menu: the File Info panel for [track] with its Edit action (the
 * same tag editor as Now Playing). Closing it calls [onClose].
 */
@Composable
fun LibraryFileInfo(track: Track, onClose: () -> Unit) {
    // The composite key hash tells the call sites apart and stays the same across recreation (as saved state does).
    val keys = LibraryFileInfoKeys.at(currentCompositeKeyHashCode)
    val viewModel: LibraryFileInfoViewModel = hiltViewModel(key = keys.fileInfo)
    val tagEditor: TagEditorViewModel = hiltViewModel(key = keys.tagEditor)
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    val tagEdit by tagEditor.state.collectAsStateWithLifecycle()
    val latestClose by rememberUpdatedState(onClose)
    LaunchedEffect(track) { viewModel.show(track) }
    // Edit replaces File Info while it is open; File Info comes back afterwards, read again after a save.
    if (tagEdit != TagEditorState.Closed) {
        EditTagsSheet(tagEdit, tagEditor)
    } else if (sheet != FileInfoSheet.Hidden) {
        FileInfoPanel(sheet, track, onDismiss = { latestClose() }, onEdit = tagEditor::open)
    }
    LaunchedEffect(tagEdit) {
        val saved = tagEdit as? TagEditorState.Saved ?: return@LaunchedEffect
        viewModel.show(saved.track)
        tagEditor.close()
    }
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.close()
            tagEditor.close()
        }
    }
}
