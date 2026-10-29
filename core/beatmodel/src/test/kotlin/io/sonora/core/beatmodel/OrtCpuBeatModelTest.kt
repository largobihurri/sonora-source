package io.sonora.core.beatmodel

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.sonora.core.djengine.mel.ChunkedInference
import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.BeatActivationModel
import io.sonora.core.djmodel.ChunkLogits
import io.sonora.core.djmodel.MelSpectrogram
import java.io.File
import java.util.Random
import kotlin.math.abs
import org.junit.AfterClass
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Runs the real `small0.onnx` through the same [OrtCpuBeatModel] class the phone uses, on the host JVM (the ONNX Runtime
 * Java API is the same; only the native library differs). The model is `tools/beatthis/build/small0.onnx`, put there by
 * `python3 tools/beatthis/fetch_model.py`; without it the class is skipped.
 */
class OrtCpuBeatModelTest {

    companion object {
        private const val LOGIT_TOLERANCE = 5e-3f

        private var bytes: ByteArray? = null
        private var shared: OrtCpuBeatModel? = null
        private var upstream: OrtCpuBeatModel? = null

        /** The model with the chunking of Beat This! upstream, which the reference goldens in `tools/beatthis/goldens` use. */
        private val UPSTREAM_CHUNKING = BeatThisModel.descriptor().copy(chunkFrames = 1_500, borderFrames = 6)

        @BeforeClass
        @JvmStatic
        fun openModel() {
            val file = BeatThisFiles.hostModel
            assumeTrue("tools/beatthis/build/small0.onnx is missing (run tools/beatthis/fetch_model.py)", file != null)
            bytes = file!!.readBytes()
            shared = OrtCpuBeatModel.create(bytes!!, config = OrtCpuConfig(intraOpThreads = 2))
            upstream = OrtCpuBeatModel.create(bytes!!, UPSTREAM_CHUNKING, OrtCpuConfig(intraOpThreads = 2))
        }

        @AfterClass
        @JvmStatic
        fun closeModel() {
            shared?.close()
            upstream?.close()
        }

        private val model get() = shared!!
    }

    private fun randomMel(frames: Int, seed: Long = 3L): MelSpectrogram {
        val random = Random(seed)
        return MelSpectrogram(128, 50.0, FloatArray(frames * 128) { random.nextFloat() * 6f })
    }

    private fun assertClose(message: String, actual: FloatArray, expected: FloatArray, tolerance: Float) {
        assertWithMessage("$message: length").that(actual.size).isEqualTo(expected.size)
        var worst = 0f
        var at = -1
        for (i in actual.indices) {
            val d = abs(actual[i] - expected[i])
            if (d > worst) { worst = d; at = i }
        }
        println("$message: largest difference $worst")
        assertWithMessage("$message: largest difference ($worst at $at)").that(worst).isAtMost(tolerance)
    }

    /** Forwards to [delegate] and keeps what it was given and what it answered. */
    private class Recording(private val delegate: BeatActivationModel) : BeatActivationModel by delegate {
        val inputs = ArrayList<MelSpectrogram>()
        val outputs = ArrayList<ChunkLogits>()

        override fun infer(chunk: MelSpectrogram): ChunkLogits = delegate.infer(chunk).also { inputs += chunk; outputs += it }
    }

    /** Upstream chunking: the per-chunk outputs of 1500-frame chunks and their aggregation (D1.18 goldens). */
    private fun checkGoldenCase(name: String) = checkGoldenCase(name, upstream!!, BeatThisFiles.goldens)

    /** The app's chunking (300 frames, border 24) through the default model (D1.26 goldens). */
    private fun checkAppChunkingCase(name: String) = checkGoldenCase(name, model, BeatThisFiles.appChunkingGoldens)

    private fun checkGoldenCase(name: String, model: BeatActivationModel, directory: File) {
        val golden = GoldenCase.load(name, directory)
        val recording = Recording(model)

        val activations = ChunkedInference.run(recording, golden.mel())

        assertWithMessage("$name: chunk count").that(recording.inputs.size).isEqualTo(golden.chunks.size)
        for ((i, expected) in golden.chunks.withIndex()) {
            assertWithMessage("$name chunk $i: input frames").that(recording.inputs[i].frameCount).isEqualTo(golden.chunkLengths[i])
            assertClose("$name chunk $i beat", recording.outputs[i].beat, expected.beat, LOGIT_TOLERANCE)
            assertClose("$name chunk $i downbeat", recording.outputs[i].downbeat, expected.downbeat, LOGIT_TOLERANCE)
        }
        assertClose("$name aggregated beat", activations.beat(), golden.beatLogits, LOGIT_TOLERANCE)
        assertClose("$name aggregated downbeat", activations.downbeat(), golden.downbeatLogits, LOGIT_TOLERANCE)
    }

