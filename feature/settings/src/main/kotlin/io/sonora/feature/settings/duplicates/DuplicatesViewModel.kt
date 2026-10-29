package io.sonora.feature.settings.duplicates

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.library.duplicates.DuplicateAlerts
import io.sonora.core.domain.library.duplicates.DuplicateDeletion
import io.sonora.core.domain.library.duplicates.DuplicateGroup
import io.sonora.core.domain.library.duplicates.DuplicateScan
import io.sonora.core.domain.library.duplicates.DuplicateScanState
import io.sonora.core.domain.library.removal.LibraryFileDeleter
import io.sonora.core.domain.port.WriteAccessRequest
import io.sonora.core.domain.port.WriteGrant
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A group opened for comparison, with the copies selected for deleting. */
internal data class OpenedGroup(val group: DuplicateGroup, val selected: Set<String>)

/** Where deleting copies is; each step is one dialog (none while [Deleting]). */
internal sealed interface CopiesDeletion {
    /** The irreversible confirmation. */
    data class Confirm(val group: DuplicateGroup, val selected: Set<String>) : CopiesDeletion

    /** A folder is read-only: pick it again to allow deleting; [wrongFolder] after another folder was picked. */
    data class NeedsAccess(val group: DuplicateGroup, val selected: Set<String>, val request: WriteAccessRequest, val wrongFolder: Boolean = false) :
        CopiesDeletion

    data object Deleting : CopiesDeletion
}

/** What a deletion ended with, shown once. */
internal sealed interface DuplicatesNotice {
    data class Deleted(val copies: Int) : DuplicatesNotice

    /** [failed] copies could not be deleted and are still there. */
    data class Incomplete(val failed: Int) : DuplicatesNotice

    /** The folder was picked again but its storage does not allow deleting. */
    data object StorageRefused : DuplicatesNotice

    data object Failed : DuplicatesNotice
}

/**
 * Settings › Duplicates: the shared [DuplicateScan]'s groups (scanned on first visit, again on request). A group opens
 * to its copies compared side by side, every copy but the suggested one selected; the last copy left can never be
 * selected. Deleting asks first, and a read-only folder is picked again to allow it, as the library's "Delete from
 * Phone" does. "Not a Duplicate" hides a group for good. The notification switch is the background check's.
 */
@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    private val scan: DuplicateScan,
    private val files: LibraryFileDeleter,
    private val alerts: DuplicateAlerts,
) : ViewModel() {

    internal val state: StateFlow<DuplicateScanState> = scan.state

    internal val notifications: StateFlow<Boolean> = alerts.enabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val openedKey = MutableStateFlow<String?>(null)
    private val selection = MutableStateFlow<Set<String>>(emptySet())

    /** The group opened, while it is still listed. */
    internal val opened: StateFlow<OpenedGroup?> = combine(scan.state, openedKey, selection) { state, key, selected ->
        (state as? DuplicateScanState.Found)?.groups?.firstOrNull { it.key == key }?.let { OpenedGroup(it, selected) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _deletion = MutableStateFlow<CopiesDeletion?>(null)
    internal val deletion: StateFlow<CopiesDeletion?> = _deletion.asStateFlow()

    private val _notice = MutableStateFlow<DuplicatesNotice?>(null)
    internal val notice: StateFlow<DuplicatesNotice?> = _notice.asStateFlow()

    init {
        scan.scanIfNeeded()
    }

    internal fun scanAgain() = scan.scan()

    /** The stored switch (the [notifications] state starts on until it is read). */
    internal suspend fun notificationsWanted(): Boolean = try {
        alerts.enabled.first()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    internal fun setNotifications(on: Boolean) {
        viewModelScope.launch {
            try {
                alerts.setEnabled(on)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot store the duplicates notification switch", e)
            }
        }
    }

    internal fun open(group: DuplicateGroup) {
        selection.value = group.copies.drop(1).mapTo(LinkedHashSet()) { it.uri }
        openedKey.value = group.key
    }

    internal fun close() {
        openedKey.value = null
    }

    /** Selects or unselects a copy for deleting; the last copy left unselected stays so. */
    internal fun toggle(uri: String) {
        val group = opened.value?.group ?: return
        val selected = selection.value
        selection.value = when {
            uri in selected -> selected - uri
            selected.size + 1 < group.copies.size -> selected + uri
            else -> selected
        }
    }

    /** Delete on the opened group's selection: asks first. */
    internal fun chooseDelete() {
        val shown = opened.value ?: return
        if (shown.selected.isNotEmpty()) _deletion.value = CopiesDeletion.Confirm(shown.group, shown.selected)
    }

    internal fun confirmDelete() {
        val confirm = _deletion.value as? CopiesDeletion.Confirm ?: return
        delete(confirm.group, confirm.selected)
    }

    internal fun cancelDelete() {
        _deletion.value = null
    }

    /** The folder picked to allow deleting: kept with write access when it is the one asked for, then deleting goes on. */
    internal fun accessPicked(treeUri: String) {
        val step = _deletion.value as? CopiesDeletion.NeedsAccess ?: return
        _deletion.value = CopiesDeletion.Deleting
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
                WriteGrant.GRANTED -> delete(step.group, step.selected)
                WriteGrant.WRONG_FOLDER -> _deletion.value = step.copy(wrongFolder = true)
                WriteGrant.REFUSED -> {
                    _deletion.value = null
                    _notice.value = DuplicatesNotice.StorageRefused
                }
            }
        }
    }

    /** "Not a Duplicate" on the opened group: hidden for good. */
    internal fun notADuplicate() {
        val group = opened.value?.group ?: return
        openedKey.value = null
        viewModelScope.launch {
            try {
                scan.dismiss(group)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot dismiss a duplicate group", e)
                _notice.value = DuplicatesNotice.Failed
            }
        }
    }

    internal fun dismissNotice() {
        _notice.value = null
    }

    private fun delete(group: DuplicateGroup, selected: Set<String>) {
        _deletion.value = CopiesDeletion.Deleting
        viewModelScope.launch {
            val result = try {
                // Files go one by one and playlists move with them: closing the page must not stop that halfway.
                withContext(NonCancellable) { scan.delete(group, selected) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot delete duplicate copies", e)
                null
            }
            _deletion.value = (result as? DuplicateDeletion.NeedsWriteAccess)?.let { CopiesDeletion.NeedsAccess(group, selected, it.request) }
            when (result) {
                // Nothing selected or nothing kept cannot come from a confirmed dialog (it holds a non-empty selection
                // that keeps a copy), but a confirmed deletion that did nothing must still say so.
                null, DuplicateDeletion.NothingSelected, DuplicateDeletion.KeepsNoCopy -> _notice.value = DuplicatesNotice.Failed
                is DuplicateDeletion.NeedsWriteAccess -> Unit
                is DuplicateDeletion.Deleted -> {
                    if (openedKey.value == group.key) openedKey.value = null
                    _notice.value = if (result.failed > 0) DuplicatesNotice.Incomplete(result.failed) else DuplicatesNotice.Deleted(result.deleted)
                }
            }
        }
    }

    private companion object {
        const val TAG = "SonoraDuplicates"
    }
}
