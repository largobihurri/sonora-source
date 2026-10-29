package io.sonora.core.data.library.duplicates

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sonora.core.domain.library.duplicates.DuplicateWork
import javax.inject.Inject

/** [DuplicateWork] on WorkManager: unique works, kept when already scheduled or pending. */
class WorkManagerDuplicateWork @Inject constructor(@ApplicationContext private val context: Context) : DuplicateWork {
    private val workManager get() = WorkManager.getInstance(context)

    override fun schedulePeriodic() {
        workManager.enqueueUniquePeriodicWork(DuplicateWorkRequests.PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, DuplicateWorkRequests.periodic())
    }

    override fun catchUp() {
        workManager.enqueueUniqueWork(DuplicateWorkRequests.CATCH_UP_NAME, ExistingWorkPolicy.KEEP, DuplicateWorkRequests.catchUp())
    }
}
