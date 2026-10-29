package io.sonora.feature.converter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.sonora.core.domain.conversion.ConversionProfile
import io.sonora.core.domain.conversion.LossyOverwriteCheck
import io.sonora.core.domain.conversion.OutputSelection
import io.sonora.core.domain.conversion.OutputTarget
import io.sonora.core.domain.conversion.OutputTargets
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.conversion.batch.CancelConversionBatch
import io.sonora.core.domain.conversion.batch.ObserveConversionHistory
import io.sonora.core.domain.conversion.batch.PendingConversionSources
import io.sonora.core.domain.conversion.batch.ResolveConvertedTracks
import io.sonora.core.domain.conversion.batch.StartBatchResult
import io.sonora.core.domain.conversion.batch.StartConversionBatch
import io.sonora.core.domain.model.BatchSource
import io.sonora.core.domain.model.ConversionBatchItem
import io.sonora.core.domain.model.ConverterPreferences
import io.sonora.core.domain.model.PickedSource
import io.sonora.core.domain.port.ConversionBatchRepository
import io.sonora.core.domain.port.ConversionBatchScheduler
import io.sonora.core.domain.port.ConversionOutputRootStore
import io.sonora.core.domain.port.ConversionSourceExpander
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.domain.port.PreferencesRepository
import io.sonora.core.domain.port.SourceFolderAccess
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The converter: the next batch's settings and picks, the active batch (live progress, then its summary) and the
 * history. "Replace originals" is irreversible, so it is confirmed through [prompt] before a batch (the overwrite
 * switch) and offered once after a batch the screen watched finish ([ConverterRules.asksToReplace]).
 * Subfolders a started batch had to leave out are reported with it ([ConverterUiState.skippedFolders]).
 * Converted files play in Sonora from a batch's detail or summary ([playItem], [playAll]); files moved or deleted
 * since are skipped and counted in [missingFiles].
 *
 * The output: one batch-wide target ([setBatchWide], stored as the starting value of the next batch) and targets set
 * by hand for single sources and for songs inside a picked folder ([setOutput], [useInherited]), which last for that
 * batch only. A folder's songs are listed when its row is opened ([openFolder]). A batch that overwrites originals and
 * would replace lossless originals with lossy files asks first ([ReplacePrompt.LossyOverwrite]).
 */
