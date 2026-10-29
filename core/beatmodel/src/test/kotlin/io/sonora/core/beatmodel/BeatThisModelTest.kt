package io.sonora.core.beatmodel

import com.google.common.truth.Truth.assertThat
import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.BeatThisChunking
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Test

class BeatThisModelTest {

    private val manifest = BeatThisFiles.manifest.readText()

    private fun manifestString(key: String) = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(manifest)!!.groupValues[1]

    @Test
    fun `the pinned digest and size are those of tools beatthis model json`() {
        assertThat(BeatThisModel.SHA256).isEqualTo(manifestString("sha256"))
        assertThat(BeatThisModel.SIZE_BYTES).isEqualTo(Regex("\"sizeBytes\"\\s*:\\s*(\\d+)").find(manifest)!!.groupValues[1].toLong())
        assertThat(manifestString("file")).isEqualTo(BeatThisModel.ASSET_PATH.substringAfterLast('/'))
    }

    @Test
    fun `the descriptor is Beat This on the CPU with the chunking the engine expects`() {
        val descriptor = BeatThisModel.descriptor()

        assertThat(descriptor.id).isEqualTo("beatthis-small0")
        assertThat(descriptor.version).isEqualTo("c462b064-onnxruntime-cpu-c300b24")
        assertThat(descriptor.accelerator).isEqualTo(Accelerator.CPU)
        assertThat(descriptor.chunkFrames).isEqualTo(300)
        assertThat(descriptor.borderFrames).isEqualTo(24)
        assertThat(descriptor.melBinCount).isEqualTo(128)
        assertThat(descriptor.framesPerSecond).isEqualTo(50.0)
        assertThat(descriptor.sampleRateHz).isEqualTo(22_050)
    }

    @Test
    fun `the chunking is the one shared with the calibration`() {
        assertThat(BeatThisModel.CHUNK_FRAMES).isEqualTo(BeatThisChunking.CHUNK_FRAMES)
        assertThat(BeatThisModel.BORDER_FRAMES).isEqualTo(BeatThisChunking.BORDER_FRAMES)
    }

    @Test
    fun `the MIT notice of Beat This and ONNX Runtime ships with the module`() {
        val notice = File("src/main/assets/licences/beatmodel-third-party.txt")

        assertThat(notice.isFile).isTrue()
        val text = notice.readText()
        assertThat(text).contains("Copyright (c) 2024 Institute of Computational Perception, JKU Linz, Austria")
        assertThat(text).contains("Copyright (c) Microsoft Corporation")
        assertThat(text).contains("Permission is hereby granted, free of charge")
        assertThat(text).contains("https://github.com/CPJKU/beat_this")
    }

    @Test
    fun `a model file entry needs a real digest and size`() {
        assertThrows(IllegalArgumentException::class.java) { BeatModelFile("a", "abc", 1) }
        assertThrows(IllegalArgumentException::class.java) { BeatModelFile("a", BeatThisModel.SHA256, 0) }
        assertThrows(IllegalArgumentException::class.java) { BeatModelFile(" ", BeatThisModel.SHA256, 1) }
    }

    @Test
    fun `the default session settings are two threads, the arena on, no memory pattern and no spinning`() {
        // Measured on the host (one 1500-frame chunk): the memory pattern lets the arena grow from 0.8 to 1.4 GB over
        // repeated chunks, and no arena raises the peak to 1.0 GB and the time by half. With 300-frame chunks (D1.26):
        // pattern on grows from 157 to 177 MB, arena off peaks at 211 MB and takes 112 ms instead of 95 ms.
        assertThat(OrtCpuConfig()).isEqualTo(OrtCpuConfig(intraOpThreads = 2, cpuArena = true, memoryPattern = false, allowSpinning = false))
    }

    @Test
    fun `thread counts outside 1 to 4 are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { OrtCpuConfig(intraOpThreads = 0) }
        assertThrows(IllegalArgumentException::class.java) { OrtCpuConfig(intraOpThreads = 5) }
    }
}
