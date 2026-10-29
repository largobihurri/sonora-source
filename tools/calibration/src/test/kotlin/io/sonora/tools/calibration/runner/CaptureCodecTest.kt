package io.sonora.tools.calibration.runner

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djengine.key.ChromaFrames
import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.BandWaveform
import io.sonora.core.djmodel.BeatCandidates
import io.sonora.core.djmodel.DjBeatGrid
import io.sonora.core.djmodel.KeyEstimate
import io.sonora.core.djmodel.OnsetEnvelope
import io.sonora.core.djmodel.TempoSegment
import io.sonora.core.domain.analysis.MusicalKey
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class CaptureCodecTest {

    private fun full() = TrackCapture(
        path = "Contents/A/Café, \"live\".flac",
        format = "flac",
        sampleRateHz = 44_100,
        channelCount = 2,
        durationMs = 312_345.678,
        candidates = BeatCandidates(
            beatsMs = doubleArrayOf(10.0, 480.5, 950.25),
            downbeatsMs = doubleArrayOf(10.0),
            downbeatProbabilities = doubleArrayOf(0.9, Double.NaN, 0.1),
        ),
        envelope = OnsetEnvelope(floatArrayOf(0f, 0.5f, 1f, 0.25f), 500.0),
        chroma = ChromaFrames(FloatArray(72) { it / 72f }, 44_100.0 / 4_096),
        waveform = BandWaveform(
            low = byteArrayOf(1, 2, -1),
            mid = byteArrayOf(3, 4, 5),
            high = byteArrayOf(6, 7, 8),
            peak = byteArrayOf(9, 10, 11),
            version = 1,
        ),
        pipelineGrid = DjBeatGrid(listOf(TempoSegment(10.0, 128.0, 1), TempoSegment(10.0 + 32 * 468.75, 130.0, 3))),
        pipelineKey = KeyEstimate(MusicalKey.A_MINOR, 0.75, "hpcp-edma/1"),
        beatSourceId = "beatthis-small/1",
        stageMs = mapOf(AnalysisStage.DECODE to 1_200L, AnalysisStage.INFERENCE to 20_000L),
        wallMs = 25_000L,
        temperatureC = 81.5,
    )

    @Test
    fun `a full capture survives a round trip`() {
        val original = full()
        val copy = roundTrip(original)

        assertThat(copy.path).isEqualTo(original.path)
        assertThat(copy.format).isEqualTo("flac")
        assertThat(copy.sampleRateHz).isEqualTo(44_100)
        assertThat(copy.channelCount).isEqualTo(2)
        assertThat(copy.durationMs).isEqualTo(312_345.678)
        assertThat(copy.candidates!!.beatsMs().toList()).containsExactly(10.0, 480.5, 950.25).inOrder()
        assertThat(copy.candidates!!.downbeatsMs().toList()).containsExactly(10.0)
        assertThat(copy.candidates!!.downbeatProbabilities()[0]).isEqualTo(0.9)
        assertThat(copy.candidates!!.downbeatProbabilities()[1].isNaN()).isTrue()
        assertThat(copy.envelope!!.values().toList()).containsExactly(0f, 0.5f, 1f, 0.25f).inOrder()
        assertThat(copy.envelope!!.framesPerSecond).isEqualTo(500.0)
        assertThat(copy.chroma!!.frameCount).isEqualTo(2)
        assertThat(copy.chroma!![1, 35]).isEqualTo(71 / 72f)
        assertThat(copy.chroma!!.framesPerSecond).isEqualTo(44_100.0 / 4_096)
        assertThat(copy.waveform).isEqualTo(original.waveform)
        assertThat(copy.pipelineGrid).isEqualTo(original.pipelineGrid)
        assertThat(copy.pipelineKey).isEqualTo(original.pipelineKey)
        assertThat(copy.beatSourceId).isEqualTo("beatthis-small/1")
        assertThat(copy.stageMs).containsExactly(AnalysisStage.DECODE, 1_200L, AnalysisStage.INFERENCE, 20_000L)
        assertThat(copy.wallMs).isEqualTo(25_000L)
        assertThat(copy.temperatureC).isEqualTo(81.5)
    }

    @Test
    fun `missing parts stay missing`() {
        val original = full().copy(candidates = null, envelope = null, chroma = null, pipelineGrid = null, pipelineKey = null)
        val copy = roundTrip(original)
        assertThat(copy.candidates).isNull()
        assertThat(copy.envelope).isNull()
        assertThat(copy.chroma).isNull()
        assertThat(copy.pipelineGrid).isNull()
        assertThat(copy.pipelineKey).isNull()
    }

    @Test
    fun `foreign bytes are rejected`() {
        assertThrows(IOException::class.java) { CaptureCodec.read(ByteArrayInputStream("not a capture".toByteArray())) }
    }

    @Test
    fun `a truncated capture is rejected`() {
        val out = ByteArrayOutputStream()
        CaptureCodec.write(full(), out)
        val bytes = out.toByteArray()
        assertThrows(IOException::class.java) { CaptureCodec.read(ByteArrayInputStream(bytes.copyOf(bytes.size / 2))) }
    }

    private fun roundTrip(capture: TrackCapture): TrackCapture {
        val out = ByteArrayOutputStream()
        CaptureCodec.write(capture, out)
        return CaptureCodec.read(ByteArrayInputStream(out.toByteArray()))
    }
}
