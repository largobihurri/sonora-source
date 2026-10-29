package io.sonora.core.data.library.duplicates

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.sonora.core.domain.library.duplicates.CheckDuplicates

/**
 * The background duplicate check (the daily scan, or a catch-up for a backlog): one [CheckDuplicates]. When WorkManager
 * stops the work its coroutine is cancelled between files, keeping what was hashed; a failed scan is retried later, up
 * to [MAX_ATTEMPTS] runs, so a lasting failure (folder access revoked, a broken index) stops spending battery until the
 * next daily scan or library scan asks again.
 */
@HiltWorker
class DuplicateCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val check: CheckDuplicates,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = outcome(check(), runAttemptCount)

    companion object {
        /** Runs of one request, the first included, before a failing check gives up. */
        const val MAX_ATTEMPTS = 5

        /** What a check that [succeeded] (or not) on its [attempt] (0 the first time) tells WorkManager. */
        fun outcome(succeeded: Boolean, attempt: Int): Result = when {
            succeeded -> Result.success()
            attempt + 1 < MAX_ATTEMPTS -> Result.retry()
            else -> Result.failure()
        }
    }
}
