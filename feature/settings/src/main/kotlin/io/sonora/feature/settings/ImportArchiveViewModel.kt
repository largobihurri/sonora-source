package io.sonora.feature.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.archive.ArchiveFailure
import io.sonora.core.domain.archive.ArchiveImportRules
import io.sonora.core.domain.archive.ArchiveImporter
import io.sonora.core.domain.archive.ArchiveJobScheduler
import io.sonora.core.domain.archive.ArchiveJobState
import io.sonora.core.domain.archive.ArchiveOutcome
import io.sonora.core.domain.archive.ArchiveProgress
import io.sonora.core.domain.archive.ArchiveSummary
import io.sonora.core.domain.archive.DestinationCoverage
import io.sonora.core.domain.archive.ImportFinisher
import io.sonora.core.domain.archive.ImportOutcome
import io.sonora.core.domain.archive.ImportRecorder
import io.sonora.core.domain.archive.ImportedSong
import io.sonora.core.domain.archive.RecordedImport
import io.sonora.core.domain.library.LibraryFolderRules
import io.sonora.core.domain.port.ConversionOutputRootStore
import io.sonora.core.domain.port.LibraryFolderRepository
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Where an import extracts to: a picked folder ([treeUri]) and the name it is shown by. */
internal data class ImportDestination(val treeUri: String, val name: String)

/** Why the password is asked again. */
internal enum class PasswordError { WRONG, NEEDED_AGAIN }

/** Where one import is; each step is one sheet (or none while [Extracting.hidden]). */
internal sealed interface ImportStep {
    data class Inspecting(val archiveUri: String) : ImportStep

    /**
     * Ready to import [archiveUri] (called [fileName]): its [summary], the [destination] (`null` until one is picked)
     * and how it stands in the library ([coverage]), and why the password is asked again, if it is.
     */
    data class Ready(
        val archiveUri: String,
        val fileName: String,
        val summary: ArchiveSummary,
        val destination: ImportDestination?,
        val coverage: DestinationCoverage?,
        val passwordError: PasswordError? = null,
    ) : ImportStep {
        /** The album's or playlist's name for a Sonora archive whose manifest is readable, else the file's. */
        val title: String get() = summary.manifest?.name?.takeIf { it.isNotBlank() } ?: fileName

        /** A folder, and a typed password when the archive is encrypted. */
        fun canImport(passwordTyped: Boolean): Boolean = destination != null && (!summary.encrypted || passwordTyped)
    }

    data class Extracting(val jobId: String, val name: String, val progress: ArchiveProgress?, val hidden: Boolean = false) : ImportStep

    /**
     * Job [jobId]'s music is in [folderUri], outside the library: the system folder picker opens there so the user
     * allows that folder to join the library ([pickerOpened] once it was launched). [record] is the import's history
     * entry, when it could be written.
     */
    data class ChoosingLibraryFolder(
        val jobId: String,
        val name: String,
        val folderUri: String,
        val fileCount: Int,
        val skipped: Int,
        val pickerOpened: Boolean = false,
        val record: RecordedImport? = null,
    ) : ImportStep

    /** Job [jobId]'s music is extracted; it is in the library (or its folder joins it) and the archive's list comes back. */
    data class Finishing(val jobId: String, val name: String, val hidden: Boolean = false) : ImportStep

    /**
     * The import ended, bringing [songs] (in the archive's order; empty when its record could not be written).
     * [addable] when the music stayed outside the library (Add to Library offers its folder again); [wrongFolder] when
     * the folder picked was not the extracted one.
     */
    data class Done(
        val name: String,
        val fileCount: Int,
        val skipped: Int,
        val outcome: ImportOutcome,
        val addable: AddableFolder? = null,
        val wrongFolder: Boolean = false,
        val songs: List<ImportedSong> = emptyList(),
    ) : ImportStep

    data class Failed(val failure: ArchiveFailure) : ImportStep
}

/** An import's extracted folder ([folderUri], made by job [jobId], recorded as [record]) that can still join the library. */
internal data class AddableFolder(val jobId: String, val folderUri: String, val record: RecordedImport? = null)

