package io.sonora.core.data.conversion.work

import android.content.Context
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Whether cancelling tells the caller that a running job will end the batch itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkManagerConversionSchedulerTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val scheduler = WorkManagerConversionScheduler(context)
    private val started = CompletableDeferred<Unit>()

    /** What the batch's job does once started: hold until stopped, or ask to run again later. */
    private var retry = false

    private inner class StandInWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            started.complete(Unit)
            if (retry) return Result.retry()
            awaitCancellation()
        }
    }

    @Before
    fun setUp() {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                StandInWorker(appContext, workerParameters)
        }
        val configuration = Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
    }

    private fun states(batchId: Long) =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(ConversionWork.uniqueName(batchId)).get().map { it.state }

    @Test
    fun `cancelling a running job says it will end the batch itself`() = runBlocking<Unit> {
        scheduler.enqueueConversion(7)
        withTimeout(5_000) { started.await() }

        assertThat(scheduler.cancel(7)).isTrue()
        assertThat(states(7)).containsExactly(WorkInfo.State.CANCELLED)
    }

    @Test
    fun `cancelling a job waiting to run again says nobody will end the batch`() = runBlocking<Unit> {
        retry = true
        scheduler.enqueueConversion(8)
        withTimeout(5_000) {
            started.await()
            while (states(8) != listOf(WorkInfo.State.ENQUEUED)) delay(10)
        }

        assertThat(scheduler.cancel(8)).isFalse()
        assertThat(states(8)).containsExactly(WorkInfo.State.CANCELLED)
    }

    @Test
    fun `cancelling a batch without any job says nobody will end it`() = runBlocking<Unit> {
        assertThat(scheduler.cancel(9)).isFalse()
    }
}
