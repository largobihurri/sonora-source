package io.sonora.core.domain.conversion.batch

import io.sonora.core.domain.conversion.ConversionProfile
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.model.BatchSource
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.ConversionBatch
import io.sonora.core.domain.model.PickedSource
import io.sonora.core.domain.port.ConversionBatchRepository
import io.sonora.core.domain.port.ConversionBatchScheduler
import io.sonora.core.domain.port.ConversionOutputRootStore
import io.sonora.core.domain.port.ConversionSourceExpander
import io.sonora.core.domain.port.SourceFolderAccess
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first

sealed interface StartBatchResult {
    /** [skippedFolders] subfolders of the picks could not be read; their files are not in the batch. */
    data class Started(val batchId: Long, val skippedFolders: Int = 0) : StartBatchResult

    /** The user has not chosen where converted files go ([ConversionOutputRootStore.set]). */
    data object NoOutputFolder : StartBatchResult

    /** Nothing picked is a supported audio file. */
    data object NoAudioFiles : StartBatchResult
}

/**
 * Starts a converter batch from the user's picks: expands them to source files, stores the batch (named after the
 * current minute) and schedules it. Write access is persisted for every picked folder whatever [overwriteOriginals]
 * says, because the user is asked again after the batch whether to replace the originals. Library songs
 * ([PickedSource.Track]) never ask for access: they keep their folder (so their original can be replaced) only when
 * write access to it is already held, and otherwise convert like files picked one by one.
 *
 * The batch records [profile] as its batch-wide one; each file converts to what `profileFor` gives it (by default
 * that same profile), so one batch can hold several output formats.
 */
class StartConversionBatch(
    private val expander: ConversionSourceExpander,
    private val repository: ConversionBatchRepository,
    private val outputRoot: ConversionOutputRootStore,
    private val sourceAccess: SourceFolderAccess,
    private val scheduler: ConversionBatchScheduler,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        picks: List<PickedSource>,
        profile: ConversionProfile,
        mode: PerformanceMode,
        overwriteOriginals: Boolean,
        profileFor: (BatchSource) -> ConversionProfile = { profile },
    ): StartBatchResult {
        if (outputRoot.root.first() == null) return StartBatchResult.NoOutputFolder
        picks.filterIsInstance<PickedSource.Folder>().forEach { sourceAccess.persistWrite(it.uri) }
        val expanded = expander.expand(picks)
        val songs = picks.filterIsInstance<PickedSource.Track>().mapTo(HashSet()) { it.uri }
        val sources = expanded.sources.map { source ->
            val kept = if (source.uri in songs && source.parentUri != null && !canWrite(source.parentUri)) source.copy(parentUri = null) else source
            kept.copy(profileId = profileFor(source).id)
        }
        if (sources.isEmpty()) return StartBatchResult.NoAudioFiles
        val now = clock.millis()
        val batch = ConversionBatch(
            id = 0,
            profileId = profile.id,
            performanceMode = mode,
            createdAtMs = now,
            outputFolderName = BatchNaming.batchFolderName(now, clock.zone),
            outputFolderUri = null,
            overwriteOriginals = overwriteOriginals,
            status = BatchStatus.QUEUED,
        )
        val id = repository.create(batch, sources)
        scheduler.enqueueConversion(id)
        return StartBatchResult.Started(id, expanded.skippedFolders)
    }

    /** A failed check (provider error, revoked access) reads as no write access. */
    private suspend fun canWrite(folderUri: String): Boolean = try {
        sourceAccess.canWrite(folderUri)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}
