package io.sonora.core.data.library.duplicates

import androidx.work.ListenableWorker.Result
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DuplicateCheckWorkerTest {

    @Test
    fun `a check that worked succeeds, whatever the attempt`() {
        assertThat(DuplicateCheckWorker.outcome(succeeded = true, attempt = 0)).isEqualTo(Result.success())
        assertThat(DuplicateCheckWorker.outcome(succeeded = true, attempt = 10)).isEqualTo(Result.success())
    }

    @Test
    fun `a failed check is retried a few times, then given up until the next trigger`() {
        val last = DuplicateCheckWorker.MAX_ATTEMPTS - 1
        assertThat(DuplicateCheckWorker.outcome(succeeded = false, attempt = 0)).isEqualTo(Result.retry())
        assertThat(DuplicateCheckWorker.outcome(succeeded = false, attempt = last - 1)).isEqualTo(Result.retry())
        assertThat(DuplicateCheckWorker.outcome(succeeded = false, attempt = last)).isEqualTo(Result.failure())
        assertThat(DuplicateCheckWorker.outcome(succeeded = false, attempt = last + 5)).isEqualTo(Result.failure())
    }
}
