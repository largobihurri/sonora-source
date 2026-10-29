package io.sonora.feature.converter

import io.sonora.core.domain.conversion.ConversionBudget
import io.sonora.core.domain.conversion.ConversionPlan
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.conversion.batch.ConversionBatchRunner
import io.sonora.core.domain.conversion.batch.OriginalReplacer
import io.sonora.core.domain.library.TrackTags
import io.sonora.core.domain.model.AppPreferences
import io.sonora.core.domain.model.BatchCounts
import io.sonora.core.domain.model.BatchItemState
import io.sonora.core.domain.model.BatchProgress
import io.sonora.core.domain.model.BatchSource
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.ConversionBatch
import io.sonora.core.domain.model.ConversionBatchItem
import io.sonora.core.domain.model.ConversionRequest
import io.sonora.core.domain.model.ConversionResult
import io.sonora.core.domain.model.OutputVerification
import io.sonora.core.domain.model.PickedSource
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.AudioConverter
import io.sonora.core.domain.port.ConversionBatchRepository
import io.sonora.core.domain.port.ConversionBatchScheduler
import io.sonora.core.domain.port.ConversionBudgetSource
import io.sonora.core.domain.port.ConversionFiles
import io.sonora.core.domain.port.ConversionOutputRootStore
import io.sonora.core.domain.port.ConversionSourceExpander
import io.sonora.core.domain.port.ConvertedFile
import io.sonora.core.domain.port.ConvertedFileReader
import io.sonora.core.domain.port.ExpandedSources
import io.sonora.core.domain.port.OutputRootFolder
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.domain.port.PreferencesRepository
import io.sonora.core.domain.port.SourceFolderAccess
import io.sonora.core.domain.port.StoredDocument
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** In-memory history; counts are derived from the items, as Room does. */
class FakeBatchRepository : ConversionBatchRepository {
    private val stored = MutableStateFlow<Map<Long, ConversionBatch>>(emptyMap())
    private val files = MutableStateFlow<Map<Long, List<ConversionBatchItem>>>(emptyMap())
    private var nextId = 1L

    override suspend fun create(batch: ConversionBatch, sources: List<BatchSource>): Long {
        val id = nextId++
        files.value += id to sources.mapIndexed { index, source ->
            ConversionBatchItem(
                id * 100 + index, id, index, source.uri, source.displayName, source.parentUri,
                profileId = source.profileId ?: batch.profileId,
            )
        }
        stored.value += id to batch.copy(id = id)
        return id
    }

    override fun batches(): Flow<List<ConversionBatch>> = combine(stored, files) { batches, items ->
        batches.values.map { it.withCounts(items[it.id].orEmpty()) }.sortedByDescending { it.createdAtMs }
    }

    override fun batch(id: Long): Flow<ConversionBatch?> =
        combine(stored, files) { batches, items -> batches[id]?.withCounts(items[id].orEmpty()) }

    override fun items(batchId: Long): Flow<List<ConversionBatchItem>> = files.map { it[batchId].orEmpty() }

    override suspend fun getBatch(id: Long): ConversionBatch? = stored.value[id]?.withCounts(files.value[id].orEmpty())

    /** Thrown by [getItems], as a broken database would. */
    var readFailure: Exception? = null

    override suspend fun getItems(batchId: Long): List<ConversionBatchItem> {
        readFailure?.let { throw it }
        return files.value[batchId].orEmpty()
    }

    override suspend fun updateBatch(batch: ConversionBatch) {
        stored.value += batch.id to batch
    }

    override suspend fun updateItem(item: ConversionBatchItem) {
        files.value += item.batchId to files.value[item.batchId].orEmpty().map { if (it.id == item.id) item else it }
    }

    override suspend fun transition(batchId: Long, from: Set<BatchStatus>, to: BatchStatus, failureReason: String?): Boolean {
        val batch = stored.value[batchId]?.takeIf { it.status in from } ?: return false
        stored.value += batchId to batch.copy(status = to, failureReason = failureReason)
        return true
    }

    /** Thrown by [delete], as a broken database would. */
    var deleteFailure: Exception? = null

    override suspend fun delete(batchId: Long) {
        deleteFailure?.let { throw it }
        stored.value -= batchId
        files.value -= batchId
    }

