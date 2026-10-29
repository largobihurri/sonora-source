package io.sonora.core.data.archive.work

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.sonora.core.domain.archive.ArchiveCompressor
import io.sonora.core.domain.archive.ArchiveFailure
import io.sonora.core.domain.archive.ArchiveImporter
import io.sonora.core.domain.archive.ArchiveJobState
import io.sonora.core.domain.archive.ArchiveOutcome
import io.sonora.core.domain.archive.ArchiveProgress
import io.sonora.core.domain.port.LibraryScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * What both archive workers share: the job id and password from memory, throttled progress to WorkInfo and the
 * foreground notification, and the ending. Not resumable: a job stopped by the system (not the user) starts over
 * when WorkManager runs it again, and keeps its password for that while the process lives.
 *
 * The password is wiped when the job ends: with a result, or cancelled by the user (in the app or from the
 * notification). Only a job the system stopped to run again later keeps it, in memory.
 */
abstract class ArchiveWorker(
    context: Context,
    params: WorkerParameters,
    private val passwords: ArchivePasswords,
    private val notifications: ArchiveNotifications,
    private val task: ArchiveTask,
) : CoroutineWorker(context, params) {

    protected val jobId: String get() = inputData.getString(ArchiveWork.KEY_JOB_ID).orEmpty()
    private val encrypted: Boolean get() = inputData.getBoolean(ArchiveWork.KEY_ENCRYPTED, false)
    private val throttle = ProgressThrottle()

    override suspend fun getForegroundInfo(): ForegroundInfo = notifications.foregroundInfo(jobId, id, task, null)

    final override suspend fun doWork(): Result {
        val password = passwords.get(jobId)
        if (encrypted && password == null) return finish(ArchiveJobState.Failed(ArchiveFailure.PasswordNeededAgain))
        promote(null)
        val state = try {
            run(password)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { if (cancelledByUser()) passwords.remove(jobId) }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Archive job $jobId failed", e)
            ArchiveJobState.Failed(ArchiveFailure.Unexpected)
        }
        return finish(state)
    }

    /** Runs the job with [password] (`null` for none) and tells how it ended. */
    protected abstract suspend fun run(password: CharArray?): ArchiveJobState

    /** Reports [progress] to WorkInfo and the notification, at most a few times per second. */
    protected suspend fun publish(progress: ArchiveProgress) {
        if (!throttle.admit(progress, System.nanoTime() / NANOS_PER_MILLI)) return
        setProgress(ArchiveWork.progressData(progress))
        promote(progress)
    }

    private fun finish(state: ArchiveJobState): Result {
        passwords.remove(jobId)
        notifications.showResult(jobId, task, state)
        val output = ArchiveWork.resultData(state)
        return if (state is ArchiveJobState.Failed) Result.failure(output) else Result.success(output)
    }

    /** Whether the job was stopped by a cancel (the app's or the notification's), not by the system to run again. */
    private suspend fun cancelledByUser(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP
        return WorkManager.getInstance(applicationContext).getWorkInfoByIdFlow(id).first()?.state == WorkInfo.State.CANCELLED
    }

    /** Shows or updates the foreground notification; without a foreground service the job still runs. */
    private suspend fun promote(progress: ArchiveProgress?) {
        try {
            setForeground(notifications.foregroundInfo(jobId, id, task, progress))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (an IllegalStateException) when started from the background.
            Log.w(TAG, "Archive job $jobId runs without a foreground service", e)
        }
    }

    private companion object {
        const val TAG = "SonoraArchive"
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** Packs a [io.sonora.core.domain.archive.CompressionRequest] stored by the scheduler into the job's cache folder. */
@HiltWorker
class CompressArchiveWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val compressor: ArchiveCompressor,
    passwords: ArchivePasswords,
    private val cache: ArchiveCache,
    notifications: ArchiveNotifications,
) : ArchiveWorker(context, params, passwords, notifications, ArchiveTask.COMPRESS) {

    override suspend fun run(password: CharArray?): ArchiveJobState {
        val request = withContext(Dispatchers.IO) {
            cache.cleanUp(System.currentTimeMillis(), keep = setOf(jobId))
            cache.readRequest(jobId)
        } ?: return ArchiveJobState.Failed(ArchiveFailure.UnreadableSource(""))
        val output = cache.archiveFile(jobId, request.name)
        return when (val outcome = compressor.compress(request, password, output.path) { publish(it) }) {
            is ArchiveOutcome.Done -> ArchiveJobState.Compressed(outcome.value)
            is ArchiveOutcome.Failed -> ArchiveJobState.Failed(outcome.failure)
        }
    }
}

/** Extracts an archive into a shared-storage folder, then asks the library for a scan to pick the music up. */
@HiltWorker
class ExtractArchiveWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val importer: ArchiveImporter,
    passwords: ArchivePasswords,
    notifications: ArchiveNotifications,
    private val scanner: LibraryScanner,
) : ArchiveWorker(context, params, passwords, notifications, ArchiveTask.EXTRACT) {

    override suspend fun run(password: CharArray?): ArchiveJobState {
        val archiveUri = inputData.getString(ArchiveWork.KEY_ARCHIVE_URI).orEmpty()
        val destination = inputData.getString(ArchiveWork.KEY_DESTINATION_URI).orEmpty()
        return when (val outcome = importer.extract(archiveUri, destination, password) { publish(it) }) {
            is ArchiveOutcome.Done -> {
                scanner.requestScan()
                val extracted = outcome.value
                ArchiveJobState.Extracted(extracted.folderUri, extracted.files.size, extracted.skippedEntries.size)
            }
            is ArchiveOutcome.Failed -> ArchiveJobState.Failed(outcome.failure)
        }
    }
}