/** The name a picked tree is shown by: its path inside the storage ("Music/Imports"), or the volume for its root. */
internal fun destinationName(treeUri: String): String {
    val id = LibraryFolderRules.location(treeUri)?.documentId ?: return treeUri.substringAfterLast('/')
    return id.substringAfter(':').ifEmpty { id.substringBefore(':') }
}

/**
 * Imports a ZIP archive ("Open with Sonora", or Settings › Folders › Import Archive…): inspects it, asks for the
 * password when it is encrypted (again after a wrong one) and a destination (the converter's output folder, or another
 * one for this import), extracts it as a background job, then hands the new folder to the [finisher], which adds it to
 * the library and recreates a Sonora playlist or selection archive's playlist. Music extracted outside the library
 * joins it through its own folder only: the system folder picker opens at that folder for the user to allow it
 * (declined, it stays outside, and Add to Library offers it again).
 *
 * A started import is followed in [jobs], not in this view model's scope: it still finishes in the library, is
 * recorded in the import history ([recorder], with the playlist it recreated), and gives back the grants it kept, when
 * its screen is gone or another archive's sheet replaced its own. Grants: a kept read grant on the archive, and the persistable grant
 * taken on a folder picked for one import, which is given back once nothing uses it (see [releaseFolderIfUnused]).
 */