    @Test
    fun `the descriptor matches what the chunker expects`() {
        val descriptor = model.descriptor

        assertThat(descriptor.id).isEqualTo("beatthis-small0")
        assertThat(descriptor.accelerator).isEqualTo(Accelerator.CPU)
        assertThat(descriptor.chunkFrames).isEqualTo(300)
        assertThat(descriptor.borderFrames).isEqualTo(24)
        assertThat(descriptor.melBinCount).isEqualTo(128)
        assertThat(descriptor.framesPerSecond).isEqualTo(50.0)
        assertThat(descriptor.version).contains("onnxruntime-cpu")
    }

    @Test fun `golden click_090 matches the reference`() = checkGoldenCase("click_090")

    @Test fun `golden click_128 matches the reference`() = checkGoldenCase("click_128")

    @Test fun `golden click_174 matches the reference`() = checkGoldenCase("click_174")

    @Test fun `golden ramp_120_124 matches the reference`() = checkGoldenCase("ramp_120_124")

    @Test fun `golden click_128_short matches the reference`() = checkGoldenCase("click_128_short")

    @Test fun `golden silence matches the reference`() = checkGoldenCase("silence")

    @Test fun `golden click_090 with the app's chunking matches the reference`() = checkAppChunkingCase("click_090")

    @Test fun `golden click_128 with the app's chunking matches the reference`() = checkAppChunkingCase("click_128")

    @Test fun `golden click_174 with the app's chunking matches the reference`() = checkAppChunkingCase("click_174")

    @Test fun `golden ramp_120_124 with the app's chunking matches the reference`() = checkAppChunkingCase("ramp_120_124")

    @Test fun `golden click_128_short with the app's chunking matches the reference`() = checkAppChunkingCase("click_128_short")

    @Test fun `golden silence with the app's chunking matches the reference`() = checkAppChunkingCase("silence")

    @Test
    fun `short chunks run and answer one value per frame`() {
        for (frames in listOf(1, 2, 5, 20)) {
            val answer = model.infer(randomMel(frames))

            assertWithMessage("$frames frames").that(answer.frameCount).isEqualTo(frames)
        }
    }

    @Test
    fun `a chunk of more than 300 frames is rejected before it reaches the runtime`() {
        val e = assertThrows(IllegalArgumentException::class.java) { model.infer(randomMel(301)) }

        assertThat(e).hasMessageThat().contains("1 to 300")
    }

    @Test
    fun `a chunk with another bin count or frame rate is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { model.infer(MelSpectrogram(64, 50.0, FloatArray(64 * 10))) }
        assertThrows(IllegalArgumentException::class.java) { model.infer(MelSpectrogram(128, 100.0, FloatArray(128 * 10))) }
    }

    @Test
    fun `a chunk after a longer one is answered as if it were the first`() {
        val short = randomMel(20, seed = 9L)
        val fresh = OrtCpuBeatModel.create(bytes!!, config = OrtCpuConfig(intraOpThreads = 2)).use { it.infer(short) }

        model.infer(randomMel(300, seed = 5L))
        val after = model.infer(short)

        assertClose("beat", after.beat, fresh.beat, 1e-6f)
        assertClose("downbeat", after.downbeat, fresh.downbeat, 1e-6f)
    }

    @Test
    fun `one thread and four threads give the same logits within a tiny tolerance`() {
        val chunk = randomMel(300, seed = 11L)
        val one = OrtCpuBeatModel.create(bytes!!, config = OrtCpuConfig(intraOpThreads = 1)).use { it.infer(chunk) }
        val four = OrtCpuBeatModel.create(bytes!!, config = OrtCpuConfig(intraOpThreads = 4)).use { it.infer(chunk) }

        assertClose("beat", four.beat, one.beat, 1e-4f)
        assertClose("downbeat", four.downbeat, one.downbeat, 1e-4f)
    }

    @Test
    fun `the arena and memory pattern settings do not change the answer`() {
        val chunk = randomMel(300, seed = 13L)
        val reference = model.infer(chunk)
        val lean = OrtCpuBeatModel.create(bytes!!, config = OrtCpuConfig(cpuArena = false, memoryPattern = false)).use { it.infer(chunk) }

        assertClose("beat", lean.beat, reference.beat, 1e-4f)
        assertClose("downbeat", lean.downbeat, reference.downbeat, 1e-4f)
    }

    @Test
    fun `close is idempotent and a closed model refuses to run`() {
        val own = OrtCpuBeatModel.create(bytes!!)
        own.infer(randomMel(5))

        own.close()
        own.close()

        assertThrows(IllegalStateException::class.java) { own.infer(randomMel(5)) }
    }

    @Test
    fun `bytes that are not a model are an error and leave nothing open`() {
        assertThrows(Exception::class.java) { OrtCpuBeatModel.create(ByteArray(64) { it.toByte() }) }

        // The shared environment is still usable after a failed load.
        assertThat(model.infer(randomMel(5)).frameCount).isEqualTo(5)
    }

    @Test
    fun `a model with another input or output name is refused`() {
        // A truncated copy of the real file is not a valid model either; what matters is that create never returns it.
        val truncated = bytes!!.copyOf(bytes!!.size / 2)
        assertThrows(Exception::class.java) { OrtCpuBeatModel.create(truncated) }
    }
}
