package io.sonora.core.domain.conversion.batch

import io.sonora.core.domain.audio.PcmDecodeException
import io.sonora.core.domain.conversion.ConversionProfile
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.model.BatchItemState
import io.sonora.core.domain.model.BatchProgress
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.ConversionBatch
import io.sonora.core.domain.model.ConversionBatchItem
import io.sonora.core.domain.model.ConversionRequest
import io.sonora.core.domain.model.ConversionResult
import io.sonora.core.domain.model.OutputVerification
import io.sonora.core.domain.port.AudioConverter
import io.sonora.core.domain.port.ConversionBatchRepository
import io.sonora.core.domain.port.ConversionBudgetSource
import io.sonora.core.domain.port.ConversionFiles
import io.sonora.core.domain.port.ConversionOutputRootStore
import io.sonora.core.domain.port.StoredDocument
import io.sonora.core.domain.port.UnsupportedOutputException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs a stored batch (the background job calls it): creates the batch's dated folder in "Convertidos", converts
 * every pending file into it and, for batches that overwrite originals, moves each verified output next to its
 * original through [OriginalReplacer].
 *
 * Each file converts to its own profile ([ConversionBatchItem.profileId]); one whose profile is unknown, or whose
 * format nothing in this build writes ([UnsupportedOutputException]), fails alone. Each output is written as
 * "Name.aiff.part", verified to decode, then renamed to its final, collision-free,
 * FAT-safe name. Files run in parallel up to the live budget's concurrency, read again before each file, with the
 * budget's pause between files; the converter applies the budget's block size and thread priority.
 *
 * Cancelling the calling coroutine deletes the part files being written and leaves those files pending, so a run
 * restarted by the system resumes where it stopped; [finishInterrupted] ends a batch the user cancelled or the system
 * stopped for good.
 *
 * A file that shows no sign of life (a finished step, decoding progress) for [stallTimeout] is given up as failed, so
 * one stuck storage, tag or codec call can never hold the batch forever (T53). Such calls often block a thread and
 * ignore cancellation, so the batch does not wait for them: each file runs apart from the batch's own coroutines,
 * and a file given up (or still busy [CANCEL_GRACE] after a cancel) no longer writes to the history if it ever ends,
 * except a replacement of its original already under way, which still records where the converted file went.
 */
