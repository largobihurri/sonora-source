package io.sonora.tools.calibration.runner

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.BeatThisChunking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Paths

class CalibrationOptionsTest {

    private val required = listOf("--reference", "/ref", "--model", "/m.onnx", "--out", "/out", "--cache", "/cache")

    @Test
    fun `defaults follow the app's model descriptor`() {
        val options = CalibrationOptions.parse(required)
        assertThat(options.reference).isEqualTo(Paths.get("/ref"))
        assertThat(options.limit).isEqualTo(5)
        assertThat(options.threads).isEqualTo(2)
        assertThat(options.chunkFrames).isEqualTo(HostBeatThisModel.DESCRIPTOR.chunkFrames)
        assertThat(options.borderFrames).isEqualTo(HostBeatThisModel.DESCRIPTOR.borderFrames)
        assertThat(options.tune).isFalse()
    }

    @Test
    fun `the host model chunks like the app, 300 frames with a 24-frame border`() {
        assertThat(HostBeatThisModel.DESCRIPTOR.chunkFrames).isEqualTo(BeatThisChunking.CHUNK_FRAMES)
        assertThat(HostBeatThisModel.DESCRIPTOR.borderFrames).isEqualTo(BeatThisChunking.BORDER_FRAMES)
        assertThat(BeatThisChunking.CHUNK_FRAMES to BeatThisChunking.BORDER_FRAMES).isEqualTo(300 to 24)
    }

    @Test
    fun `values and flags are read`() {
        val options = CalibrationOptions.parse(
            required + listOf("--limit", "30", "--variable", "--chunk-frames", "1500", "--border-frames", "6", "--tune", "--label", "x"),
        )
        assertThat(options.limit).isEqualTo(30)
        assertThat(options.includeVariable).isTrue()
        assertThat(options.chunkFrames).isEqualTo(1_500)
        assertThat(options.borderFrames).isEqualTo(6)
        assertThat(options.tune).isTrue()
        assertThat(options.label).isEqualTo("x")
    }

    @Test
    fun `a missing required path or a dangling value is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { CalibrationOptions.parse(listOf("--reference", "/ref")) }
        assertThrows(IllegalArgumentException::class.java) { CalibrationOptions.parse(required + "--limit") }
        assertThrows(IllegalArgumentException::class.java) { CalibrationOptions.parse(required + "stray") }
    }

    @Test
    fun `an evaluate-only run needs no model`() {
        val noModel = listOf("--reference", "/ref", "--out", "/out", "--cache", "/cache")
        assertThat(CalibrationOptions.parse(noModel + "--evaluate-only").model).isNull()
        assertThrows(IllegalArgumentException::class.java) { CalibrationOptions.parse(noModel) }
    }

    @Test
    fun `the bar phase sweep is a flag`() {
        assertThat(CalibrationOptions.parse(required).barSweep).isFalse()
        assertThat(CalibrationOptions.parse(required + "--bar-sweep").barSweep).isTrue()
    }

    @Test
    fun `the report config is the baseline unless the pre-calibration defaults are asked for`() {
        assertThat(CalibrationOptions.parse(required).config).isEqualTo(CalibrationConfig.BASELINE)
        assertThat(CalibrationOptions.parse(required + listOf("--config", "pre-d1.20")).config).isEqualTo(CalibrationConfig.PRE_D1_20)
        assertThat(CalibrationOptions.parse(required + listOf("--config", "pre-d1.29")).config).isEqualTo(CalibrationConfig.PRE_D1_29)
        assertThrows(IllegalArgumentException::class.java) { CalibrationOptions.parse(required + listOf("--config", "other")) }
    }

    @Test
    fun `each model chunking gets its own cache directory`() {
        val standard = CalibrationOptions.parse(required)
        val upstream = CalibrationOptions.parse(required + listOf("--chunk-frames", "1500", "--border-frames", "6"))
        assertThat(standard.modelCache).isEqualTo(Paths.get("/cache/chunk300-border24"))
        assertThat(upstream.modelCache).isEqualTo(Paths.get("/cache/chunk1500-border6"))
    }

    @Test
    fun `cache names are hashes of the normalised path`() {
        val name = CalibrationRun.cacheName("/Contents/a.mp3")
        assertThat(name).matches("[0-9a-f]{32}\\.scap")
        assertThat(CalibrationRun.cacheName("Contents/a.mp3")).isEqualTo(name)
        assertThat(CalibrationRun.cacheName("Contents/b.mp3")).isNotEqualTo(name)
    }
}
