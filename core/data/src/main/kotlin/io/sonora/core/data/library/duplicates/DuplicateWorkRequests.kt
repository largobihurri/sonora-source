package io.sonora.core.data.library.duplicates

import androidx.work.Constraints
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import java.util.concurrent.TimeUnit

/** The background duplicate checks' work requests, both run by [DuplicateCheckWorker]. */
object DuplicateWorkRequests {
    const val PERIODIC_NAME = "duplicates-daily"
    const val CATCH_UP_NAME = "duplicates-catch-up"

    /** About daily, only while the phone is idle and charging, with battery and storage to spare: the cheapest time. */
    fun periodic(): PeriodicWorkRequest = PeriodicWorkRequestBuilder<DuplicateCheckWorker>(1, TimeUnit.DAYS)
        .setConstraints(
            Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build(),
        )
        .build()

    /** Once, as soon as the battery is not low: a backlog of songs not checked yet does not wait for the night. */
    fun catchUp(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<DuplicateCheckWorker>()
        .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
        .build()
}
