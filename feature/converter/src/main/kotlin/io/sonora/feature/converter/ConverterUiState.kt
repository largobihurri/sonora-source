package io.sonora.feature.converter

import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.LossyOverwriteCheck
import io.sonora.core.domain.conversion.OutputSelection
import io.sonora.core.domain.conversion.OutputTarget
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.conversion.batch.HistoryGroup
import io.sonora.core.domain.conversion.batch.PendingConversionSources
import io.sonora.core.domain.conversion.batch.HistorySection
import io.sonora.core.domain.model.BatchItemState
import io.sonora.core.domain.model.BatchProgress
import io.sonora.core.domain.model.BatchSource
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.ConversionBatch
import io.sonora.core.domain.model.ConversionBatchItem
import io.sonora.core.domain.model.PickedSource
import io.sonora.core.domain.port.OutputRootFolder

/** A pick waiting for the next batch (shared with the Library's "Convert…", see [PendingConversionSources]). */
typealias PendingSource = io.sonora.core.domain.conversion.batch.PendingSource

/** Why the converter shows an alert. */
enum class ConverterNotice {
    NO_OUTPUT_FOLDER,
    NO_AUDIO_FILES,
    START_FAILED,
    OUTPUT_FOLDER_FAILED,
    PICKER_MISSING,
    CANCEL_FAILED,
    DELETE_FAILED,
    PLAY_FAILED,
}

/** The irreversible "replace originals" confirmation, asked before a batch or after it. */
sealed interface ReplacePrompt {
    /** Turning on "Overwrite originals" for the next batch. */
    data object BeforeBatch : ReplacePrompt

    /**
     * Replacing the originals of batch [batchId]'s [count] verified outputs now, [lossyCount] of them lossless
     * originals a lossy file would replace ([ConverterRules.lossyReplaceableCount]).
     */
    data class AfterBatch(val batchId: Long, val count: Int, val lossyCount: Int) : ReplacePrompt

    /**
     * Starting a batch that overwrites originals in which [count] songs would replace a lossless original with a lossy
     * file ([io.sonora.core.domain.conversion.LossyOverwriteCheck]); confirming starts it.
     */
    data class LossyOverwrite(val count: Int) : ReplacePrompt
}

/** What a picked folder holds, listed when the user opens its row to set its songs one by one. */
sealed interface FolderContents {
    data object Loading : FolderContents

    /** Its audio files, as a batch would take them; [skippedFolders] subfolders could not be read. */
    data class Listed(val files: List<BatchSource>, val skippedFolders: Int) : FolderContents

    /** The folder could not be listed (access revoked, provider error); opening it again retries. */
    data object Failed : FolderContents
}

/** A batch with its files and, while its job (conversion or replacement) runs, live progress. */
data class BatchSnapshot(
    val batch: ConversionBatch,
    val items: List<ConversionBatchItem>,
    val progress: BatchProgress?,
) {
    val isConverting: Boolean get() = !batch.status.isFinal

    /** Replacing originals after the batch ended. */
    val isReplacing: Boolean get() = batch.status.isFinal && progress != null

    val isRunning: Boolean get() = isConverting || isReplacing

    /** Outputs that "Replace originals" can still move next to their sources; none while a job runs. */
    val replaceableCount: Int get() = if (isRunning) 0 else ConverterRules.replaceableCount(items)

    val replacedItems: List<ConversionBatchItem> get() = items.filter { it.state == BatchItemState.REPLACED_ORIGINAL }

    /** Converted files on record that can be played ([ConverterRules.isPlayable]). */
    val playableCount: Int get() = items.count(ConverterRules::isPlayable)

    /** Originals already replaced by their converted files, by the batch itself or afterwards. */
    val replacedCount: Int get() = batch.counts.replacedOriginals
}

/** One batch of the history list. */
data class HistoryEntry(val batch: ConversionBatch, val profileName: String)

/** One dated section of the history list. */
data class HistorySectionUi(val section: HistorySection, val entries: List<HistoryEntry>)

