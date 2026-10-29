package io.sonora.core.beatmodel

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.DeviceLoad
import io.sonora.core.djmodel.DjAnalysisBudgetSource
import io.sonora.core.djmodel.DjAnalysisLimits
import io.sonora.core.domain.conversion.PerformanceMode
import org.junit.Assert.assertThrows
import org.junit.Test

class BeatModelLimitsTest {

    private val base = OrtCpuConfig(intraOpThreads = 2, cpuArena = false, memoryPattern = true, allowSpinning = true)

    @Test
    fun `without a budget the configured threads are used`() {
        assertThat(OrtBeatModelFactory.configFor(base, null)).isEqualTo(base)
    }

    @Test
    fun `the budget sets the threads of the session and nothing else`() {
        for (mode in PerformanceMode.entries) {
            val limits = DjAnalysisLimits.of(mode, DeviceLoad())

            val config = OrtBeatModelFactory.configFor(base, DjAnalysisBudgetSource { limits })

            assertThat(config).isEqualTo(base.copy(intraOpThreads = limits.budget.intraOpThreads))
        }
    }

    @Test
    fun `the budget is read again for every session, so a change applies to the next track`() {
        var limits = DjAnalysisLimits.of(PerformanceMode.NORMAL, DeviceLoad())
        val source = DjAnalysisBudgetSource { limits }

        assertThat(OrtBeatModelFactory.configFor(base, source).intraOpThreads).isEqualTo(2)
        limits = DjAnalysisLimits.of(PerformanceMode.HIGH_PERFORMANCE, DeviceLoad())
        assertThat(OrtBeatModelFactory.configFor(base, source).intraOpThreads).isEqualTo(4)
        limits = DjAnalysisLimits.of(PerformanceMode.LOW_RESOURCES, DeviceLoad())
        assertThat(OrtBeatModelFactory.configFor(base, source).intraOpThreads).isEqualTo(1)
    }

    @Test
    fun `the thread priority is set for the block and put back after it`() {
        var current = 0
        val log = ArrayList<Int>()
        val scope = ThreadPriorityScope(get = { current }, set = { current = it; log += it })

        val result = scope.runAt(15) { current }

        assertThat(result).isEqualTo(15)
        assertThat(current).isEqualTo(0)
        assertThat(log).containsExactly(15, 0).inOrder()
    }

    @Test
    fun `the thread priority is put back when the block throws`() {
        var current = 10
        val scope = ThreadPriorityScope(get = { current }, set = { current = it })

        assertThrows(IllegalStateException::class.java) { scope.runAt(15) { error("boom") } }

        assertThat(current).isEqualTo(10)
    }

    @Test
    fun `no scope changes nothing`() {
        assertThat(ThreadPriorityScope.NONE.runAt(19) { "ran" }).isEqualTo("ran")
    }
}
