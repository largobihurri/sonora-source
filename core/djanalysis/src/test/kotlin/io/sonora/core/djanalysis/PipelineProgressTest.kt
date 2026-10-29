package io.sonora.core.djanalysis

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.AnalysisStage
import org.junit.Test

class PipelineProgressTest {

    @Test
    fun `with a model the decode, then inference, then the grid`() {
        assertThat(PipelineProgress.stageAt(0f, withModel = true)).isEqualTo(AnalysisStage.DECODE)
        assertThat(PipelineProgress.stageAt(0.29f, withModel = true)).isEqualTo(AnalysisStage.DECODE)
        assertThat(PipelineProgress.stageAt(0.3f, withModel = true)).isEqualTo(AnalysisStage.INFERENCE)
        assertThat(PipelineProgress.stageAt(0.94f, withModel = true)).isEqualTo(AnalysisStage.INFERENCE)
        assertThat(PipelineProgress.stageAt(0.95f, withModel = true)).isEqualTo(AnalysisStage.GRID)
        assertThat(PipelineProgress.stageAt(1f, withModel = true)).isEqualTo(AnalysisStage.GRID)
    }

    @Test
    fun `without a model the decode, then the grid`() {
        assertThat(PipelineProgress.stageAt(0f, withModel = false)).isEqualTo(AnalysisStage.DECODE)
        assertThat(PipelineProgress.stageAt(0.5f, withModel = false)).isEqualTo(AnalysisStage.DECODE)
        assertThat(PipelineProgress.stageAt(0.949f, withModel = false)).isEqualTo(AnalysisStage.DECODE)
        assertThat(PipelineProgress.stageAt(0.95f, withModel = false)).isEqualTo(AnalysisStage.GRID)
        assertThat(PipelineProgress.stageAt(1f, withModel = false)).isEqualTo(AnalysisStage.GRID)
    }
}
