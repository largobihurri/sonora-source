package io.sonora.core.data.archive.work

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.await
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.archive.ArchiveJobScheduler
import io.sonora.core.domain.archive.ArchiveJobState
import io.sonora.core.domain.archive.CompressionRequest
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [ArchiveJobScheduler] on WorkManager: one unique work per job, run by [CompressArchiveWorker] or
 * [ExtractArchiveWorker]. A job's input data holds its id and URIs only (WorkManager stores it in its database);
 * the password goes to [ArchivePasswords] and a compression's songs to the job's file in [ArchiveCache].
 */
class WorkManagerArchiveScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val passwords: ArchivePasswords,
    private val cache: ArchiveCache,
) : ArchiveJobScheduler {

    private val workManager get() = WorkManager.getInstance(context)

    override suspend fun compress(request: CompressionRequest, password: CharArray?): String {
        val jobId = UUID.randomUUID().toString()
        withContext(Dispatchers.IO) {
            cache.cleanUp(System.currentTimeMillis())
            cache.writeRequest(jobId, request)
        }
        val encrypted = hold(jobId, password)
        enqueue(jobId, CompressArchiveWorker::class.java, ArchiveWork.compressInput(jobId, encrypted))
        return jobId
    }

    override suspend fun extract(archiveUri: String, destinationFolderUri: String, password: CharArray?): String {
        val jobId = UUID.randomUUID().toString()
        val encrypted = hold(jobId, password)
        enqueue(jobId, ExtractArchiveWorker::class.java, ArchiveWork.extractInput(jobId, archiveUri, destinationFolderUri, encrypted))
        return jobId
    }

    override suspend fun cancel(jobId: String) {
        workManager.cancelUniqueWork(ArchiveWork.uniqueName(jobId)).await()
        passwords.remove(jobId)
    }

    override fun state(jobId: String): Flow<ArchiveJobState> =
        workManager.getWorkInfosForUniqueWorkFlow(ArchiveWork.uniqueName(jobId))
            .map { infos ->
                val info = infos.lastOrNull() ?: return@map ArchiveJobState.Unknown
                ArchiveWork.stateOf(info.state, info.outputData, info.progress)
            }
            .distinctUntilChanged()

    /** Keeps a non-empty [password] in memory for [jobId]; true when the job has one. */
    private fun hold(jobId: String, password: CharArray?): Boolean {
        if (password == null || password.isEmpty()) return false
        passwords.put(jobId, password)
        return true
    }

    /**
     * Enqueues the job and waits until WorkManager has recorded it, so [state] knows the job as soon as its id is
     * returned (an unknown job then means a lost one) and a failed enqueue reaches the caller instead of a job id
     * that never runs; that failure also forgets the job's password.
     */
    private suspend fun enqueue(jobId: String, worker: Class<out ListenableWorker>, input: Data) {
        val request = OneTimeWorkRequest.Builder(worker)
            .setInputData(input)
            .addTag(ArchiveWork.TAG)
            .build()
        try {
            workManager.enqueueUniqueWork(ArchiveWork.uniqueName(jobId), ExistingWorkPolicy.KEEP, request).await()
        } catch (e: Throwable) {
            passwords.remove(jobId)
            throw e
        }
    }
}
