package io.sonora.core.djanalysis

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.DeviceLoad
import io.sonora.core.djmodel.DjAnalysisBudgetSource
import io.sonora.core.djmodel.DjAnalysisLimits
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.conversion.ThermalLevel
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Test

class AnalysisThrottleTest {

    private val hot = DeviceLoad(thermalThrottled = true, thermalLevel = ThermalLevel.SEVERE)
    private var limits = DjAnalysisLimits.of(PerformanceMode.NORMAL, DeviceLoad())
    private val source = DjAnalysisBudgetSource { limits }
    private val priorities = ArrayList<Int>()
    private val sleeps = ArrayList<Long>()
    private var onSleep: () -> Unit = {}
    private val throttle = AnalysisThrottle(source, setPriority = { priorities += it }, sleep = { sleeps += it; onSleep() })

    @Test
    fun `a track starts at the priority of the current budget`() {
        throttle.beginTrack()
        limits = DjAnalysisLimits.of(PerformanceMode.LOW_RESOURCES, DeviceLoad())
        throttle.beginTrack()
        limits = DjAnalysisLimits.of(PerformanceMode.HIGH_PERFORMANCE, DeviceLoad())
        throttle.beginTrack()

        assertThat(priorities).containsExactly(10, 15, 10).inOrder()
    }

    @Test
    fun `a checkpoint costs nothing while the device is not hot`() {
        var checks = 0

        throttle.checkpoint { checks++ }

        assertThat(checks).isEqualTo(1)
        assertThat(sleeps).isEmpty()
    }

    @Test
    fun `low resources waits between chunks while the device is hot and goes on when it cools`() {
        limits = DjAnalysisLimits.of(PerformanceMode.LOW_RESOURCES, hot)
        var polls = 0
        onSleep = { if (++polls == 3) limits = DjAnalysisLimits.of(PerformanceMode.LOW_RESOURCES, DeviceLoad()) }
        var checks = 0

        throttle.checkpoint { checks++ }

        assertThat(sleeps).containsExactly(AnalysisThrottle.POLL_MS, AnalysisThrottle.POLL_MS, AnalysisThrottle.POLL_MS)
        assertThat(checks).isEqualTo(4) // once on entry and once per wait, so a cancellation is noticed while waiting
    }

    @Test
    fun `a cancellation ends the wait`() {
        limits = DjAnalysisLimits.of(PerformanceMode.LOW_RESOURCES, hot)
        var checks = 0

        assertThrows(CancellationException::class.java) {
            throttle.checkpoint { if (++checks == 3) throw CancellationException("cancelled") }
        }

        assertThat(sleeps).hasSize(2)
    }

    @Test
    fun `without a budget source nothing is throttled`() {
        val unlimited = AnalysisThrottle.UNLIMITED

        unlimited.beginTrack()
        unlimited.checkpoint { }
    }
}
