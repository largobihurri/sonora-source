package io.sonora.core.domain.archive

import kotlinx.coroutines.flow.Flow

/** Where a background archive job is. */
sealed interface ArchiveJobState {
    /** Waiting to start (or to run again after the system stopped it). */
    data object Queued : ArchiveJobState

    /** Running; [progress] is `null` before its first report. */
    data class Running(val progress: ArchiveProgress?) : ArchiveJobState

    data class Compressed(val archive: CompressedArchive) : ArchiveJobState

    /** The music is in the new folder [folderUri] (with the manifest, for a Sonora archive), and a scan was asked. */
    data class Extracted(val folderUri: String, val fileCount: Int, val skippedCount: Int) : ArchiveJobState

    data class Failed(val failure: ArchiveFailure) : ArchiveJobState

    data object Cancelled : ArchiveJobState

    /** No job with that id (never started, or forgotten by the system long after it ended). */
    data object Unknown : ArchiveJobState
}

/**
 * Runs archive jobs in the background, as a foreground job with a progress notification and Cancel, so they survive
 * leaving the app. Passwords are kept in memory only, never written anywhere: if the app process dies before an
 * encrypted job ends, the job fails with [ArchiveFailure.PasswordNeededAgain].
 */
interface ArchiveJobScheduler {
    /**
     * Starts compressing [request]; returns the job's id. The scheduler takes [password] over: it keeps the array
     * in memory and wipes it when the job ends, so the caller must not use it afterwards.
     */
    suspend fun compress(request: CompressionRequest, password: CharArray?): String

    /** Starts extracting [archiveUri] into [destinationFolderUri]; returns the job's id. [password] as in [compress]. */
    suspend fun extract(archiveUri: String, destinationFolderUri: String, password: CharArray?): String

    /** Stops the job (its partial output is removed) and forgets its password. */
    suspend fun cancel(jobId: String)

    fun state(jobId: String): Flow<ArchiveJobState>
}