class ConversionBatchRunner(
    private val repository: ConversionBatchRepository,
    private val files: ConversionFiles,
    private val outputRoot: ConversionOutputRootStore,
    private val converter: AudioConverter,
    private val budgets: ConversionBudgetSource,
    private val replacer: OriginalReplacer,
    private val stallTimeout: Duration = DEFAULT_STALL_TIMEOUT,
) {

    /**
     * Runs batch [batchId] to the end and returns it; `null` when it does not exist. Final batches are returned as is,
     * and so is a batch cancelled before this run could claim it.
     */
    suspend fun run(batchId: Long, onProgress: suspend (BatchProgress) -> Unit = {}): ConversionBatch? {
        val stored = repository.getBatch(batchId) ?: return null
        if (stored.status.isFinal) return stored
        // Claimed first: a cancel racing this start either wins (the batch is final now) or leaves it to this job.
        if (!repository.transition(batchId, ACTIVE, BatchStatus.RUNNING)) return repository.getBatch(batchId)
        val claimed = stored.copy(status = BatchStatus.RUNNING, failureReason = null)
        val batch = try {
            prepareFolder(claimed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return finishFailed(batchId, "The output folder is not available (${e.message ?: e.javaClass.simpleName})")
        }
        // Files left running by a run that died (process killed) start over.
        val items = repository.getItems(batchId).map { item ->
            if (item.state != BatchItemState.RUNNING) return@map item
            item.copy(state = BatchItemState.PENDING).also { repository.updateItem(it) }
        }
        val folder = checkNotNull(batch.outputFolderUri)
        Execution(batch, folder, items, onProgress).run()
        // Every file has its outcome now. A cancel landing from here on either ends the batch first (the transition
        // fails and that ending stands; with every file finished it has nothing left to clean) or finds it completed.
        try {
            repository.transition(batchId, setOf(BatchStatus.RUNNING), BatchStatus.COMPLETED)
        } finally {
            // Every output moved next to its original leaves the batch folder empty: it goes too, however the batch
            // ended, even when this job is cancelled just now (the ending's clean-up only handles unfinished files).
            if (batch.overwriteOriginals) withContext(NonCancellable) { replacer.removeIfEmpty(folder) }
        }
        return repository.getBatch(batchId)
    }

    /**
     * Ends a batch that will not run again ([BatchStatus.CANCELLED] by the user or [BatchStatus.TIMED_OUT]): files not
     * converted are skipped and part files are deleted. Call it after the run was cancelled.
     */
    suspend fun finishInterrupted(batchId: Long, status: BatchStatus): ConversionBatch? {
        require(status == BatchStatus.CANCELLED || status == BatchStatus.TIMED_OUT) { "Not an interruption: $status" }
        return end(batchId, ACTIVE, status, null)
    }

    /**
     * Ends a batch whose run broke unexpectedly as [BatchStatus.FAILED] with [reason], so it never stays running:
     * files not converted are skipped and part files are deleted. A batch already ended keeps its ending.
     */
    suspend fun finishFailed(batchId: Long, reason: String): ConversionBatch? = end(batchId, ACTIVE, BatchStatus.FAILED, reason)

    /**
     * Cancels batch [batchId] if no run has claimed it yet (it has no folder then); false when a run already claimed
     * it, whose job ends it when stopped, or when it already ended.
     */
    internal suspend fun cancelQueued(batchId: Long): Boolean {
        if (!repository.transition(batchId, setOf(BatchStatus.QUEUED), BatchStatus.CANCELLED)) return false
        skipUnfinished(batchId)
        return true
    }

    /**
     * Moves the batch from [from] to the final [status], then cleans up. The clean-up runs whoever ended the batch, so
     * one cut short (process killed, database error) is completed by the next ending call.
     */
    private suspend fun end(batchId: Long, from: Set<BatchStatus>, status: BatchStatus, reason: String?): ConversionBatch? {
        repository.getBatch(batchId) ?: return null
        repository.transition(batchId, from, status, reason)
        return cleanUpEnded(batchId)
    }

    /**
     * Completes the ending of a batch that stopped before completing (cancelled, timed out, failed): deletes its part
     * files and skips the files left. Does nothing to a batch still active, completed, or already cleaned up.
     */
    internal suspend fun cleanUpEnded(batchId: Long): ConversionBatch? {
        val batch = repository.getBatch(batchId) ?: return null
        if (batch.status !in INTERRUPTED || repository.getItems(batchId).all { it.state.isFinished }) return batch
        batch.outputFolderUri?.let { folder ->
            try {
                deletePartFiles(folder)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The folder is gone or no longer accessible: nothing left to clean.
            }
        }
        skipUnfinished(batchId)
        return repository.getBatch(batchId)
    }

    /** [batch] with its output folder: created on the first run, cleared of part files on a resumed one. */
    private suspend fun prepareFolder(batch: ConversionBatch): ConversionBatch {
        batch.outputFolderUri?.let { folder ->
            deletePartFiles(folder)
            return batch
        }
        val root = outputRoot.root.first() ?: throw IOException("no output folder chosen")
        val converted = files.findOrCreateFolder(root.treeUri, BatchNaming.CONVERTED_FOLDER)
        val name = BatchNaming.uniqueFolderName(batch.outputFolderName, files.children(converted.uri).map { it.name })
        val folder = files.createFolder(converted.uri, name)
        return batch.copy(outputFolderUri = folder.uri, outputFolderName = folder.name).also { repository.updateBatch(it) }
    }

    private suspend fun deletePartFiles(folder: String) {
        files.children(folder).filter { it.name.endsWith(BatchNaming.PARTIAL_SUFFIX) }.forEach { files.delete(it.uri) }
    }

    private suspend fun skipUnfinished(batchId: Long) {
        repository.getItems(batchId).filterNot { it.state.isFinished }.forEach {
            repository.updateItem(it.copy(state = BatchItemState.SKIPPED))
        }
    }

    private inner class Execution(
        private val batch: ConversionBatch,
        private val folder: String,
        private val items: List<ConversionBatchItem>,
        private val onProgress: suspend (BatchProgress) -> Unit,
    ) {
        private val lock = Mutex()
        private val takenNames = mutableSetOf<String>()
        // Written by the converter's threads.
        private val fractions = ConcurrentHashMap<Long, Float>()
        private var finished = items.count { it.state.isFinished }
        private var failed = items.count { it.state == BatchItemState.FAILED }

        /** Names of the files converting now, in the order they started; guarded by [lock]. */
        private val inProgress = LinkedHashMap<Long, String>()

        suspend fun run() = coroutineScope {
            takenNames += files.children(folder).map { it.name }
            // Long files would otherwise show no movement between their start and end.
            val ticker = launch {
                while (true) {
                    delay(PROGRESS_TICK_MS)
                    report {}
                }
            }
            // Files run apart from this scope (see the class comment); a SupervisorJob keeps one file's crash local.
            val apart = CoroutineScope(coroutineContext.minusKey(Job) + SupervisorJob())
            coroutineScope {
                val active = MutableStateFlow(0)
                var started = 0
                for (item in items.filter { it.state == BatchItemState.PENDING }) {
                    val budget = budgets.budgets(batch.performanceMode).first()
                    // A budget source must allow at least one file; zero would wait forever.
                    active.first { it < budget.concurrentFiles.coerceAtLeast(1) }
                    if (started++ > 0 && budget.pauseBetweenFilesMs > 0) delay(budget.pauseBetweenFilesMs)
                    active.update { it + 1 }
                    launch {
                        try {
                            convert(item, apart)
                        } finally {
                            active.update { it - 1 }
                        }
                    }
                }
            }
            ticker.cancel()
        }

        /**
         * Converts [item] on [apart] under a watchdog and records how it ended. Cancelling the caller cancels the
         * conversion, waiting at most [CANCEL_GRACE] for it to clean up.
         */
        private suspend fun convert(item: ConversionBatchItem, apart: CoroutineScope) {
            report { inProgress[item.id] = item.sourceName; fractions[item.id] = 0f }
            val attempt = Attempt(item)
            val ended = CompletableDeferred<ConversionBatchItem>()
            val work = apart.launch {
                try {
                    ended.complete(attempt.run())
                } catch (e: Throwable) {
                    ended.completeExceptionally(e)
                }
            }
            val outcome = coroutineScope {
                val watchdog = launch {
                    attempt.awaitStall()
                    // Decided before the attempt hears of it, so nothing it does afterwards reaches the history.
                    attempt.abandoned = true
                    if (ended.complete(item.failed(stalledReason()))) work.cancel()
                }
                try {
                    ended.await()
                } catch (e: CancellationException) {
                    work.cancel()
                    withContext(NonCancellable) {
                        if (withTimeoutOrNull(minOf(stallTimeout, CANCEL_GRACE)) { work.join() } == null) attempt.abandoned = true
                    }
                    throw e
                } finally {
                    watchdog.cancel()
                }
            }
            // Written even when cancelled meanwhile: the outcome may say where a replaced original's file now is.
            withContext(NonCancellable) { repository.updateItem(outcome) }
            report {
                inProgress -= item.id
                fractions -= item.id
                finished++
                if (outcome.state == BatchItemState.FAILED) failed++
            }
        }

        private fun stalledReason(): String {
            val minutes = stallTimeout.inWholeMinutes
            val silence = if (minutes > 0) "$minutes min" else stallTimeout.toString()
            return "The conversion stopped responding (nothing happened for $silence); the file was given up"
        }

        /** One file's conversion: writes its history only while not [abandoned], and counts its signs of life. */
        private inner class Attempt(private val item: ConversionBatchItem) {
            /** Set once the batch stopped waiting for this file; from then on its history is not this attempt's. */
            @Volatile var abandoned = false

            private val beats = AtomicLong()

            private fun beat() {
                beats.incrementAndGet()
            }

            /**
             * A step ended: a sign of life, and the point where an attempt given up or cancelled meanwhile stops, even
             * when the call it was stuck in ignored cancellation.
             */
            private suspend fun stepDone() {
                currentCoroutineContext().ensureActive()
                beat()
            }

            /** Returns once a whole [stallTimeout] passed without a sign of life. */
            suspend fun awaitStall() {
                var seen = beats.get()
                while (true) {
                    delay(stallTimeout)
                    val now = beats.get()
                    if (now == seen) return
                    seen = now
                }
            }

            private suspend fun record(item: ConversionBatchItem) {
                // The watchdog firing between this check and the write would take a whole silent stallTimeout ending
                // just as this file shows a sign of life; a lock around a write that may itself hang is not worth it.
                if (!abandoned) repository.updateItem(item)
                stepDone()
            }

            suspend fun run(): ConversionBatchItem {
                // Each file converts to its own profile: its extension, encoder, mime type and verification follow it.
                val profile = ConversionProfiles.byId(item.profileId)
                    ?: return item.failed("Unknown conversion profile ${item.profileId}")
                var running = item.copy(state = BatchItemState.RUNNING, failureReason = null)
                var part: StoredDocument? = null
                return try {
                    record(running)
                    // Room for ".part" while it is written.
                    val maxBytes = BatchNaming.MAX_NAME_BYTES - BatchNaming.PARTIAL_SUFFIX.length
                    val name = reserveName(BatchNaming.fatSafe(profile.outputFileName(item.sourceName), maxBytes), maxBytes)
                    part = files.createFile(folder, name + BatchNaming.PARTIAL_SUFFIX, PARTIAL_MIME_TYPE)
                    stepDone()
                    val request = ConversionRequest(item.sourceUri, part.uri, profile, batch.performanceMode)
                    val result = converter.convert(request) { fraction ->
                        // Verifying the output still follows: a converted file does not show as finished yet.
                        if (!abandoned) fractions[item.id] = fraction * CONVERTING_SHARE
                        beat()
                    }
                    stepDone()
                    val output = finishOutput(profile, part, name, result) { stepDone() }
                    part = null
                    when (output) {
                        is Output.Invalid -> item.failed(output.reason)
                        is Output.Verified -> {
                            running = running.copy(
                                state = BatchItemState.DONE,
                                outputUri = output.document.uri,
                                outputName = output.document.name,
                                tagsWritten = result.tagsWritten,
                                result = result,
                            )
                            record(running)
                            if (batch.overwriteOriginals) replacer.replace(profile, running) else running
                        }
                    }
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        part?.let { files.delete(it.uri) }
                        // A converted file stays converted (a replacement records itself); anything else runs again on resume.
                        if (running.state != BatchItemState.DONE) record(item.copy(state = BatchItemState.PENDING))
                    }
                    throw e
                } catch (e: Exception) {
                    part?.let { files.delete(it.uri) }
                    // [running] still holds a verified output when only the replacement failed: keep it on record.
                    running.failed(reasonFor(e))
                }
            }
        }

        /**
         * Gives the verified [part] (written for [profile]) its final [name]; a part that does not verify is deleted. Storage that cannot
         * rename gets a copy under the final name, verified again. The part file is gone when this returns. [step] is
         * called after each step.
         */
        private suspend fun finishOutput(
            profile: ConversionProfile,
            part: StoredDocument,
            name: String,
            result: ConversionResult,
            step: suspend () -> Unit,
        ): Output {
            verify(part.uri, result)?.let { return it }
            step()
            files.rename(part.uri, name)?.let { return Output.Verified(it) }
            step()
            val final = files.createFile(folder, name, profile.container.mimeType)
            try {
                files.copy(part.uri, final.uri)
            } catch (e: Exception) {
                files.delete(final.uri)
                throw e
            } finally {
                files.delete(part.uri)
            }
            step()
            return verify(final.uri, result) ?: Output.Verified(final)
        }

        /** `null` when [uri] decodes as [result] says, else [Output.Invalid] with [uri] deleted. */
        private suspend fun verify(uri: String, result: ConversionResult): Output.Invalid? {
            val verification = converter.verify(uri, result)
            if (verification !is OutputVerification.Invalid) return null
            files.delete(uri)
            return Output.Invalid("The output does not decode (${verification.reason})")
        }

        private suspend fun reserveName(desired: String, maxBytes: Int): String = lock.withLock {
            BatchNaming.uniqueFileName(desired, takenNames, maxBytes).also { takenNames += it }
        }

        private suspend fun report(change: () -> Unit) {
            val progress = lock.withLock {
                change()
                val partial = fractions.values.sum()
                BatchProgress(
                    finished = finished,
                    total = items.size,
                    // The latest file started among those still converting: never one that already finished.
                    currentFileName = inProgress.values.lastOrNull(),
                    fraction = if (items.isEmpty()) 1f else ((finished + partial) / items.size).coerceIn(0f, 1f),
                    failed = failed,
                )
            }
            onProgress(progress)
        }
    }

    private fun ConversionBatchItem.failed(reason: String) = copy(state = BatchItemState.FAILED, failureReason = reason)

    private sealed interface Output {
        data class Verified(val document: StoredDocument) : Output

        data class Invalid(val reason: String) : Output
    }

    companion object {
        private const val PROGRESS_TICK_MS = 1_000L

        /**
         * Longest a file may show no sign of life before it is given up. Steps without progress (tag reading, which may
         * copy the source, flushing and verifying the output) take seconds for ordinary files, minutes at worst for
         * multi-gigabyte ones on slow storage; anything longer is a stuck call, not a slow one.
         */
        val DEFAULT_STALL_TIMEOUT: Duration = 10.minutes

        /** Part of a file's progress bar its conversion fills; verifying and naming the output fill the rest. */
        private const val CONVERTING_SHARE = 0.9f

        /** How long a cancelled batch waits for its files to delete their part files and leave their state. */
        private val CANCEL_GRACE: Duration = 2.seconds

        /** Statuses of a batch that has not ended. */
        private val ACTIVE = setOf(BatchStatus.QUEUED, BatchStatus.RUNNING)

        /** Endings that may leave files unconverted. */
        private val INTERRUPTED = setOf(BatchStatus.CANCELLED, BatchStatus.TIMED_OUT, BatchStatus.FAILED)

        /** Part files are not audio yet; a neutral type keeps providers from appending an extension. */
        const val PARTIAL_MIME_TYPE: String = "application/octet-stream"

        /** A reason the history can show for a file that failed with [e]. */
        fun reasonFor(e: Exception): String = when (e) {
            is PcmDecodeException -> "Cannot decode the source: ${e.message}"
            is UnsupportedOutputException -> e.reason
                ?.let { "This device cannot write ${e.profile.displayName} files: $it" }
                ?: "This version cannot write ${e.profile.displayName} files yet"
            is SecurityException -> "No access to the file (${e.message ?: "permission revoked"})"
            is IOException -> "Cannot read or write the file (${e.message ?: e.javaClass.simpleName})"
            else -> "Conversion failed (${e.message ?: e.javaClass.simpleName})"
        }
    }
}