@HiltViewModel
class ImportArchiveViewModel @Inject constructor(
    private val importer: ArchiveImporter,
    private val scheduler: ArchiveJobScheduler,
    private val outputRoot: ConversionOutputRootStore,
    private val libraryFolders: LibraryFolderRepository,
    private val finisher: ImportFinisher,
    private val documents: ImportDocuments,
    private val jobs: ImportJobScope,
    private val recorder: ImportRecorder,
) : ViewModel() {

    private val _step = MutableStateFlow<ImportStep?>(null)
    internal val step: StateFlow<ImportStep?> = _step.asStateFlow()

    /** The inspection or the folder choice under way; an extraction is followed apart, to its end. */
    private var work: Job? = null

    /** The persistable read grant on the archive about to be imported, released when that import ends. */
    private var pendingGrant: String? = null

    /** The folder picked for the import about to start, whose persistable grant was taken for it. */
    private var pickedFolder: String? = null

    /** The destinations of the extractions under way, which need their folder's grant until they end. */
    private val runningDestinations = mutableListOf<String>()

    /** True from Import until the job is scheduled, so a double tap extracts once. */
    private var scheduling = false

    /**
     * Opens [archiveUri], shown as [fileName] (read from its provider when `null`); [keptGrant] when the caller kept
     * a persistable read grant on it, given back once the import ends. An import already extracting goes on behind
     * its notification and still finishes in the library.
     */
    fun open(archiveUri: String, fileName: String? = null, keptGrant: Boolean = false) {
        work?.cancel()
        releasePending()
        pendingGrant = archiveUri.takeIf { keptGrant }
        _step.value = ImportStep.Inspecting(archiveUri)
        work = viewModelScope.launch {
            val ready = try {
                when (val inspected = importer.inspect(archiveUri)) {
                    is ArchiveOutcome.Failed -> ImportStep.Failed(inspected.failure)
                    is ArchiveOutcome.Done -> {
                        val name = fileName ?: documents.displayName(archiveUri) ?: archiveUri.substringAfterLast('/')
                        val destination = outputRoot.root.first()?.let { ImportDestination(it.treeUri, it.displayName) }
                        ImportStep.Ready(archiveUri, name, inspected.value, destination, destination?.let { coverageOf(it.treeUri) })
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot inspect the archive", e)
                ImportStep.Failed(ArchiveFailure.Unexpected)
            }
            if (ready is ImportStep.Failed) releasePending()
            _step.value = ready
        }
    }

    /**
     * Uses [treeUri] (picked with the folder picker; [keptGrant] when its persistable grant was taken) for this
     * import. Without an output folder yet, it becomes the converter's output folder too, as the converter would ask
     * for one anyway. A folder picked before for this import is given back, unless something else uses it.
     */
    internal fun chooseDestination(treeUri: String, keptGrant: Boolean = false) {
        val ready = _step.value as? ImportStep.Ready ?: return
        val previous = pickedFolder
        pickedFolder = treeUri.takeIf { keptGrant } ?: previous?.takeIf { it == treeUri }
        work = viewModelScope.launch {
            val destination = try {
                if (ready.destination == null) {
                    outputRoot.set(treeUri).let { ImportDestination(it.treeUri, it.displayName) }
                } else {
                    ImportDestination(treeUri, destinationName(treeUri))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot keep the picked folder", e)
                ImportDestination(treeUri, destinationName(treeUri))
            }
            val coverage = coverageOf(destination.treeUri)
            (_step.value as? ImportStep.Ready)?.takeIf { it.archiveUri == ready.archiveUri }?.let {
                _step.value = it.copy(destination = destination, coverage = coverage)
            }
            if (previous != null && previous != treeUri) releaseFolderIfUnused(previous)
        }
    }

    /**
     * Extracts with [password] (`null` or empty: none); the scheduler takes the array over. A second tap while the
     * first is being scheduled is ignored, and its password wiped.
     */
    internal fun importArchive(password: CharArray?) {
        val ready = _step.value as? ImportStep.Ready
        val typed = password?.takeIf { it.isNotEmpty() }
        val destination = ready?.destination
        if (ready == null || destination == null || scheduling || !ready.canImport(passwordTyped = typed != null)) {
            password?.fill('\u0000')
            return
        }
        work?.cancel()
        scheduling = true
        val folderGrant = pickedFolder.also { pickedFolder = null }
        val archiveGrant = pendingGrant.also { pendingGrant = null }
        runningDestinations += destination.treeUri
        jobs.launch {
            val jobId = try {
                scheduler.extract(ready.archiveUri, destination.treeUri, typed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot schedule an extraction", e)
                _step.value = ImportStep.Failed(ArchiveFailure.Unexpected)
                end(destination, folderGrant, archiveGrant)
                return@launch
            } finally {
                scheduling = false
            }
            _step.value = ImportStep.Extracting(jobId, ready.title, progress = null)
            follow(jobId, ready, destination, folderGrant, archiveGrant)
        }
    }

    /** Closes the progress sheet; the job goes on and its result shows when it ends. */
    internal fun hide() {
        _step.value = when (val current = _step.value) {
            is ImportStep.Extracting -> current.copy(hidden = true)
            is ImportStep.Finishing -> current.copy(hidden = true)
            else -> return
        }
    }

    /** Stops the extraction (its new folder is removed) and closes the sheet. */
    internal fun stop() {
        val extracting = _step.value as? ImportStep.Extracting ?: return
        _step.value = null
        viewModelScope.launch { scheduler.cancel(extracting.jobId) }
    }

    /** The folder picker for the extracted folder was launched (it is not launched again on recomposition). */
    internal fun libraryFolderPickerOpened() {
        val choosing = _step.value as? ImportStep.ChoosingLibraryFolder ?: return
        _step.value = choosing.copy(pickerOpened = true)
    }

    /**
     * The user allowed [treeUri] in the folder picker: when it is the extracted folder it joins the library and the
     * playlist comes back; any other folder is not added.
     */
    internal fun libraryFolderPicked(treeUri: String) {
        val choosing = _step.value as? ImportStep.ChoosingLibraryFolder ?: return
        if (!ArchiveImportRules.isExtractedFolder(treeUri, choosing.folderUri)) {
            _step.value = choosing.done(OUTSIDE, wrongFolder = true)
            return
        }
        val record = choosing.record
        _step.value = ImportStep.Finishing(choosing.jobId, choosing.name)
        jobs.launch {
            val outcome = try {
                finisher.addAndFinish(treeUri, choosing.folderUri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot add the imported folder to the library", e)
                val added = try {
                    libraryFolders.folders().first().any { it.treeUri == treeUri }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                // The music is there; in the library only when the folder was added before the failure.
                if (added) OUTSIDE.copy(coverage = DestinationCoverage.JOINS_LIBRARY) else OUTSIDE
            }
            notePlaylist(record, outcome)
            showIfOwn(choosing.jobId) { choosing.done(outcome) }
        }
    }

    /** The user closed the folder picker: the music stays outside the library, with Add to Library offered. */
    internal fun libraryFolderDeclined() {
        val choosing = _step.value as? ImportStep.ChoosingLibraryFolder ?: return
        _step.value = choosing.done(OUTSIDE)
    }

    /** Add to Library on the finished sheet: the folder picker opens at the extracted folder again. */
    internal fun addToLibrary() {
        val done = _step.value as? ImportStep.Done ?: return
        val addable = done.addable ?: return
        _step.value = ImportStep.ChoosingLibraryFolder(
            addable.jobId, done.name, addable.folderUri, done.fileCount, done.skipped, record = addable.record,
        )
    }

    private fun ImportStep.ChoosingLibraryFolder.done(outcome: ImportOutcome, wrongFolder: Boolean = false) = ImportStep.Done(
        name, fileCount, skipped, outcome,
        addable = AddableFolder(jobId, folderUri, record).takeIf { outcome.coverage == DestinationCoverage.OUTSIDE_LIBRARY },
        wrongFolder = wrongFolder,
        songs = record?.songs.orEmpty(),
    )

    /** Closes the sheet; an extraction already running goes on behind its notification. */
    internal fun dismiss() {
        val current = _step.value
        if (current is ImportStep.Extracting || current is ImportStep.Finishing) return
        if (current is ImportStep.ChoosingLibraryFolder) return libraryFolderDeclined()
        work?.cancel()
        if (current !is ImportStep.Done) releasePending()
        _step.value = null
    }

    /**
     * Follows job [jobId] to its end, which also records the import in the history and finishes it in the library; the
     * sheet mirrors it only while it is that job's own (an archive opened meanwhile keeps its sheet). A wrong or lost password goes back to [ready],
     * keeping the grants for the next try; any other end gives them back. A job no longer known (lost from
     * WorkManager) ends as unexpected rather than leaving a sheet that never ends.
     */
    private suspend fun follow(
        jobId: String,
        ready: ImportStep.Ready,
        destination: ImportDestination,
        folderGrant: String?,
        archiveGrant: String?,
    ) {
        val end = scheduler.state(jobId).first { state ->
            if (state is ArchiveJobState.Running) showIfOwn(jobId) { (it as? ImportStep.Extracting)?.copy(progress = state.progress) ?: it }
            state is ArchiveJobState.Extracted || state is ArchiveJobState.Failed || state == ArchiveJobState.Cancelled ||
                state == ArchiveJobState.Unknown
        }
        when (end) {
            is ArchiveJobState.Extracted -> {
                val record = record(ready.fileName, destination, end.folderUri)
                if (isOwn(jobId) && coverageOf(destination.treeUri) == DestinationCoverage.JOINS_LIBRARY) {
                    _step.value = ImportStep.ChoosingLibraryFolder(
                        jobId, ready.title, end.folderUri, end.fileCount, end.skippedCount, record = record,
                    )
                } else {
                    showIfOwn(jobId) { ImportStep.Finishing(jobId, ready.title) }
                    val outcome = finish(destination, end.folderUri)
                    notePlaylist(record, outcome)
                    val addable = AddableFolder(jobId, end.folderUri, record).takeIf { outcome.coverage == DestinationCoverage.OUTSIDE_LIBRARY }
                    showIfOwn(jobId) {
                        ImportStep.Done(ready.title, end.fileCount, end.skippedCount, outcome, addable, songs = record?.songs.orEmpty())
                    }
                }
            }
            is ArchiveJobState.Failed -> {
                val retry = when (end.failure) {
                    ArchiveFailure.WrongPassword, ArchiveFailure.PasswordRequired -> PasswordError.WRONG
                    ArchiveFailure.PasswordNeededAgain -> PasswordError.NEEDED_AGAIN
                    else -> null
                }
                if (retry != null && isOwn(jobId)) {
                    // Asked again: the same archive is still to be imported, into the same folder.
                    runningDestinations -= destination.treeUri
                    pendingGrant = archiveGrant
                    pickedFolder = folderGrant
                    _step.value = ready.copy(summary = ready.summary.copy(encrypted = true), passwordError = retry)
                    return
                }
                showIfOwn(jobId) { ImportStep.Failed(end.failure) }
            }
            ArchiveJobState.Unknown -> showIfOwn(jobId) { ImportStep.Failed(ArchiveFailure.Unexpected) }
            else -> showIfOwn(jobId) { null }
        }
        end(destination, folderGrant, archiveGrant)
    }

    /** An import into [destination] is over: gives back the grants it kept. */
    private suspend fun end(destination: ImportDestination, folderGrant: String?, archiveGrant: String?) {
        runningDestinations -= destination.treeUri
        archiveGrant?.let(documents::releaseArchive)
        folderGrant?.let { releaseFolderIfUnused(it) }
    }

    private fun isOwn(jobId: String): Boolean = when (val current = _step.value) {
        is ImportStep.Extracting -> current.jobId == jobId
        is ImportStep.ChoosingLibraryFolder -> current.jobId == jobId
        is ImportStep.Finishing -> current.jobId == jobId
        else -> false
    }

    private inline fun showIfOwn(jobId: String, next: (ImportStep?) -> ImportStep?) {
        if (isOwn(jobId)) _step.value = next(_step.value)
    }

    /** Records the import of [archiveName] in the history; `null` when it cannot be written (the import goes on). */
    private suspend fun record(archiveName: String, destination: ImportDestination, folderUri: String): RecordedImport? = try {
        recorder.record(archiveName, destination.treeUri, folderUri)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Cannot record the import in the history", e)
        null
    }

    /** Keeps the playlist [outcome] recreated with the import's [record], so deleting the import deletes it too. */
    private suspend fun notePlaylist(record: RecordedImport?, outcome: ImportOutcome) {
        val playlistId = outcome.playlistId ?: return
        val importId = record?.id ?: return
        try {
            recorder.playlistMade(importId, playlistId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cannot note the recreated playlist in the import history", e)
        }
    }

    private suspend fun finish(destination: ImportDestination, folderUri: String): ImportOutcome = try {
        finisher.finish(destination.treeUri, folderUri)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The music is there, inside a library folder; only the playlist could not be made.
        Log.w(TAG, "Cannot finish the import", e)
        ImportOutcome(DestinationCoverage.IN_LIBRARY, playlistName = null, playlistSongs = 0, missing = 0)
    }

    private fun releasePending() {
        pendingGrant?.let(documents::releaseArchive)
        pendingGrant = null
        val picked = pickedFolder ?: return
        pickedFolder = null
        jobs.launch { releaseFolderIfUnused(picked) }
    }

    /**
     * Gives back the grant taken on the picked folder [treeUri] unless it is still needed: by the converter's output
     * folder or a library folder of that exact address (they share the one persistable grant), by an extraction
     * under way into it, or by the import about to start there.
     */
    private suspend fun releaseFolderIfUnused(treeUri: String) {
        val needed = try {
            treeUri == pickedFolder || treeUri in runningDestinations ||
                outputRoot.root.first()?.treeUri == treeUri ||
                libraryFolders.folders().first().any { it.treeUri == treeUri }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cannot tell whether the folder is still used; its grant is kept", e)
            true
        }
        if (!needed) documents.releaseFolder(treeUri)
    }

    private suspend fun coverageOf(treeUri: String): DestinationCoverage? = try {
        ArchiveImportRules.coverage(treeUri, libraryFolders.folders().first())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read the library folders", e)
        null
    }

    private companion object {
        const val TAG = "SonoraImport"
        val OUTSIDE = ImportOutcome(DestinationCoverage.OUTSIDE_LIBRARY, playlistName = null, playlistSongs = 0, missing = 0)
    }
}
