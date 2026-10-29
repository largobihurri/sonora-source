package io.sonora.core.djmodel

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.conversion.ThermalLevel
import org.junit.Test

class DjAnalysisBudgetTest {

    @Test
    fun `low resources runs one thread at a low priority and looks at the playing track only`() {
        assertThat(DjAnalysisBudget.of(PerformanceMode.LOW_RESOURCES)).isEqualTo(
            DjAnalysisBudget(PerformanceMode.LOW_RESOURCES, intraOpThreads = 1, threadPriority = 15, lookAhead = 0, pauseWhileHot = true),
        )
    }

    @Test
    fun `normal runs two threads in the background and looks three tracks ahead`() {
        assertThat(DjAnalysisBudget.of(PerformanceMode.NORMAL)).isEqualTo(
            DjAnalysisBudget(PerformanceMode.NORMAL, intraOpThreads = 2, threadPriority = 10, lookAhead = 3, pauseWhileHot = false),
        )
    }

    @Test
    fun `high performance runs four threads at the background priority and looks five tracks ahead`() {
        assertThat(DjAnalysisBudget.of(PerformanceMode.HIGH_PERFORMANCE)).isEqualTo(
            DjAnalysisBudget(PerformanceMode.HIGH_PERFORMANCE, intraOpThreads = 4, threadPriority = 10, lookAhead = 5, pauseWhileHot = false),
        )
    }

    @Test
    fun `every mode gives the beat model a thread count it accepts and never outgrows the look-ahead cap`() {
        for (mode in PerformanceMode.entries) {
            val budget = DjAnalysisBudget.of(mode)
            assertThat(budget.intraOpThreads).isIn(1..4)
            assertThat(budget.lookAhead).isAtMost(DjAnalysisBudget.MAX_LOOK_AHEAD)
        }
        assertThat(PerformanceMode.entries.maxOf { DjAnalysisBudget.of(it).lookAhead }).isEqualTo(DjAnalysisBudget.MAX_LOOK_AHEAD)
    }

    @Test
    fun `more performance never means a lower priority, fewer threads or less look-ahead`() {
        val budgets = PerformanceMode.entries.map { DjAnalysisBudget.of(it) }

        for ((slower, faster) in budgets.zipWithNext()) {
            assertThat(faster.intraOpThreads).isAtLeast(slower.intraOpThreads)
            assertThat(faster.lookAhead).isAtLeast(slower.lookAhead)
            assertThat(faster.threadPriority).isAtMost(slower.threadPriority)
        }
    }

    @Test
    fun `a cool device runs the chosen mode`() {
        for (mode in PerformanceMode.entries) {
            assertThat(DjAnalysisLimits.of(mode, DeviceLoad()).budget.mode).isEqualTo(mode)
        }
    }

    @Test
    fun `battery saver steps the mode down one level`() {
        val saving = DeviceLoad(batterySaver = true)

        assertThat(DjAnalysisLimits.of(PerformanceMode.HIGH_PERFORMANCE, saving).budget.mode).isEqualTo(PerformanceMode.NORMAL)
        assertThat(DjAnalysisLimits.of(PerformanceMode.NORMAL, saving).budget.mode).isEqualTo(PerformanceMode.LOW_RESOURCES)
        assertThat(DjAnalysisLimits.of(PerformanceMode.LOW_RESOURCES, saving).budget.mode).isEqualTo(PerformanceMode.LOW_RESOURCES)
    }

    @Test
    fun `moderate heat steps the mode down one level and heat plus battery saver two`() {
        val warm = DeviceLoad(thermalLevel = ThermalLevel.MODERATE)
        val warmAndSaving = warm.copy(batterySaver = true)

        assertThat(DjAnalysisLimits.of(PerformanceMode.HIGH_PERFORMANCE, warm).budget.mode).isEqualTo(PerformanceMode.NORMAL)
        assertThat(DjAnalysisLimits.of(PerformanceMode.HIGH_PERFORMANCE, warmAndSaving).budget.mode).isEqualTo(PerformanceMode.LOW_RESOURCES)
        assertThat(DjAnalysisLimits.of(PerformanceMode.NORMAL, warm).budget.mode).isEqualTo(PerformanceMode.LOW_RESOURCES)
    }

    @Test
    fun `light heat does not change the mode`() {
        val light = DeviceLoad(thermalLevel = ThermalLevel.LIGHT)

        assertThat(DjAnalysisLimits.of(PerformanceMode.HIGH_PERFORMANCE, light).budget.mode).isEqualTo(PerformanceMode.HIGH_PERFORMANCE)
    }

    @Test
    fun `severe heat forces low resources, whichever way it is reported`() {
        for (mode in PerformanceMode.entries) {
            assertThat(DjAnalysisLimits.of(mode, DeviceLoad(thermalLevel = ThermalLevel.SEVERE)).budget.mode)
                .isEqualTo(PerformanceMode.LOW_RESOURCES)
            assertThat(DjAnalysisLimits.of(mode, DeviceLoad(thermalThrottled = true)).budget.mode)
                .isEqualTo(PerformanceMode.LOW_RESOURCES)
        }
    }

    @Test
    fun `low resources waits while the device is hot, the other modes do not`() {
        val hot = DeviceLoad(thermalThrottled = true, thermalLevel = ThermalLevel.SEVERE)

        assertThat(DjAnalysisLimits.of(PerformanceMode.HIGH_PERFORMANCE, hot).pauseNow).isTrue() // severe heat made it low
        assertThat(DjAnalysisLimits.of(PerformanceMode.LOW_RESOURCES, DeviceLoad()).pauseNow).isFalse()
        assertThat(DjAnalysisLimits.of(PerformanceMode.LOW_RESOURCES, DeviceLoad(thermalLevel = ThermalLevel.MODERATE)).pauseNow).isFalse()
        assertThat(DjAnalysisLimits(DjAnalysisBudget.of(PerformanceMode.NORMAL), hot).pauseNow).isFalse()
    }

    @Test
    fun `a thermal level of severe or worse counts as throttled`() {
        assertThat(DeviceLoad(thermalLevel = ThermalLevel.SEVERE).hot).isTrue()
        assertThat(DeviceLoad(thermalThrottled = true).hot).isTrue()
        assertThat(DeviceLoad(thermalLevel = ThermalLevel.MODERATE).hot).isFalse()
        assertThat(DeviceLoad().hot).isFalse()
    }
}