@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
@HiltViewModel
class ConverterViewModel @Inject constructor(
    private val startBatch: StartConversionBatch,
    private val cancelBatch: CancelConversionBatch,
    observeHistory: ObserveConversionHistory,
    private val outputRoot: ConversionOutputRootStore,
    private val scheduler: ConversionBatchScheduler,
    private val repository: ConversionBatchRepository,
    private val resolveTracks: ResolveConvertedTracks,
    private val playback: PlaybackController,
    private val pending: PendingConversionSources,
    private val expander: ConversionSourceExpander,
    private val preferences: PreferencesRepository,
    private val folderAccess: SourceFolderAccess,
) : ViewModel() {

    /** What the user set up for the next batch; its sources live in [pending], shared with the Library. */
    private data class Form(
        val output: OutputSelection<OutputTarget> = OutputSelection(OutputTarget.Xdj700),
        /** The user picked the batch-wide target, so the stored one, read late, does not replace it. */
        val batchWideChosen: Boolean = false,
        val mode: PerformanceMode = PerformanceMode.DEFAULT,
        val overwriteOriginals: Boolean = false,
        val starting: Boolean = false,
    )

    private val form = MutableStateFlow(Form())
    private val folders = MutableStateFlow<Map<String, FolderContents>>(emptyMap())

    /** The batch the lossy warning is about, started as it was if the user confirms. */
    private var awaitingConfirmation: Pair<List<PendingSource>, Form>? = null

    init {
        viewModelScope.launch {
            val stored = try {
                preferences.preferences.first().converter.outputTarget
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            form.update { if (it.batchWideChosen) it else it.copy(output = it.output.withBatchWide(stored)) }
        }
    }
    private val selectedBatchId = MutableStateFlow<Long?>(null)

    /** Batches seen converting, and those already asked about (or not to be asked about) afterwards. */
    private val seenConverting = mutableSetOf<Long>()
    private val askedAfterBatch = mutableSetOf<Long>()

    private val _prompt = MutableStateFlow<ReplacePrompt?>(null)
    val prompt: StateFlow<ReplacePrompt?> = _prompt.asStateFlow()

    private val _notice = MutableStateFlow<ConverterNotice?>(null)
    val notice: StateFlow<ConverterNotice?> = _notice.asStateFlow()

    /**
     * Converted files the last play could not open (moved or deleted since); 0 when there is nothing to tell. Kept until
     * the screen has shown it ([missingFilesShown]), so a notice raised while the screen is covered is not lost.
     */
    private val _missingFiles = MutableStateFlow(0)
    val missingFiles: StateFlow<Int> = _missingFiles.asStateFlow()
    private var playJob: Job? = null

    /**
     * Whether the active batch should scroll into view: it just started or its notification was opened. The screen
     * clears it through [revealHandled]; a screen restored later (also after process death) finds nothing pending.
     */
    private val _revealRequested = MutableStateFlow(false)
    val revealRequested: StateFlow<Boolean> = _revealRequested.asStateFlow()

    /**
     * Whether the sources should scroll into view: songs just arrived from the Library's "Convert…". Kept here, not in
     * the screen, because the tab's screen is recreated while it slides in; cleared through [sourcesRevealHandled].
     */
    private val _sourcesRevealRequested = MutableStateFlow(false)
    val sourcesRevealRequested: StateFlow<Boolean> = _sourcesRevealRequested.asStateFlow()

    fun revealSources() {
        _sourcesRevealRequested.value = true
    }

    /** The screen scrolled the sources into view. */
    fun sourcesRevealHandled() {
        _sourcesRevealRequested.value = false
    }

    /** Subfolders the last started batch left out because they could not be read, by batch id. */
    private val skippedFolders = MutableStateFlow<Pair<Long, Int>?>(null)

    private val history: StateFlow<List<HistorySectionUi>> = observeHistory()
        .map(ConverterRules::historySections)
        .onEach { sections ->
            // A batch still running (the app was closed and reopened) is shown as the active one.
            if (selectedBatchId.value == null) {
                sections.flatMap { it.entries }.firstOrNull { !it.batch.status.isFinal }?.let { selectedBatchId.value = it.batch.id }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val activeBatch: StateFlow<BatchSnapshot?> = selectedBatchId
        .flatMapLatest { id -> if (id == null) flowOf(null) else batchDetail(id) }
        .onEach(::offerReplacementOnce)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<ConverterUiState> =
        combine(combine(form, pending.sources, folders, ::Triple), outputRoot.root, activeBatch, history, skippedFolders) {
                (form, sources, folders), root, active, history, skipped ->
            ConverterUiState(
                output = form.output,
                folders = folders,
                outputFolder = root,
                sources = sources,
                mode = form.mode,
                overwriteOriginals = form.overwriteOriginals,
                starting = form.starting,
                activeBatch = active,
                history = history,
                skippedFolders = skipped?.takeIf { (batchId, _) -> batchId == active?.batch?.id }?.second ?: 0,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ConverterUiState())

    /** Batch [batchId] with its files and live progress; `null` once it is deleted. */
    fun batchDetail(batchId: Long): Flow<BatchSnapshot?> =
        combine(repository.batch(batchId), repository.items(batchId), scheduler.progress(batchId)) { batch, items, progress ->
            batch?.let { BatchSnapshot(it, items, progress) }
        }

    /** A new batch-wide target: every source and song not set by hand follows it, and it is stored for next time. */
    fun setBatchWide(target: OutputTarget) {
        form.update { it.copy(output = it.output.withBatchWide(target), batchWideChosen = true) }
        viewModelScope.launch {
            // Not stored (disk full): it still applies to this batch.
            attempt { preferences.update { it.copy(converter = ConverterPreferences.of(target)) } }
        }
    }

    /** Sets source [source] (a song, a file or a folder), or song [child] inside folder [source], by hand. */
    fun setOutput(source: String, child: String?, target: OutputTarget) = form.update {
        it.copy(output = if (child == null) it.output.withSource(source, target) else it.output.withChild(source, child, target))
    }

    /** Puts [source] back on the batch-wide target, or song [child] of folder [source] back on its folder's. */
    fun useInherited(source: String, child: String?) = form.update {
        it.copy(output = if (child == null) it.output.clearSource(source) else it.output.clearChild(source, child))
    }

    /** Lists picked folder [uri]'s songs, once; a folder that could not be listed is tried again. */
    fun openFolder(uri: String) {
        val current = folders.value[uri]
        if (current == FolderContents.Loading || current is FolderContents.Listed) return
        folders.update { it + (uri to FolderContents.Loading) }
        viewModelScope.launch {
            val contents = try {
                expander.expand(listOf(PickedSource.Folder(uri))).let { FolderContents.Listed(it.sources, it.skippedFolders) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                FolderContents.Failed
            }
            // A folder removed meanwhile stays forgotten.
            folders.update { if (uri in it) it + (uri to contents) else it }
        }
    }

    fun setOutputFolder(treeUri: String) {
        viewModelScope.launch {
            try {
                outputRoot.set(treeUri)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _notice.value = ConverterNotice.OUTPUT_FOLDER_FAILED
            }
        }
    }

    /** Adds picks; one already listed (same URI) stays as it was. */
    fun addSources(picked: List<PendingSource>) = pending.add(picked)

    fun removeSource(source: PendingSource) {
        pending.remove(source.pick.uri)
        forget(setOf(source.pick.uri))
    }

    /** Drops what was set and listed for sources no longer waiting. */
    private fun forget(uris: Set<String>) {
        form.update { next -> next.copy(output = uris.fold(next.output) { output, uri -> output.removeSource(uri) }) }
        folders.update { it - uris }
    }

    fun setMode(mode: PerformanceMode) = form.update { it.copy(mode = mode) }

    /** Turning the switch on asks first ([ReplacePrompt.BeforeBatch]); it turns on only through [confirmReplace]. */
    fun setOverwriteOriginals(on: Boolean) {
        if (on) _prompt.value = ReplacePrompt.BeforeBatch else form.update { it.copy(overwriteOriginals = false) }
    }

    /** Offers to replace the originals of a finished batch now (its summary or its history detail). */
    fun requestReplaceOriginals(snapshot: BatchSnapshot) {
        val count = snapshot.replaceableCount
        if (count > 0) _prompt.value = afterBatchPrompt(snapshot)
    }

    private fun afterBatchPrompt(snapshot: BatchSnapshot) = ReplacePrompt.AfterBatch(
        snapshot.batch.id,
        snapshot.replaceableCount,
        ConverterRules.lossyReplaceableCount(snapshot.items),
    )

    fun confirmReplace() {
        val prompt = _prompt.value
        _prompt.value = null
        when (prompt) {
            ReplacePrompt.BeforeBatch -> form.update { it.copy(overwriteOriginals = true) }
            is ReplacePrompt.AfterBatch -> scheduler.enqueueReplacement(prompt.batchId)
            is ReplacePrompt.LossyOverwrite -> awaitingConfirmation?.let { (sources, next) ->
                awaitingConfirmation = null
                if (!form.value.starting && activeBatch.value?.isRunning != true) {
                    form.update { it.copy(starting = true) }
                    viewModelScope.launch { start(sources, next) }
                }
            }
            null -> Unit
        }
    }

    fun dismissReplacePrompt() {
        _prompt.value = null
        awaitingConfirmation = null
    }

    fun convert() {
        val next = form.value
        val sources = pending.sources.value
        if (sources.isEmpty() || next.starting || activeBatch.value?.isRunning == true) return
        form.update { it.copy(starting = true) }
        viewModelScope.launch {
            if (next.overwriteOriginals) {
                val lossy = try {
                    lossyOverwrites(sources, next.output)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Overwriting without the check could replace lossless originals unasked.
                    fail(ConverterNotice.START_FAILED)
                    return@launch
                }
                if (lossy > 0) {
                    awaitingConfirmation = sources to next
                    form.update { it.copy(starting = false) }
                    _prompt.value = ReplacePrompt.LossyOverwrite(lossy)
                    return@launch
                }
            }
            start(sources, next)
        }
    }

    /**
     * Songs of [sources] that would replace a lossless original with a lossy file. Only those whose original can be
     * replaced count: not a file picked on its own (its folder is unknown), nor a library song whose folder Sonora
     * cannot write, which [StartConversionBatch] converts like one.
     */
    private suspend fun lossyOverwrites(sources: List<PendingSource>, output: OutputSelection<OutputTarget>): Int {
        val picks = sources.map { it.pick }
        val songs = picks.filterIsInstance<PickedSource.Track>().mapTo(HashSet()) { it.uri }
        val codecs = sources.mapNotNull { source -> source.codec?.let { source.pick.uri to it } }.toMap()
        val files = expander.expand(picks).sources.filter { file ->
            val parent = file.parentUri ?: return@filter false
            file.uri !in songs || canWrite(parent)
        }
        val items = files.map { LossyOverwriteCheck.Item(it.displayName, codecs[it.uri], profileOf(output, it)) }
        return LossyOverwriteCheck.count(items, overwriteOriginals = true)
    }

    /** As [StartConversionBatch] decides: a failed check (provider error, revoked access) reads as no write access. */
    private suspend fun canWrite(folderUri: String): Boolean = try {
        folderAccess.canWrite(folderUri)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /** What [file] converts to: its own target, its folder's, or the batch-wide one. */
    private fun profileOf(output: OutputSelection<OutputTarget>, file: BatchSource): ConversionProfile =
        OutputTargets.profileFor(output.resolve(file.pickUri ?: file.uri, file.uri))

    private suspend fun start(sources: List<PendingSource>, next: Form) {
        val result = try {
            startBatch(
                sources.map { it.pick },
                OutputTargets.profileFor(next.output.batchWide),
                next.mode,
                next.overwriteOriginals,
                profileFor = { profileOf(next.output, it) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        when (result) {
            is StartBatchResult.Started -> {
                // Only what this batch took: sources picked while it was starting stay for the next one.
                val taken = sources.mapTo(HashSet()) { it.pick.uri }
                pending.removeAll(taken)
                forget(taken)
                form.update { it.copy(overwriteOriginals = false, starting = false) }
                skippedFolders.value = result.batchId to result.skippedFolders
                showBatch(result.batchId)
            }
            StartBatchResult.NoOutputFolder -> fail(ConverterNotice.NO_OUTPUT_FOLDER)
            StartBatchResult.NoAudioFiles -> fail(ConverterNotice.NO_AUDIO_FILES)
            null -> fail(ConverterNotice.START_FAILED)
        }
    }

    private fun fail(notice: ConverterNotice) {
        form.update { it.copy(starting = false) }
        _notice.value = notice
    }

    fun cancelActiveBatch() {
        val id = activeBatch.value?.takeIf { it.isConverting }?.batch?.id ?: return
        viewModelScope.launch {
            if (!attempt { cancelBatch(id) }) _notice.value = ConverterNotice.CANCEL_FAILED
        }
    }

    /** Shows batch [batchId] as the active one and asks to scroll it into view (tapping its notification). */
    fun showBatch(batchId: Long) {
        selectedBatchId.value = batchId
        _revealRequested.value = true
    }

    /** The screen scrolled the active batch into view (or no longer needs to). */
    fun revealHandled() {
        _revealRequested.value = false
    }

    /** Hides the summary of a finished batch; a running one stays. */
    fun dismissActiveBatch() {
        if (activeBatch.value?.isRunning != true) selectedBatchId.value = null
    }

    /** Forgets a finished batch; its converted files stay where they are. */
    fun deleteFromHistory(batchId: Long) {
        viewModelScope.launch {
            val deleted = attempt {
                if (repository.getBatch(batchId)?.status?.isFinal != true) return@attempt
                repository.delete(batchId)
                if (selectedBatchId.value == batchId) selectedBatchId.value = null
            }
            if (!deleted) _notice.value = ConverterNotice.DELETE_FAILED
        }
    }

    /** Runs [action]; false when it failed (cancellation still propagates), so the caller can tell the user. */
    private suspend fun attempt(action: suspend () -> Unit): Boolean = try {
        action()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /**
     * A tapped converted file: plays its batch's converted files as a queue, in batch order, starting at [item], like a
     * tapped song in a list. A file that was not converted does nothing; one that is gone plays nothing.
     */
    fun playItem(item: ConversionBatchItem) {
        if (ConverterRules.isPlayable(item)) play(item.batchId, startItemId = item.id)
    }

    /** Plays batch [batchId]'s converted files as a queue, in batch order, from the first. */
    fun playAll(batchId: Long) = play(batchId, startItemId = null)

    /** The screen showed the [missingFiles] notice for [count] files; a newer count raised meanwhile stays. */
    fun missingFilesShown(count: Int) {
        _missingFiles.compareAndSet(count, 0)
    }

    /** The latest tap wins: a play still opening its files is dropped for the newer one. */
    private fun play(batchId: Long, startItemId: Long?) {
        playJob?.cancel()
        playJob = viewModelScope.launch {
            val outputs = try {
                resolveTracks(batchId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _notice.value = ConverterNotice.PLAY_FAILED
                return@launch
            }
            // ResolveConvertedTracks ids each track by its item (-item id); a tapped file that is gone has none.
            val start = if (startItemId == null) 0 else outputs.tracks.indexOfFirst { it.id == -startItemId }
            if (start in outputs.tracks.indices) playback.playQueue(outputs.tracks, start)
            if (outputs.unavailable > 0) _missingFiles.value = outputs.unavailable
        }
    }

    fun pickerMissing() {
        _notice.value = ConverterNotice.PICKER_MISSING
    }

    fun dismissNotice() {
        _notice.value = null
    }

    /**
     * Asks once per batch the screen watched convert, as soon as its ending is settled ([ConverterRules.hasSettled]).
     * The history reports the batch ended while its job is still winding down (its last progress lingers until the
     * work ends) and may list the files' outcome later still; deciding before then would find nothing to replace and
     * use up the only ask.
     */
    private fun offerReplacementOnce(snapshot: BatchSnapshot?) {
        val id = snapshot?.batch?.id ?: return
        if (snapshot.isConverting) {
            seenConverting += id
            return
        }
        if (id !in seenConverting || id in askedAfterBatch || !ConverterRules.hasSettled(snapshot)) return
        askedAfterBatch += id
        if (ConverterRules.asksToReplace(snapshot)) _prompt.value = afterBatchPrompt(snapshot)
    }
}
