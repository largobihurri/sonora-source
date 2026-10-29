package io.sonora.core.data.conversion.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.model.BatchProgress
import io.sonora.core.domain.port.ConversionBatchScheduler
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** [ConversionBatchScheduler] on WorkManager: one unique work chain per batch, run by [ConversionBatchWorker]. */
class WorkManagerConversionScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : ConversionBatchScheduler {

    private val workManager get() = WorkManager.getInstance(context)

    override fun enqueueConversion(batchId: Long) = enqueue(batchId, ConversionWork.TASK_CONVERT, ExistingWorkPolicy.KEEP)

    override fun enqueueReplacement(batchId: Long) =
        enqueue(batchId, ConversionWork.TASK_REPLACE, ExistingWorkPolicy.APPEND_OR_REPLACE)

    private fun enqueue(batchId: Long, task: String, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<ConversionBatchWorker>()
            .setInputData(workDataOf(ConversionWork.KEY_BATCH_ID to batchId, ConversionWork.KEY_TASK to task))
            .addTag(ConversionWork.TAG)
            .build()
        workManager.enqueueUniqueWork(ConversionWork.uniqueName(batchId), policy, request)
    }

    /**
     * A job that starts between the check and the cancel is still stopped and ends the batch; the caller may end it
     * too, which is safe because ending a batch twice keeps the first ending and only repeats the clean-up.
     */
    override suspend fun cancel(batchId: Long): Boolean {
        val name = ConversionWork.uniqueName(batchId)
        val running = workManager.getWorkInfosForUniqueWorkFlow(name).first().any { it.state == WorkInfo.State.RUNNING }
        workManager.cancelUniqueWork(name).await()
        return running
    }

    override fun progress(batchId: Long): Flow<BatchProgress?> =
        workManager.getWorkInfosForUniqueWorkFlow(ConversionWork.uniqueName(batchId))
            .map { infos ->
                infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.let { ConversionWork.progressOf(it.progress) }
            }
            .distinctUntilChanged()
}