    /** Test helper: stores [batch] (with its id) and its [items] at once. */
    fun put(batch: ConversionBatch, items: List<ConversionBatchItem> = emptyList()) {
        files.value += batch.id to items
        stored.value += batch.id to batch
        nextId = maxOf(nextId, batch.id + 1)
    }

    fun setItems(batchId: Long, items: List<ConversionBatchItem>) {
        files.value += batchId to items
    }

    fun update(batchId: Long, change: (ConversionBatch) -> ConversionBatch) {
        stored.value += batchId to change(stored.value.getValue(batchId))
    }

    fun has(batchId: Long): Boolean = batchId in stored.value

    fun itemsOf(batchId: Long): List<ConversionBatchItem> = files.value[batchId].orEmpty()

    private fun ConversionBatch.withCounts(items: List<ConversionBatchItem>) = copy(
        counts = BatchCounts(
            total = items.size,
            pending = items.count { it.state == BatchItemState.PENDING },
            running = items.count { it.state == BatchItemState.RUNNING },
            done = items.count { it.state == BatchItemState.DONE },
            failed = items.count { it.state == BatchItemState.FAILED },
            skipped = items.count { it.state == BatchItemState.SKIPPED },
            replacedOriginals = items.count { it.state == BatchItemState.REPLACED_ORIGINAL },
            withoutTags = items.count { it.isConverted && !it.tagsWritten },
            notReplaced = items.count { it.state == BatchItemState.FAILED && it.canReplaceOriginal },
        ),
    )
}

class FakeScheduler : ConversionBatchScheduler {
    val conversions = mutableListOf<Long>()
    val replacements = mutableListOf<Long>()
    val cancelled = mutableListOf<Long>()
    val progress = MutableStateFlow<Map<Long, BatchProgress>>(emptyMap())
    /** Whether a cancelled batch's job was running, so it ends the batch itself. */
    var jobRunning = true
    /** Thrown by [cancel], as WorkManager does when it cannot reach its database. */
    var cancelFailure: Exception? = null

    override fun enqueueConversion(batchId: Long) {
        conversions += batchId
    }

    override fun enqueueReplacement(batchId: Long) {
        replacements += batchId
    }

    override suspend fun cancel(batchId: Long): Boolean {
        cancelFailure?.let { throw it }
        cancelled += batchId
        return jobRunning
    }

    override fun progress(batchId: Long): Flow<BatchProgress?> = progress.map { it[batchId] }
}

class FakeOutputRoot(initial: OutputRootFolder? = null) : ConversionOutputRootStore {
    override val root = MutableStateFlow(initial)
    var failure: Exception? = null

    override suspend fun set(treeUri: String): OutputRootFolder {
        failure?.let { throw it }
        return OutputRootFolder(treeUri, treeUri.substringAfterLast('/')).also { root.value = it }
    }
}

/**
 * Expands every pick to [sources] (or, for a pick listed in [byPick], to its own files), or throws [failure]; holds
 * while [gate] is not completed. Every call's picks are kept in [calls].
 */
class FakeExpander(var sources: List<BatchSource> = emptyList(), var failure: Exception? = null) : ConversionSourceExpander {
    var lastPicks: List<PickedSource> = emptyList()
    val calls = mutableListOf<List<PickedSource>>()
    var gate: CompletableDeferred<Unit>? = null
    /** Subfolders of the picks that could not be read. */
    var skippedFolders = 0
    val byPick = mutableMapOf<String, List<BatchSource>>()

    override suspend fun expand(picks: List<PickedSource>): ExpandedSources {
        lastPicks = picks
        calls += picks
        gate?.await()
        failure?.let { throw it }
        if (byPick.isEmpty()) return ExpandedSources(sources, skippedFolders)
        return ExpandedSources(picks.flatMap { pick -> byPick[pick.uri].orEmpty().map { it.copy(pickUri = pick.uri) } }, skippedFolders)
    }
}

/** Preferences in memory; [updateFailure] is thrown by [update], as a full disk would. */
class FakePreferences(initial: AppPreferences = AppPreferences()) : PreferencesRepository {
    override val preferences = MutableStateFlow(initial)
    var updateFailure: Exception? = null

    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        updateFailure?.let { throw it }
        preferences.value = transform(preferences.value)
    }
}

