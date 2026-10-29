package io.sonora.core.domain.library.duplicates

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the duplicate scan is. */
sealed interface DuplicateScanState {
    data object NotScanned : DuplicateScanState

    /** [checked] of the [total] files not checked yet are done ([total] 0: nothing new to check). */
    data class Scanning(val checked: Int, val total: Int) : DuplicateScanState

    data class Found(val groups: List<DuplicateGroup>) : DuplicateScanState

    data object Failed : DuplicateScanState
}

/**
 * The app's duplicate scan and its last result, shared by the Duplicates screen, the in-app notice and the background
 * checks. One scan runs at a time: [scan] starts one in [scope] (the process) unless one runs, [scanNow] runs one in
 * the caller (a background job, stopped with it) after any running one. A stopped scan leaves the previous result.
 * Dismissing a group or deleting copies updates the result at once, without a new scan.
 */
class DuplicateScan(
    private val find: FindDuplicates,
    private val library: DuplicateLibrary,
    private val dismissals: DuplicateDismissals,
    private val deleteCopies: DeleteDuplicateCopies,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<DuplicateScanState>(DuplicateScanState.NotScanned)
    val state: StateFlow<DuplicateScanState> = _state.asStateFlow()

    private val _announced = MutableStateFlow(false)

    /**
     * The current or last scan is the first analysis, to be announced in the app until the notice is closed. A failed
     * scan ends it (the notice has nothing to show, and no later scan may take its place).
     */
    val announced: StateFlow<Boolean> = _announced.asStateFlow()

    private val running = Mutex()
    private var job: Job? = null

    /** Starts a scan unless one runs; [announce] marks it as the first analysis. */
    fun scan(announce: Boolean = false) {
        synchronized(this) {
            if (announce) _announced.value = true
            if (job?.isActive == true) return
            job = scope.launch { scanNow() }
        }
    }

    /** Starts a scan unless there is a result or one runs. */
    fun scanIfNeeded() {
        val now = _state.value
        if (now is DuplicateScanState.NotScanned || now is DuplicateScanState.Failed) scan()
    }

    /** Scans now, after any running scan; the groups, or `null` when the scan failed. */
    suspend fun scanNow(): List<DuplicateGroup>? = running.withLock {
        val before = _state.value
        _state.value = DuplicateScanState.Scanning(0, 0)
        try {
            find { checked, total -> _state.value = DuplicateScanState.Scanning(checked, total) }
                .also { _state.value = DuplicateScanState.Found(it) }
        } catch (e: CancellationException) {
            _state.value = before
            throw e
        } catch (_: Exception) {
            _state.value = DuplicateScanState.Failed
            _announced.value = false
            null
        }
    }

    /** Hides the first analysis' notice; the scan goes on. */
    fun closeAnnouncement() {
        _announced.value = false
    }

    /** "Not a duplicate": [group] leaves the list for good. */
    suspend fun dismiss(group: DuplicateGroup) {
        dismissals.dismiss(group.copies.mapTo(LinkedHashSet()) { it.uri })
        edit { groups -> groups.filterNot { it.key == group.key } }
    }

    /** Deletes the [selected] copies of [group] ([DeleteDuplicateCopies]); the group then shows the copies left. */
    suspend fun delete(group: DuplicateGroup, selected: Set<String>): DuplicateDeletion {
        val result = deleteCopies(group, selected)
        if (result is DuplicateDeletion.Deleted && result.deleted > 0) {
            val present = library.copies().mapTo(HashSet()) { it.uri }
            edit { groups ->
                groups.mapNotNull { shown ->
                    if (shown.key != group.key) shown else shown.copies.filter { it.uri in present }.takeIf { it.size > 1 }?.let(::DuplicateGroup)
                }
            }
        }
        return result
    }

    private fun edit(change: (List<DuplicateGroup>) -> List<DuplicateGroup>) = _state.update { now ->
        if (now is DuplicateScanState.Found) DuplicateScanState.Found(change(now.groups)) else now
    }
}
