package io.sonora.core.data.library.scan

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReadRetryTest {

    private val lastFailure = 50_000_000L

    @Test
    fun `retries wait for the retry spacing after the last failure`() {
        assertThat(ReadRetry.retryDue(failures = 1, lastFailureMs = lastFailure, nowMs = lastFailure)).isFalse()
        assertThat(ReadRetry.retryDue(1, lastFailure, lastFailure + ReadRetry.RETRY_SPACING_MS - 1)).isFalse()
        assertThat(ReadRetry.retryDue(1, lastFailure, lastFailure + ReadRetry.RETRY_SPACING_MS)).isTrue()
    }

    @Test
    fun `a clock set back before the last failure makes the next read due at once`() {
        assertThat(ReadRetry.retryDue(failures = 2, lastFailureMs = lastFailure, nowMs = lastFailure - 1)).isTrue()
        assertThat(ReadRetry.retryDue(2, lastFailure, lastFailure - 365L * ReadRetry.REREAD_SPACING_MS)).isTrue()
        assertThat(ReadRetry.rereadDue(ReadRetry.MAX_ATTEMPTS, lastFailure, lastFailure - 1)).isTrue()
        assertThat(ReadRetry.rereadDue(ReadRetry.MAX_ATTEMPTS, lastFailure, lastFailure + ReadRetry.REREAD_SPACING_MS - 1)).isFalse()
    }

    @Test
    fun `a clock set back does not lift the bound on re-reads`() {
        val exhausted = ReadRetry.MAX_ATTEMPTS + ReadRetry.MAX_REREADS

        assertThat(ReadRetry.rereadDue(exhausted, lastFailure, lastFailure - 1)).isFalse()
        assertThat(ReadRetry.rereadDue(ReadRetry.MAX_ATTEMPTS - 1, lastFailure, lastFailure - 1)).isFalse()
    }
}
