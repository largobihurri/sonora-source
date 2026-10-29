package io.sonora.core.data.archive.work

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import io.sonora.core.domain.archive.ArchiveFailure
import io.sonora.core.domain.archive.ArchiveJobState
import io.sonora.core.domain.archive.ArchiveProgress
import io.sonora.core.domain.archive.CompressedArchive

/** Names and data shared by the archive workers and [WorkManagerArchiveScheduler]. No key ever holds a password. */
internal object ArchiveWork {
    const val KEY_JOB_ID = "job_id"

    /** Whether the job needs a password from [ArchivePasswords]. */
    const val KEY_ENCRYPTED = "encrypted"
    const val KEY_ARCHIVE_URI = "archive_uri"
    const val KEY_DESTINATION_URI = "destination_uri"
    const val TAG = "sonora-archive"

    private const val KEY_ENTRIES_DONE = "entries_done"
    private const val KEY_ENTRIES_TOTAL = "entries_total"
    private const val KEY_BYTES_DONE = "bytes_done"
    private const val KEY_BYTES_TOTAL = "bytes_total"
    private const val KEY_CURRENT = "current"

    private const val KEY_RESULT = "result"
    private const val RESULT_COMPRESSED = "compressed"
    private const val RESULT_EXTRACTED = "extracted"
    private const val KEY_PATH = "path"
    private const val KEY_FILE_NAME = "file_name"
    private const val KEY_SIZE = "size"
    private const val KEY_ENTRY_COUNT = "entry_count"
    private const val KEY_FOLDER_URI = "folder_uri"
    private const val KEY_FILE_COUNT = "file_count"
    private const val KEY_SKIPPED_COUNT = "skipped_count"

    private const val KEY_FAILURE = "failure"
    private const val KEY_NEEDED = "needed_bytes"
    private const val KEY_AVAILABLE = "available_bytes"
    private const val KEY_NAME = "name"

    fun uniqueName(jobId: String) = "archive-$jobId"

    fun compressInput(jobId: String, encrypted: Boolean): Data = workDataOf(KEY_JOB_ID to jobId, KEY_ENCRYPTED to encrypted)

    fun extractInput(jobId: String, archiveUri: String, destinationUri: String, encrypted: Boolean): Data = workDataOf(
        KEY_JOB_ID to jobId,
        KEY_ENCRYPTED to encrypted,
        KEY_ARCHIVE_URI to archiveUri,
        KEY_DESTINATION_URI to destinationUri,
    )

    fun progressData(progress: ArchiveProgress): Data = workDataOf(
        KEY_ENTRIES_DONE to progress.entriesDone,
        KEY_ENTRIES_TOTAL to progress.entriesTotal,
        KEY_BYTES_DONE to progress.bytesDone,
        KEY_BYTES_TOTAL to progress.bytesTotal,
        KEY_CURRENT to progress.currentName,
    )

    /** The progress a running job published, `null` before its first report. */
    fun progressOf(data: Data): ArchiveProgress? {
        if (!data.hasKeyWithValueOfType(KEY_BYTES_TOTAL, Long::class.javaObjectType)) return null
        return ArchiveProgress(
            entriesDone = data.getInt(KEY_ENTRIES_DONE, 0),
            entriesTotal = data.getInt(KEY_ENTRIES_TOTAL, 0),
            bytesDone = data.getLong(KEY_BYTES_DONE, 0),
            bytesTotal = data.getLong(KEY_BYTES_TOTAL, 0),
            currentName = data.getString(KEY_CURRENT),
        )
    }

    /** The output data of a job that ended in [state] ([ArchiveJobState.Compressed], `Extracted` or `Failed`). */
    fun resultData(state: ArchiveJobState): Data = when (state) {
        is ArchiveJobState.Compressed -> workDataOf(
            KEY_RESULT to RESULT_COMPRESSED,
            KEY_PATH to state.archive.path,
            KEY_FILE_NAME to state.archive.fileName,
            KEY_SIZE to state.archive.sizeBytes,
            KEY_ENTRY_COUNT to state.archive.entryCount,
            KEY_ENCRYPTED to state.archive.encrypted,
        )
        is ArchiveJobState.Extracted -> workDataOf(
            KEY_RESULT to RESULT_EXTRACTED,
            KEY_FOLDER_URI to state.folderUri,
            KEY_FILE_COUNT to state.fileCount,
            KEY_SKIPPED_COUNT to state.skippedCount,
        )
        is ArchiveJobState.Failed -> failureData(state.failure)
        else -> Data.EMPTY
    }

