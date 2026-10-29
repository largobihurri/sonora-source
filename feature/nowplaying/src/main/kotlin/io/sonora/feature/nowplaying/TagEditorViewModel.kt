package io.sonora.feature.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.PictureLoader
import io.sonora.core.domain.port.QueuedTrackUpdater
import io.sonora.core.domain.port.SongTagEditor
import io.sonora.core.domain.port.TagEditFailure
import io.sonora.core.domain.port.TagReadOutcome
import io.sonora.core.domain.port.WriteAccessRequest
import io.sonora.core.domain.port.WriteGrant
import io.sonora.core.domain.tags.ArtworkChange
import io.sonora.core.domain.tags.EditableTags
import io.sonora.core.domain.tags.SaveSongTags
import io.sonora.core.domain.tags.SongTags
import io.sonora.core.domain.tags.TagError
import io.sonora.core.domain.tags.TagField
import io.sonora.core.domain.tags.TagSaveResult
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Something the edit form reports above its fields. */
sealed interface EditProblem {
    /** The song's folder is held read-only: pick it again to allow changes. */
    data class NeedsAccess(val request: WriteAccessRequest) : EditProblem

    /** The folder picked to allow changes was another one. */
    data class WrongFolder(val request: WriteAccessRequest) : EditProblem

    /** The folder was picked again but its storage refuses writes. */
    data object AccessRefused : EditProblem

    data class SaveFailed(val failure: TagEditFailure) : EditProblem

    /** The picked image could not be read or decoded. */
    data object PictureUnusable : EditProblem
}

/** The tag edit sheet. */
sealed interface TagEditorState {
    data object Closed : TagEditorState

    data object Loading : TagEditorState

    /** The file's tags cannot be edited ([TagEditFailure.UNSUPPORTED_FORMAT], [TagEditFailure.UNREADABLE]). */
    data class Unavailable(val failure: TagEditFailure) : TagEditorState

    data class Editing(
        val track: Track,
        val original: SongTags,
        val form: EditableTags,
        val saving: Boolean = false,
        val loadingPicture: Boolean = false,
        val problem: EditProblem? = null,
    ) : TagEditorState {
        /** Problems with the changed fields, shown as the user types. */
        val errors: Map<TagField, TagError> = form.errors(original)

        /** Whether the form differs from the file, so closing it would drop changes. */
        val dirty: Boolean get() = !form.changesFrom(original).isEmpty

        val canSave: Boolean get() = dirty && errors.isEmpty() && !saving && !loadingPicture
    }

    /** Saved; [track] has the new details. The screen refreshes File Info and closes the sheet. */
    data class Saved(val track: Track) : TagEditorState
}

/** The tag edit form opened from File Info: reads the file's tags, edits them and saves through [SaveSongTags]. */
@HiltViewModel
class TagEditorViewModel @Inject constructor(
    private val editor: SongTagEditor,
    private val pictures: PictureLoader,
    queue: QueuedTrackUpdater,
) : ViewModel() {

    private val saveTags = SaveSongTags(editor, queue)
    private val _state = MutableStateFlow<TagEditorState>(TagEditorState.Closed)
    val state: StateFlow<TagEditorState> = _state.asStateFlow()
    private var job: Job? = null

    /**
     * Counts the editor's openings and closings. Work started for one opening (a save, a grant, a picture) checks it
     * before landing, so after close-then-open on another song its result never reaches the new form.
     */
    private var session = 0

    fun open(track: Track) {
        job?.cancel()
        session++
        _state.value = TagEditorState.Loading
        job = viewModelScope.launch {
            _state.value = when (val outcome = editor.read(track)) {
                is TagReadOutcome.Read -> TagEditorState.Editing(track, outcome.tags, EditableTags.of(outcome.tags))
                is TagReadOutcome.Failed -> TagEditorState.Unavailable(outcome.failure)
            }
        }
    }

    fun change(field: TagField, value: String) = updateForm { it.with(field, value) }

    /** Replaces the artwork with the image at [uri] (from the photo picker). */
    fun pickPicture(uri: String) {
        val editing = _state.value as? TagEditorState.Editing ?: return
        _state.value = editing.copy(loadingPicture = true, problem = null)
        val started = session
        viewModelScope.launch {
            val picture = pictures.load(uri)
            val current = editingIn(started) ?: return@launch
            _state.value = if (picture == null) {
                current.copy(loadingPicture = false, problem = EditProblem.PictureUnusable)
            } else {
                current.copy(loadingPicture = false, form = current.form.copy(artwork = ArtworkChange.Replace(picture)))
            }
        }
    }

    fun removePicture() = updateForm { it.copy(artwork = ArtworkChange.Remove) }

    fun save() {
        val editing = _state.value as? TagEditorState.Editing ?: return
        if (!editing.canSave) return
        _state.value = editing.copy(saving = true, problem = null)
        val started = session
        job = viewModelScope.launch {
            val result = saveTags(editing.track, editing.original, editing.form)
            val current = editingIn(started) ?: return@launch
            _state.value = when (result) {
                is TagSaveResult.Saved -> TagEditorState.Saved(result.track)
                is TagSaveResult.Invalid -> current.copy(saving = false)
                is TagSaveResult.NeedsWriteAccess -> current.copy(saving = false, problem = EditProblem.NeedsAccess(result.request))
                is TagSaveResult.Failed -> current.copy(saving = false, problem = EditProblem.SaveFailed(result.failure))
            }
        }
    }

    /**
     * The user picked [treeUri] to allow changes; on the right folder the save is tried again, with the form as it is
     * once the grant is done (it stays editable meanwhile).
     */
    fun accessPicked(treeUri: String) {
        val editing = _state.value as? TagEditorState.Editing ?: return
        val request = when (val problem = editing.problem) {
            is EditProblem.NeedsAccess -> problem.request
            is EditProblem.WrongFolder -> problem.request
            else -> return
        }
        val started = session
        viewModelScope.launch {
            val grant = editor.grantWriteAccess(request, treeUri)
            val current = editingIn(started) ?: return@launch
            when (grant) {
                WriteGrant.GRANTED -> {
                    _state.value = current.copy(problem = null)
                    save()
                }
                WriteGrant.WRONG_FOLDER -> _state.value = current.copy(problem = EditProblem.WrongFolder(request))
                WriteGrant.REFUSED -> _state.value = current.copy(problem = EditProblem.AccessRefused)
            }
        }
    }

    fun dismissProblem() {
        val editing = _state.value as? TagEditorState.Editing ?: return
        _state.value = editing.copy(problem = null)
    }

    /**
     * Closes the sheet; a save under way still finishes (the file is never left half-written), but its result is
     * dropped.
     */
    fun close() {
        if ((_state.value as? TagEditorState.Editing)?.saving != true) job?.cancel()
        job = null
        session++
        _state.value = TagEditorState.Closed
    }

    /** The form, while the editor is still in the opening [started] belongs to. */
    private fun editingIn(started: Int): TagEditorState.Editing? =
        (_state.value as? TagEditorState.Editing)?.takeIf { started == session }

    private fun updateForm(transform: (EditableTags) -> EditableTags) {
        val editing = _state.value as? TagEditorState.Editing ?: return
        if (editing.saving) return
        _state.value = editing.copy(form = transform(editing.form))
    }
}