object GrantingAccess : SourceFolderAccess {
    override suspend fun persistWrite(treeUri: String) = true
    override suspend fun canWrite(folderUri: String) = true
}

/** Write access to every folder but those in [denied]. */
class FolderAccess(var denied: Set<String> = emptySet()) : SourceFolderAccess {
    override suspend fun persistWrite(treeUri: String) = treeUri !in denied
    override suspend fun canWrite(folderUri: String) = folderUri !in denied
}

/** Collaborators of [ConversionBatchRunner] that cancelling a queued batch never touches. */
object UnusedFiles : ConversionFiles {
    override suspend fun children(folderUri: String): List<StoredDocument> = emptyList()
    override suspend fun findOrCreateFolder(parentUri: String, name: String): StoredDocument = error("unused")
    override suspend fun createFolder(parentUri: String, name: String): StoredDocument = error("unused")
    override suspend fun createFile(folderUri: String, name: String, mimeType: String): StoredDocument = error("unused")
    override suspend fun rename(documentUri: String, name: String): StoredDocument? = error("unused")
    override suspend fun copy(fromUri: String, toUri: String) = error("unused")
    override suspend fun delete(documentUri: String): Boolean = error("unused")
}

object UnusedConverter : AudioConverter {
    override suspend fun convert(request: ConversionRequest, onProgress: (Float) -> Unit): ConversionResult = error("unused")
    override suspend fun verify(outputUri: String, expected: ConversionResult): OutputVerification = error("unused")
}

object UnusedBudgets : ConversionBudgetSource {
    override fun budgets(mode: PerformanceMode): Flow<ConversionBudget> = error("unused")
}

fun runnerFor(repository: ConversionBatchRepository, outputRoot: ConversionOutputRootStore) = ConversionBatchRunner(
    repository,
    UnusedFiles,
    outputRoot,
    UnusedConverter,
    UnusedBudgets,
    OriginalReplacer(UnusedFiles, GrantingAccess, UnusedConverter, repository),
)

val RESULT = ConversionResult(
    plan = ConversionPlan(ConversionProfiles.XDJ_700, 44_100, 44_100, 2, 2, 16, dither = false),
    outputFrames = 44_100,
    sourceFrames = 44_100,
    tagsWritten = true,
)

/** A verified output still in the batch folder. */
fun ConversionBatchItem.converted(tags: Boolean = true) =
    copy(state = BatchItemState.DONE, outputUri = "content://out/$sourceName", outputName = sourceName, tagsWritten = tags, result = RESULT)

/** Converted files by URI, titled after their name; any other URI is gone. [gates] hold a read until completed. */
class FakeConvertedFiles : ConvertedFileReader {
    val existing = mutableSetOf<String>()
    val reads = mutableListOf<String>()
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    override suspend fun read(uri: String, fileName: String): ConvertedFile? {
        reads += uri
        gates[uri]?.await()
        return if (uri in existing) ConvertedFile(TrackTags(title = fileName.substringBeforeLast('.')), null, "audio/aiff", 1) else null
    }
}

/** Records the queues it is asked to play, as "start:uri,uri". */
class RecordingPlayback : PlaybackController {
    val played = mutableListOf<String>()
    override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())
    override fun playQueue(queue: List<Track>, startIndex: Int, shuffle: Boolean) {
        played += "$startIndex:" + queue.joinToString(",") { it.uri }
    }
    override fun playNow(tracks: List<Track>) = error("unused")
    override fun playNext(tracks: List<Track>) = error("unused")
    override fun addToQueue(tracks: List<Track>) = error("unused")
    override fun moveQueueItem(from: Int, to: Int) = Unit
    override fun removeQueueItem(index: Int) = Unit
    override fun clearUpcoming() = Unit
    override fun skipToQueueItem(index: Int) = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun togglePlayPause() = Unit
    override fun skipToNext() = Unit
    override fun skipToPrevious() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setShuffle(enabled: Boolean) = Unit
    override fun setRepeatMode(mode: RepeatMode) = Unit
    override fun cycleRepeatMode() = Unit
}
