package io.sonora.core.djanalysis

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djengine.DJ_ANALYZER_VERSION
import io.sonora.core.djmodel.DeviceLoad
import io.sonora.core.djmodel.DjAnalysisLimits
import io.sonora.core.djmodel.DjAnalysisPauseSource
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.conversion.ThermalLevel
import org.junit.Test

class DjAnalysisLimitsHolderTest {

    private val holder = DjAnalysisLimitsHolder()
    private val pause: DjAnalysisPauseSource = holder

    @Test
    fun `before anything is published the analysis is not paused`() {
        assertThat(pause.pausedByHeat.value).isFalse()
    }

    @Test
    fun `severe heat pauses the analysis and cooling resumes it`() {
        holder.publish(DjAnalysisLimits.of(PerformanceMode.NORMAL, DeviceLoad(thermalThrottled = true)))
        assertThat(pause.pausedByHeat.value).isTrue()

        holder.publish(DjAnalysisLimits.of(PerformanceMode.NORMAL, DeviceLoad()))
        assertThat(pause.pausedByHeat.value).isFalse()
    }

    @Test
    fun `moderate heat steps the mode down but does not pause`() {
        holder.publish(DjAnalysisLimits.of(PerformanceMode.NORMAL, DeviceLoad(thermalLevel = ThermalLevel.MODERATE)))

        assertThat(pause.pausedByHeat.value).isFalse()
    }

    @Test
    fun `battery saver alone does not pause the track being played`() {
        holder.publish(DjAnalysisLimits.of(PerformanceMode.LOW_RESOURCES, DeviceLoad(batterySaver = true)))

        assertThat(pause.pausedByHeat.value).isFalse()
    }

    @Test
    fun `the current analyser version is the engine's`() {
        assertThat(CurrentDjAnalyzer.version).isEqualTo(DJ_ANALYZER_VERSION)
    }
}
