package io.sonora.feature.library

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.archive.ArchiveFailure
import io.sonora.core.domain.archive.ArchiveFiles
import io.sonora.core.domain.archive.ArchiveJobScheduler
import io.sonora.core.domain.archive.ArchiveJobState
import io.sonora.core.domain.archive.ArchiveKind
import io.sonora.core.domain.archive.ArchiveProgress
import io.sonora.core.domain.archive.CompressedArchive
import io.sonora.core.domain.archive.CompressionRequest
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.FolderBrowser
import io.sonora.core.domain.port.LibraryRepository
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** What "Compress" packs: [source]'s songs, in order, as a [kind] archive called [name]. */
internal data class CompressTarget(val kind: ArchiveKind, val name: String, val source: PlaylistSource)

/** Where one "Compress" is; each step is one sheet (or none while [Working.hidden]). */
internal sealed interface CompressStep {
    /** Choosing a password (or none) for [tracks]. */
    data class Password(val target: CompressTarget, val tracks: List<Track>) : CompressStep

    /** Job [jobId] packs [name]; [hidden] once the user closed the sheet (the notification keeps the progress). */
    data class Working(val jobId: String, val name: String, val progress: ArchiveProgress?, val hidden: Boolean = false) :
        CompressStep

    /** The archive is in the app's cache: share it or save it. */
    data class Finished(val archive: CompressedArchive) : CompressStep

    data class Failed(val failure: ArchiveFailure) : CompressStep
}

/** One-off outcomes the screen shows as a banner. */
internal sealed interface CompressEvent {
    data object NoSongs : CompressEvent

    /** The target's songs could not be read; nothing was scheduled. */
    data object ReadFailed : CompressEvent

    data object Saved : CompressEvent

    data object SaveFailed : CompressEvent
}

/**
 * "Compress" from the song, album and playlist menus (and the selection bar): asks for an optional password, runs
 * the job in the background ([ArchiveJobScheduler], progress in a notification too), then offers Share and Save.
 * The password only passes through here: the scheduler takes the array over and keeps it in memory.
 */
@HiltViewModel
class CompressViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val folders: FolderBrowser,
    private val scheduler: ArchiveJobScheduler,
    private val files: ArchiveFiles,
) : ViewModel() {

    private val _step = MutableStateFlow<CompressStep?>(null)
    internal val step: StateFlow<CompressStep?> = _step.asStateFlow()

    private val _events = Channel<CompressEvent>(Channel.BUFFERED)
    internal val events: Flow<CompressEvent> = _events.receiveAsFlow()

    /** Each scheduled job, followed to its end even while another sheet is shown. */
    private val following = mutableMapOf<String, Job>()

    /** Results of jobs that ended while another sheet was shown, in the order they ended; shown one by one. */
    private val waiting = ArrayDeque<CompressStep>()

    /** True from Compress until the job is scheduled, so a double tap schedules one job. */
    private var scheduling = false

    /**
     * Reads [target]'s songs and asks for a password; says so when there are none or they cannot be read. A job
     * already running (its sheet hidden) goes on, and its result shows once the sheets in front of it close.
     */
    internal fun start(target: CompressTarget) {
        viewModelScope.launch {
            val tracks = try {
                target.source.tracks(library, folders)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read the songs to compress", e)
                _events.send(CompressEvent.ReadFailed)
                return@launch
            }
            if (tracks.isEmpty()) _events.send(CompressEvent.NoSongs) else _step.value = CompressStep.Password(target, tracks)
        }
    }

    /**
     * Starts the job with [password] (empty or `null`: none); the scheduler takes the array over. A second tap
     * while the first is being scheduled is ignored, and its password wiped.
     */
    internal fun compress(password: CharArray?) {
        val step = _step.value as? CompressStep.Password
        if (step == null || scheduling) {
            password?.fill('\u0000')
            return
        }
        scheduling = true
        val target = step.target
        viewModelScope.launch {
            val jobId = try {
                scheduler.compress(CompressionRequest(target.kind, target.name, step.tracks), password?.takeIf { it.isNotEmpty() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot schedule a compression", e)
                _step.value = CompressStep.Failed(ArchiveFailure.Unexpected)
                return@launch
            } finally {
                scheduling = false
            }
            _step.value = CompressStep.Working(jobId, target.name, progress = null)
            follow(jobId)
        }
    }

    /** Closes the progress sheet; the job goes on and its result shows when it ends. */
    internal fun hide() {
        _step.value = (_step.value as? CompressStep.Working)?.copy(hidden = true) ?: return
    }

    /** Stops the running job (its partial archive is removed) and closes the sheet. */
    internal fun stop() {
        val working = _step.value as? CompressStep.Working ?: return
        following.remove(working.jobId)?.cancel()
        showNext()
        viewModelScope.launch { scheduler.cancel(working.jobId) }
    }

    /** Closes the password, finished or failed sheet; a result that waited behind it shows next. */
    internal fun dismiss() {
        if (_step.value !is CompressStep.Working) showNext()
    }

    /** The finished archive's address for the share sheet; `null` without one. */
    internal fun shareUri(): String? {
        val archive = (_step.value as? CompressStep.Finished)?.archive ?: return null
        return try {
            files.shareUri(archive)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Cannot share the archive", e)
            null
        }
    }

    /** Copies the finished archive into [documentUri], picked with the system "create document" picker. */
    internal fun save(documentUri: String) {
        val archive = (_step.value as? CompressStep.Finished)?.archive ?: return
        viewModelScope.launch {
            _events.send(if (files.saveTo(archive, documentUri)) CompressEvent.Saved else CompressEvent.SaveFailed)
        }
    }

    /**
     * Follows job [jobId] to its end. Its progress shows while its sheet (shown or hidden) is the current one; its
     * result replaces that sheet, or waits for the sheets in front of it to close. A job no longer known (lost from
     * WorkManager) ends as unexpected rather than leaving a sheet that never ends.
     */
    private fun follow(jobId: String) {
        following[jobId] = viewModelScope.launch {
            val end = scheduler.state(jobId).first { state ->
                if (state is ArchiveJobState.Running) {
                    (_step.value as? CompressStep.Working)?.takeIf { it.jobId == jobId }?.let {
                        _step.value = it.copy(progress = state.progress)
                    }
                }
                state.isEnd()
            }
            following.remove(jobId)
            val result = when (end) {
                is ArchiveJobState.Compressed -> CompressStep.Finished(end.archive)
                is ArchiveJobState.Failed -> CompressStep.Failed(end.failure)
                ArchiveJobState.Unknown -> CompressStep.Failed(ArchiveFailure.Unexpected)
                else -> null // cancelled
            }
            val own = (_step.value as? CompressStep.Working)?.jobId == jobId
            when {
                own -> if (result != null) _step.value = result else showNext()
                result != null && _step.value == null -> _step.value = result
                result != null -> waiting.addLast(result)
            }
        }
    }

    private fun ArchiveJobState.isEnd(): Boolean = when (this) {
        is ArchiveJobState.Compressed, is ArchiveJobState.Failed, ArchiveJobState.Cancelled, ArchiveJobState.Unknown -> true
        // Queued right after scheduling; an extraction never comes from a compression.
        ArchiveJobState.Queued, is ArchiveJobState.Running, is ArchiveJobState.Extracted -> false
    }

    /** Closes the current sheet, showing the next result that waited behind it, if any. */
    private fun showNext() {
        _step.value = waiting.removeFirstOrNull()
    }

    private companion object {
        const val TAG = "SonoraCompress"
    }
}
