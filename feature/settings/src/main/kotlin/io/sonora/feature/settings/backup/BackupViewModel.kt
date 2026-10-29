package io.sonora.feature.settings.backup

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.backup.ApplyRestore
import io.sonora.core.domain.backup.BackupError
import io.sonora.core.domain.backup.CreateBackup
import io.sonora.core.domain.backup.ReadBackup
import io.sonora.core.domain.backup.RestorePlan
import io.sonora.core.domain.backup.RestoreReading
import io.sonora.core.domain.backup.RestoreReport
import io.sonora.core.domain.port.BackupHistory
import io.sonora.feature.settings.ImportJobScope
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A one-off notice of Settings › Backup & Restore. */
enum class BackupMessage { SAVED, WRITE_FAILED, UNREADABLE, OTHER_FORMAT, NEWER_VERSION, MALFORMED, RESTORE_FAILED, NO_PICKER }

/**
 * Settings › Backup & Restore. [busy] while a backup, a read or a restore runs; [preview] holds a read backup until
 * the user restores or cancels it; [report] is what the last restore did.
 */
data class BackupUiState(
    val busy: Boolean = false,
    val preview: RestorePlan? = null,
    val report: RestoreReport? = null,
    val message: BackupMessage? = null,
)

/**
 * Settings › Backup & Restore. Writing a backup and applying a restore run in [jobs], which outlive the screen, so
 * leaving it never stops a restore halfway.
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val createBackup: CreateBackup,
    private val readBackup: ReadBackup,
    private val applyRestore: ApplyRestore,
    private val jobs: ImportJobScope,
    history: BackupHistory,
) : ViewModel() {

    /** When the last backup was made, epoch milliseconds; `null` before the first. */
    val lastBackupMs: StateFlow<Long?> = history.lastBackupMs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    /** Writes a backup to the document the user created at [uri]. */
    fun backUp(uri: String) {
        if (!start()) return
        jobs.launch {
            val saved = guarded(otherwise = false) { createBackup(uri) }
            _state.value = BackupUiState(message = if (saved) BackupMessage.SAVED else BackupMessage.WRITE_FAILED)
        }
    }

    /** Reads the backup the user picked at [uri] and previews its restore. */
    fun read(uri: String) {
        if (!start()) return
        viewModelScope.launch {
            val reading = guarded<RestoreReading>(otherwise = RestoreReading.Unreadable) { readBackup(uri) }
            _state.value = when (reading) {
                is RestoreReading.Ready -> BackupUiState(preview = reading.plan)
                is RestoreReading.Rejected -> BackupUiState(message = reading.error.message())
                RestoreReading.Unreadable -> BackupUiState(message = BackupMessage.UNREADABLE)
            }
        }
    }

    /** Applies the previewed restore. */
    fun confirmRestore() {
        val plan = _state.value.preview ?: return
        if (_state.value.busy) return
        _state.value = BackupUiState(busy = true)
        jobs.launch {
            val report = guarded(otherwise = null) { applyRestore(plan) }
            _state.value = if (report != null) BackupUiState(report = report) else BackupUiState(message = BackupMessage.RESTORE_FAILED)
        }
    }

    fun cancelRestore() {
        _state.update { if (it.busy) it else it.copy(preview = null) }
    }

    /** The system has no app to pick or create a document. */
    fun pickerMissing() {
        _state.update { if (it.busy) it else BackupUiState(message = BackupMessage.NO_PICKER) }
    }

    /** Closes the notice or the restore report. */
    fun dismiss() {
        _state.update { if (it.busy) it else BackupUiState() }
    }

    /** Marks the screen busy; `false` when something already runs. */
    private fun start(): Boolean {
        if (_state.value.busy) return false
        _state.value = BackupUiState(busy = true)
        return true
    }

    /** [block]'s result; [otherwise] when it fails (a database or provider error the use case did not expect). */
    private suspend fun <T> guarded(otherwise: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Backup or restore failed", e)
        otherwise
    }

    private fun BackupError.message(): BackupMessage = when (this) {
        is BackupError.OtherFormat -> BackupMessage.OTHER_FORMAT
        is BackupError.NewerVersion -> BackupMessage.NEWER_VERSION
        is BackupError.Malformed -> BackupMessage.MALFORMED
    }

    private companion object {
        const val TAG = "SonoraBackup"
    }
}