    /** The job state for a work in [state] with [output] (when ended) and [progress] (while running). */
    fun stateOf(state: WorkInfo.State, output: Data, progress: Data): ArchiveJobState = when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ArchiveJobState.Queued
        WorkInfo.State.RUNNING -> ArchiveJobState.Running(progressOf(progress))
        WorkInfo.State.CANCELLED -> ArchiveJobState.Cancelled
        WorkInfo.State.FAILED -> ArchiveJobState.Failed(failureOf(output))
        WorkInfo.State.SUCCEEDED -> when (output.getString(KEY_RESULT)) {
            RESULT_COMPRESSED -> ArchiveJobState.Compressed(
                CompressedArchive(
                    path = output.getString(KEY_PATH).orEmpty(),
                    fileName = output.getString(KEY_FILE_NAME).orEmpty(),
                    sizeBytes = output.getLong(KEY_SIZE, 0),
                    entryCount = output.getInt(KEY_ENTRY_COUNT, 0),
                    encrypted = output.getBoolean(KEY_ENCRYPTED, false),
                ),
            )
            RESULT_EXTRACTED -> ArchiveJobState.Extracted(
                folderUri = output.getString(KEY_FOLDER_URI).orEmpty(),
                fileCount = output.getInt(KEY_FILE_COUNT, 0),
                skippedCount = output.getInt(KEY_SKIPPED_COUNT, 0),
            )
            else -> ArchiveJobState.Failed(ArchiveFailure.Unexpected)
        }
    }

    private fun failureData(failure: ArchiveFailure): Data = when (failure) {
        ArchiveFailure.PasswordRequired -> workDataOf(KEY_FAILURE to "password_required")
        ArchiveFailure.WrongPassword -> workDataOf(KEY_FAILURE to "wrong_password")
        ArchiveFailure.PasswordNeededAgain -> workDataOf(KEY_FAILURE to "password_needed_again")
        ArchiveFailure.Damaged -> workDataOf(KEY_FAILURE to "damaged")
        ArchiveFailure.NoMusic -> workDataOf(KEY_FAILURE to "no_music")
        is ArchiveFailure.NotEnoughSpace -> workDataOf(
            KEY_FAILURE to "not_enough_space",
            KEY_NEEDED to failure.neededBytes,
            KEY_AVAILABLE to failure.availableBytes,
        )
        is ArchiveFailure.UnreadableSource -> workDataOf(KEY_FAILURE to "unreadable_source", KEY_NAME to failure.name)
        ArchiveFailure.DestinationUnwritable -> workDataOf(KEY_FAILURE to "destination_unwritable")
        ArchiveFailure.Unexpected -> workDataOf(KEY_FAILURE to "unexpected")
    }

    /** The failure a job reported; [ArchiveFailure.Unexpected] when it reported none (it crashed). */
    private fun failureOf(data: Data): ArchiveFailure = when (data.getString(KEY_FAILURE)) {
        "password_required" -> ArchiveFailure.PasswordRequired
        "wrong_password" -> ArchiveFailure.WrongPassword
        "password_needed_again" -> ArchiveFailure.PasswordNeededAgain
        "damaged" -> ArchiveFailure.Damaged
        "no_music" -> ArchiveFailure.NoMusic
        "not_enough_space" -> ArchiveFailure.NotEnoughSpace(data.getLong(KEY_NEEDED, 0), data.getLong(KEY_AVAILABLE, 0))
        "unreadable_source" -> ArchiveFailure.UnreadableSource(data.getString(KEY_NAME).orEmpty())
        "destination_unwritable" -> ArchiveFailure.DestinationUnwritable
        else -> ArchiveFailure.Unexpected
    }
}

/**
 * Which progress reports reach WorkManager and the notification: the first, then one per [intervalMs]; a new entry
 * (its name shows) comes sooner, but never within [entryIntervalMs] of the last report, so an archive of many small
 * files cannot flood WorkManager's database and the notification (the system drops updates beyond about five a
 * second anyway).
 */
internal class ProgressThrottle(private val intervalMs: Long = 500, private val entryIntervalMs: Long = 200) {
    private var lastAtMs: Long? = null
    private var lastEntries = -1

    fun admit(progress: ArchiveProgress, nowMs: Long): Boolean {
        val last = lastAtMs
        val elapsed = if (last == null) Long.MAX_VALUE else nowMs - last
        val due = elapsed >= intervalMs || (progress.entriesDone != lastEntries && elapsed >= entryIntervalMs)
        if (due) {
            lastAtMs = nowMs
            lastEntries = progress.entriesDone
        }
        return due
    }
}
