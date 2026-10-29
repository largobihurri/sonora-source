package io.sonora.core.data.library.duplicates

import androidx.work.NetworkType
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DuplicateWorkRequestsTest {

    @Test
    fun `the daily scan waits for an idle, charging phone with battery and storage to spare`() {
        val spec = DuplicateWorkRequests.periodic().workSpec
        assertThat(spec.intervalDuration).isEqualTo(TimeUnit.DAYS.toMillis(1))
        with(spec.constraints) {
            assertThat(requiresCharging()).isTrue()
            assertThat(requiresDeviceIdle()).isTrue()
            assertThat(requiresBatteryNotLow()).isTrue()
            assertThat(requiresStorageNotLow()).isTrue()
            assertThat(requiredNetworkType).isEqualTo(NetworkType.NOT_REQUIRED)
        }
    }

    @Test
    fun `a catch-up only needs the battery not low`() {
        with(DuplicateWorkRequests.catchUp().workSpec.constraints) {
            assertThat(requiresBatteryNotLow()).isTrue()
            assertThat(requiresCharging()).isFalse()
            assertThat(requiresDeviceIdle()).isFalse()
        }
    }
}
