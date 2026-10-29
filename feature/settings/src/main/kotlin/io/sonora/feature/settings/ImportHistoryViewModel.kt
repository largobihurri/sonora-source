package io.sonora.feature.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.archive.ArchiveImportEntry
import io.sonora.core.domain.archive.ForgetArchiveImport
import io.sonora.core.domain.archive.ImportDeleter
import io.sonora.core.domain.archive.ImportDeletion
import io.sonora.core.domain.archive.ImportedSong
import io.sonora.core.domain.library.removal.LibraryFileDeleter
import io.sonora.core.domain.port.ArchiveImportRepository
import io.sonora.core.domain.port.WriteAccessRequest
import io.sonora.core.domain.port.WriteGrant
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** An import of the history opened to its songs. */
internal data class OpenedImport(val entry: ArchiveImportEntry, val songs: List<ImportedSong>)

/** Where "Delete from Phone" is for an import; each step is one dialog (none while [Deleting]). */
internal sealed interface HistoryDeletion {
    val entry: ArchiveImportEntry

    /** The irreversible confirmation. */
    data class Confirm(override val entry: ArchiveImportEntry) : HistoryDeletion

    /** A folder is read-only: pick it again to allow deleting; [wrongFolder] after another folder was picked. */
    data class NeedsAccess(
        override val entry: ArchiveImportEntry,
        val request: WriteAccessRequest,
        val wrongFolder: Boolean = false,
    ) : HistoryDeletion

    data class Deleting(override val entry: ArchiveImportEntry) : HistoryDeletion
}

/** What a deletion ended with, shown once. */
internal sealed interface HistoryNotice {
    /** The import is gone from the phone, [songs] of its songs from the library. */
    data class Deleted(val songs: Int) : HistoryNotice

    /** [deleted] songs went, [failed] are still there (with none failed, its folder is): it stays in the history. */
    data class Incomplete(val deleted: Int, val failed: Int) : HistoryNotice

    /** The folder was picked again but its storage does not allow deleting. */
    data object StorageRefused : HistoryNotice

    data object Failed : HistoryNotice
}

/**
 * Settings › Library Folders › Import History: every archive import, newest first; one opens to its songs and can be
 * deleted from the phone (its folder and files, its songs everywhere in the library, its playlist; asked first, and a
 * read-only folder is picked again to allow it, as in the library) or removed from the history only.
 */
@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
@HiltViewModel
class ImportHistoryViewModel @Inject constructor(
    private val history: ArchiveImportRepository,
    private val deleter: ImportDeleter,
    private val forget: ForgetArchiveImport,
    private val files: LibraryFileDeleter,
) : ViewModel() {

    internal val imports: StateFlow<List<ArchiveImportEntry>> = history.imports()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val selected = MutableStateFlow<Long?>(null)

    /** The import opened, while it is still in the history. */
    internal val opened: StateFlow<OpenedImport?> = selected
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(null)
            } else {
                combine(history.import(id), history.songs(id)) { entry, songs -> entry?.let { OpenedImport(it, songs) } }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _deletion = MutableStateFlow<HistoryDeletion?>(null)
    internal val deletion: StateFlow<HistoryDeletion?> = _deletion.asStateFlow()

    private val _notice = MutableStateFlow<HistoryNotice?>(null)
    internal val notice: StateFlow<HistoryNotice?> = _notice.asStateFlow()

    internal fun open(importId: Long) {
        selected.value = importId
    }

    internal fun close() {
        selected.value = null
    }

    /** Delete from Phone on the opened import: asks first. */
    internal fun chooseDelete() {
        val entry = opened.value?.entry ?: return
        _deletion.value = HistoryDeletion.Confirm(entry)
    }

    internal fun confirmDelete() {
        val confirm = _deletion.value as? HistoryDeletion.Confirm ?: return
        delete(confirm.entry)
    }

    internal fun cancelDelete() {
        _deletion.value = null
    }

    /** The folder picked to allow deleting: kept with write access when it is the one asked for, then deleting goes on. */
    internal fun accessPicked(treeUri: String) {
        val step = _deletion.value as? HistoryDeletion.NeedsAccess ?: return
        _deletion.value = HistoryDeletion.Deleting(step.entry)
        viewModelScope.launch {
            val grant = try {
                files.grantWriteAccess(step.request, treeUri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot keep write access to the folder", e)
                WriteGrant.REFUSED
            }
            when (grant) {
                WriteGrant.GRANTED -> delete(step.entry)
                WriteGrant.WRONG_FOLDER -> _deletion.value = step.copy(wrongFolder = true)
                WriteGrant.REFUSED -> {
                    _deletion.value = null
                    _notice.value = HistoryNotice.StorageRefused
                }
            }
        }
    }

    /** Remove from History on the opened import: forgets its record; its folder, songs and playlist stay. */
    internal fun removeFromHistory() {
        val entry = opened.value?.entry ?: return
        selected.value = null
        viewModelScope.launch {
            try {
                forget(entry.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot remove an import from the history", e)
                _notice.value = HistoryNotice.Failed
            }
        }
    }

    internal fun dismissNotice() {
        _notice.value = null
    }

    private fun delete(entry: ArchiveImportEntry) {
        _deletion.value = HistoryDeletion.Deleting(entry)
        viewModelScope.launch {
            val result = try {
                deleter.delete(entry.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot delete an import from the phone", e)
                null
            }
            _deletion.value = (result as? ImportDeletion.NeedsWriteAccess)?.let { HistoryDeletion.NeedsAccess(entry, it.request) }
            when (result) {
                null -> _notice.value = HistoryNotice.Failed
                is ImportDeletion.NeedsWriteAccess -> Unit
                is ImportDeletion.Deleted -> {
                    if (selected.value == entry.id) selected.value = null
                    _notice.value = HistoryNotice.Deleted(result.songs)
                }
                is ImportDeletion.Incomplete -> _notice.value = HistoryNotice.Incomplete(result.deleted, result.failed)
                ImportDeletion.Gone -> if (selected.value == entry.id) selected.value = null
            }
        }
    }

    private companion object {
        const val TAG = "SonoraImportHistory"
    }
}
