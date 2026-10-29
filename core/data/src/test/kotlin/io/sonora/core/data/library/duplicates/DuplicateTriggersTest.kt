package io.sonora.core.data.library.duplicates

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.duplicates.BacklogAction
import io.sonora.core.domain.model.ScanProgress
import io.sonora.core.domain.model.ScanSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DuplicateTriggersTest {

    private val scans = MutableStateFlow<ScanProgress>(ScanProgress.Idle)
    private var periodic = 0
    private var reviews = 0
    private val errors = mutableListOf<Throwable>()
    var failing = false

    private fun TestScope.triggers() = DuplicateTriggers(
        scope = backgroundScope,
        libraryScans = scans,
        schedulePeriodic = { periodic++ },
        review = {
            reviews++
            if (failing) error("database closed")
            BacklogAction.NONE
        },
        onError = { errors += it },
    )

    @Test
    fun `starting schedules the daily scan once and reviews the backlog after each library scan`() = runTest(UnconfinedTestDispatcher()) {
        val triggers = triggers()
        triggers.start()
        triggers.start()
        assertThat(periodic).isEqualTo(1)
        assertThat(reviews).isEqualTo(0)
        scans.value = ScanProgress.Scanning("Music", 0, 10)
        scans.value = ScanProgress.Finished(ScanSummary(added = 10))
        assertThat(reviews).isEqualTo(1)
        scans.value = ScanProgress.Scanning("Music", 0, 1)
        scans.value = ScanProgress.Finished(ScanSummary(added = 10))
        assertThat(reviews).isEqualTo(2)
    }

    @Test
    fun `a failing review is reported and the next library scan reviews again`() = runTest(UnconfinedTestDispatcher()) {
        triggers().start()
        failing = true
        scans.value = ScanProgress.Finished(ScanSummary(added = 1))
        failing = false
        scans.value = ScanProgress.Scanning("Music", 0, 1)
        scans.value = ScanProgress.Finished(ScanSummary(added = 2))
        assertThat(reviews).isEqualTo(2)
        assertThat(errors).hasSize(1)
    }
}
