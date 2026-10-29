package io.sonora.core.djanalysis

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.AudioId
import org.junit.Test

class DjAnalysisReportTest {

    private val id = AudioId.of(AudioId.PAYLOAD_V1, "0123456789abcdef".repeat(4))

    @Test
    fun `a full analysis prints every stage in pipeline order with the totals`() {
        val report = DjAnalysisReport(
            audioId = id,
            kind = AnalysisKind.FULL,
            audioMs = 361_200,
            stageMs = linkedMapOf(
                AnalysisStage.STORE to 12L,
                AnalysisStage.INFERENCE to 8_000L,
                AnalysisStage.IDENTIFY to 120L,
                AnalysisStage.DECODE to 1_500L,
                AnalysisStage.KEY to 200L,
            ),
            beatSourceId = "beatthis-small0/c462b064-onnxruntime-cpu",
            accelerator = Accelerator.CPU,
            stored = true,
            debuggable = false,
        )

        assertThat(report.line()).isEqualTo(
            "analysed id=ap1:0123456789ab kind=full audio=361.2s total=9832ms speed=36.7x " +
                "source=beatthis-small0/c462b064-onnxruntime-cpu accelerator=CPU stored=true debuggable=false " +
                "IDENTIFY=120 DECODE=1500 INFERENCE=8000 KEY=200 STORE=12",
        )
    }

    @Test
    fun `a waveform-only run has no source, no accelerator and no speed when nothing was timed`() {
        val report = DjAnalysisReport(
            audioId = id,
            kind = AnalysisKind.WAVEFORM_ONLY,
            audioMs = 1_000,
            stageMs = emptyMap(),
            beatSourceId = null,
            accelerator = null,
            stored = false,
            debuggable = true,
        )

        assertThat(report.line()).isEqualTo(
            "analysed id=ap1:0123456789ab kind=waveform_only audio=1.0s total=0ms source=none accelerator=none " +
                "stored=false debuggable=true",
        )
    }
}
