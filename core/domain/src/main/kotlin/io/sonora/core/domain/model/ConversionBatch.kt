package io.sonora.core.domain.model

import io.sonora.core.domain.conversion.PerformanceMode

/** Where a batch stands. Every state but [QUEUED] and [RUNNING] is final. */
enum class BatchStatus {
    QUEUED,
    RUNNING,
    COMPLETED,

    /** The user cancelled; files not converted yet are [BatchItemState.SKIPPED]. */
    CANCELLED,

    /** The system's foreground time limit for media processing ran out; files not converted yet are skipped. */
    TIMED_OUT,

    /** The batch could not run at all (no output folder, access revoked); see [ConversionBatch.failureReason]. */
    FAILED,
    ;

    val isFinal: Boolean get() = this != QUEUED && this != RUNNING
}

/** Where one file of a batch stands. */
enum class BatchItemState {
    PENDING,
    RUNNING,

    /** Converted and verified; the output is in the batch folder. */
    DONE,

    /**
     * See [ConversionBatchItem.failureReason]. A file whose conversion worked but whose original could not be
     * replaced keeps its output in the batch folder and on record ([ConversionBatchItem.outputUri]), so it still
     * counts as converted ([BatchCounts.notReplaced]) and can be replaced again.
     */
    FAILED,

    /** Not converted because the batch was cancelled or timed out. */
    SKIPPED,

    /** The verified output replaced the original: it now sits next to where the original was. */
    REPLACED_ORIGINAL,
    ;

    val isFinished: Boolean get() = this != PENDING && this != RUNNING
}

/**
 * A converter run: many sources, one dated output folder. Each file converts to its own profile
 * ([ConversionBatchItem.profileId]); [profileId] is the batch-wide one the user chose.
 */
data class ConversionBatch(
    val id: Long,
    /** The batch-wide [io.sonora.core.domain.conversion.ConversionProfile.id]. */
    val profileId: String,
    val performanceMode: PerformanceMode,
    val createdAtMs: Long,
    /** Name of the batch's dated folder inside "Convertidos", e.g. "2026-10-03 14.05". */
    val outputFolderName: String,
    /** Document URI of that folder once created; `null` until the batch starts. */
    val outputFolderUri: String?,
    val overwriteOriginals: Boolean,
    val status: BatchStatus,
    val failureReason: String? = null,
    val counts: BatchCounts = BatchCounts(),
    /** The distinct profiles of its files, as read from the history; empty for a batch not stored yet. */
    val profileIds: Set<String> = emptySet(),
) {
    /** Its files convert to more than one profile (the history says so). */
    val hasMixedProfiles: Boolean get() = profileIds.size > 1
}

/** How many files of a batch are in each state. */
data class BatchCounts(
    val total: Int = 0,
    val pending: Int = 0,
    val running: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val replacedOriginals: Int = 0,
    /** [ConversionBatchItem.isConverted] files that carry no tags; the UI shows a "converted without tags" badge. */
    val withoutTags: Int = 0,
    /**
     * Files converted and verified whose original could not be replaced: [BatchItemState.FAILED] with their output
     * still in the batch folder and replaceable again ([ConversionBatchItem.canReplaceOriginal]). Counted in [failed]
     * too, since the replacement failed.
     */
    val notReplaced: Int = 0,
) {
    /** Files no longer waiting or running. */
    val finished: Int get() = done + failed + skipped + replacedOriginals

    /** Files converted successfully, wherever they are now (including those whose original could not be replaced). */
    val converted: Int get() = done + replacedOriginals + notReplaced
}

/** One source file of a batch and what became of it. */
data class ConversionBatchItem(
    val id: Long,
    val batchId: Long,
    /** Order within the batch, from 0. */
    val position: Int,
    val sourceUri: String,
    val sourceName: String,
    /**
     * Document URI of the folder holding the source, known when it came from a picked folder; needed to put the
     * converted file next to the original. `null` for individually picked files.
     */
    val sourceParentUri: String?,
    val state: BatchItemState = BatchItemState.PENDING,
    /** Where the converted file is now: in the batch folder, or next to the original after replacing it. */
    val outputUri: String? = null,
    val outputName: String? = null,
    /** Whether the output carries the source's tags; false also when the source had none. */
    val tagsWritten: Boolean = false,
    val failureReason: String? = null,
    /**
     * The original was replaced, but its converted file had to keep a suffixed name ("Name (2).aiff") because the
     * plain name was taken by another file or the storage cannot rename.
     */
    val suffixedName: Boolean = false,
    /** What the conversion produced; kept to verify copies of the output later. */
    val result: ConversionResult? = null,
    /** The [io.sonora.core.domain.conversion.ConversionProfile.id] this file converts to. */
    val profileId: String,
) {
    /**
     * A verified output still in the batch folder, which "replace originals" can move next to its source: a converted
     * file, or one whose replacement failed. Needs [result] to verify the copy next to the original.
     */
    val canReplaceOriginal: Boolean
        get() = (state == BatchItemState.DONE || state == BatchItemState.FAILED) && outputUri != null && result != null

    /** Converted successfully, wherever the output is now: done, replaced, or kept after a failed replacement. */
    val isConverted: Boolean
        get() = state == BatchItemState.DONE || state == BatchItemState.REPLACED_ORIGINAL ||
            (state == BatchItemState.FAILED && canReplaceOriginal)
}

/** A source to convert, found by expanding the user's picks. */
data class BatchSource(
    val uri: String,
    val displayName: String,
    /** See [ConversionBatchItem.sourceParentUri]. */
    val parentUri: String?,
    /** The pick this file came from: the folder for a file found inside one, else the file itself; `null` if unknown. */
    val pickUri: String? = null,
    /** The profile this file converts to ([ConversionBatchItem.profileId]); `null` for the batch-wide one. */
    val profileId: String? = null,
)

/** What the user picked in the converter: a whole folder (SAF tree), single documents, or songs of the library. */
sealed interface PickedSource {
    val uri: String

    data class Folder(override val uri: String) : PickedSource

    /** A document picked on its own in the system picker; its folder is unknown, so it cannot be replaced. */
    data class File(override val uri: String) : PickedSource

    /**
     * A library song, by its document [uri] inside a library folder. Its original can be replaced only when Sonora
     * already holds write access to that folder; picking it never asks for more.
     */
    data class Track(override val uri: String) : PickedSource
}

/** Live progress of a running batch. */
data class BatchProgress(
    val finished: Int,
    val total: Int,
    /** Display name of the file started most recently, while any runs. */
    val currentFileName: String?,
    /** Overall fraction in `[0, 1]`, counting the running files' partial progress. */
    val fraction: Float,
    val failed: Int = 0,
)