data class ConverterUiState(
    /** The batch-wide output and what each source (and each song inside a folder) converts to. */
    val output: OutputSelection<OutputTarget> = OutputSelection(OutputTarget.Xdj700),
    /** The picked folders opened so far, by URI. */
    val folders: Map<String, FolderContents> = emptyMap(),
    /** `null` until the user picks where "Convertidos" goes. */
    val outputFolder: OutputRootFolder? = null,
    val sources: List<PendingSource> = emptyList(),
    val mode: PerformanceMode = PerformanceMode.DEFAULT,
    val overwriteOriginals: Boolean = false,
    /** A Convert tap is being handled (sources are being listed). */
    val starting: Boolean = false,
    /** The batch shown above the form: running, or finished until dismissed. */
    val activeBatch: BatchSnapshot? = null,
    val history: List<HistorySectionUi> = emptyList(),
    /** Subfolders of the picks that could not be read, so the active batch left their files out. */
    val skippedFolders: Int = 0,
) {
    /** One batch at a time: a second foreground job would defeat the performance mode. */
    val canConvert: Boolean
        get() = outputFolder != null && sources.isNotEmpty() && !starting && activeBatch?.isRunning != true

    /** Files picked one by one in the system picker cannot be replaced in place (no access to their folder). */
    val hasIndividualFiles: Boolean get() = sources.any { it.pick is PickedSource.File }
}

/** The converter screen's pure rules, unit-tested. */
object ConverterRules {

    /** Verified outputs whose original sits in a picked folder; individually picked files cannot be replaced. */
    fun replaceableCount(items: List<ConversionBatchItem>): Int =
        items.count { it.canReplaceOriginal && it.sourceParentUri != null }

    /**
     * Of the [replaceableCount] outputs, those that would replace a lossless original with a lossy file. A batch keeps
     * no codec, so the original is judged by its name ([LossyOverwriteCheck.isLosslessSource]); an output whose profile
     * this version does not know is not counted.
     */
    fun lossyReplaceableCount(items: List<ConversionBatchItem>): Int = items.count { item ->
        item.canReplaceOriginal && item.sourceParentUri != null &&
            ConversionProfiles.byId(item.profileId)?.isLossy == true &&
            LossyOverwriteCheck.isLosslessSource(item.sourceName, codec = null)
    }

    /** A converted file with its output on record (wherever it is now); failed, skipped or pending files are not. */
    fun isPlayable(item: ConversionBatchItem): Boolean = item.isConverted && item.outputUri != null

    /**
     * Whether a batch's ending is settled: it ended, its job is gone (no progress), and every file's outcome is in.
     * Until then what can be replaced is not known yet.
     */
    fun hasSettled(snapshot: BatchSnapshot): Boolean =
        snapshot.batch.status.isFinal && snapshot.progress == null && snapshot.items.all { it.state.isFinished }

    /**
     * Whether a batch that just finished asks "Replace originals now?": only when it did not replace them itself,
     * ended on its own (a cancelled batch is left alone; its summary still offers the action) and has outputs to move.
     */
    fun asksToReplace(snapshot: BatchSnapshot): Boolean {
        val batch = snapshot.batch
        return !batch.overwriteOriginals &&
            (batch.status == BatchStatus.COMPLETED || batch.status == BatchStatus.TIMED_OUT) &&
            snapshot.replaceableCount > 0
    }

    /** A profile this version does not know (history written by a newer one) shows its id. */
    fun profileName(profileId: String): String = ConversionProfiles.byId(profileId)?.displayName ?: profileId

    /**
     * The format a batch shows: the one its files share (which may not be the batch-wide one when every song was set by
     * hand), the batch-wide one while its files are not on record, and `null` when they convert to several.
     */
    fun batchProfileId(batch: ConversionBatch): String? =
        if (batch.hasMixedProfiles) null else batch.profileIds.singleOrNull() ?: batch.profileId

    fun historySections(groups: List<HistoryGroup>): List<HistorySectionUi> = groups.map { group ->
        HistorySectionUi(group.section, group.batches.map { HistoryEntry(it, profileName(it.profileId)) })
    }
}
