package io.sonora.core.data.conversion.work

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
import io.sonora.core.domain.conversion.batch.ConversionBatchJob
import io.sonora.core.domain.conversion.batch.ConversionBatchRunner
import io.sonora.core.domain.conversion.batch.ReplaceBatchOriginals
import io.sonora.core.domain.model.BatchProgress
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.ConversionBatch
import io.sonora.core.domain.port.ConversionBatchRepository
import kotlinx.coroutines.flow.first

/**
 * Runs one converter batch, or the later replacement of its originals, as a foreground job (mediaProcessing on
 * Android 15+): a thin [ConversionBatchJob.Host] around the domain's [ConversionBatchJob]. Progress goes to WorkInfo
 * progress and the notification; the history is written by the runner.
 *
 * Stopping: the user's Cancel (notification action or UI) and the system's mediaProcessing time limit end the batch,
 * skipping the files left; any other stop (process killed, system pressure) leaves them pending, and WorkManager runs
 * the job again, which resumes the batch. An unexpected failure ends the batch as failed and fails the work.
 */
@HiltWorker
class ConversionBatchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    repository: ConversionBatchRepository,
    runner: ConversionBatchRunner,
    replaceOriginals: ReplaceBatchOriginals,
    private val notifications: ConversionNotifications,
) : CoroutineWorker(context, params) {

    private val job = ConversionBatchJob(repository, runner, replaceOriginals)
    private val batchId: Long get() = inputData.getLong(ConversionWork.KEY_BATCH_ID, -1)
    private val replacing: Boolean get() = inputData.getString(ConversionWork.KEY_TASK) == ConversionWork.TASK_REPLACE

    override suspend fun getForegroundInfo(): ForegroundInfo = notifications.foregroundInfo(batchId, id, null, replacing)

    override suspend fun doWork(): Result = when (job.run(batchId, replacing, host)) {
        ConversionBatchJob.Outcome.SUCCESS -> Result.success()
        ConversionBatchJob.Outcome.FAILURE -> Result.failure()
    }

    private val host = object : ConversionBatchJob.Host {
        override suspend fun publish(progress: BatchProgress) {
            setProgress(ConversionWork.progressData(progress))
        }

        override suspend fun promote(progress: BatchProgress?) = this@ConversionBatchWorker.promote(progress)

        override fun showSummary(batch: ConversionBatch, replacing: Boolean) = notifications.showSummary(batch, replacing)

        override suspend fun endingStatus(): BatchStatus? = this@ConversionBatchWorker.endingStatus()
    }

    /** Shows or updates the foreground notification; without a foreground service the work still runs. */
    private suspend fun promote(progress: BatchProgress?) {
        try {
            setForeground(notifications.foregroundInfo(batchId, id, progress, replacing))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (an IllegalStateException) when started from the background.
            Log.w(TAG, "Batch $batchId runs without a foreground service", e)
        }
    }

    /** How a stop ends the batch: cancelled by the user, out of time, or `null` when the job will run again. */
    private suspend fun endingStatus(): BatchStatus? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return endingStatusFor(stopReason)
        // No stop reason before Android 12 (nor a foreground time limit): a user's cancel shows as cancelled work.
        val state = WorkManager.getInstance(applicationContext).getWorkInfoByIdFlow(id).first()?.state
        return if (state == WorkInfo.State.CANCELLED) BatchStatus.CANCELLED else null
    }

    internal companion object {
        private const val TAG = "SonoraConversion"

        /** How a stop for [stopReason] (Android 12+) ends the batch; `null` when the job will run again. */
        fun endingStatusFor(stopReason: Int): BatchStatus? = when (stopReason) {
            WorkInfo.STOP_REASON_CANCELLED_BY_APP -> BatchStatus.CANCELLED
            WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> BatchStatus.TIMED_OUT
            else -> null
        }
    }
}
