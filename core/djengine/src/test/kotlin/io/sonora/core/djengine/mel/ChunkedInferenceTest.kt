package io.sonora.core.djengine.mel

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.sonora.core.djengine.beats.BeatThisCase
import io.sonora.core.djengine.beats.BeatThisGoldens
import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.BeatActivationModel
import io.sonora.core.djmodel.BeatModelDescriptor
import io.sonora.core.djmodel.BeatThisChunking
import io.sonora.core.djmodel.ChunkLogits
import io.sonora.core.djmodel.MelSpectrogram
import java.util.Random
import kotlin.math.abs
import org.junit.Assert.assertThrows
import org.junit.Test

class ChunkedInferenceTest {

    private val descriptor = BeatModelDescriptor(
        id = "fake",
        version = "1",
        sampleRateHz = 22_050,
        melBinCount = 128,
        framesPerSecond = 50.0,
        chunkFrames = 1_500,
        borderFrames = 6,
        accelerator = Accelerator.CPU,
    )

    /** The app's chunking for Beat This! small (D1.26). */
    private val appDescriptor = descriptor.copy(chunkFrames = BeatThisChunking.CHUNK_FRAMES, borderFrames = BeatThisChunking.BORDER_FRAMES)

    /** Records every chunk it is given and answers with [answer]. */
    private abstract inner class RecordingModel(
        override val descriptor: BeatModelDescriptor = this@ChunkedInferenceTest.descriptor,
    ) : BeatActivationModel {
        val chunks = ArrayList<MelSpectrogram>()

        abstract fun answer(index: Int, chunk: MelSpectrogram): ChunkLogits

        override fun infer(chunk: MelSpectrogram): ChunkLogits = answer(chunks.size, chunk).also { chunks += chunk }

        override fun close() = Unit
    }

    private fun model(
        descriptor: BeatModelDescriptor = this.descriptor,
        answer: (Int, MelSpectrogram) -> ChunkLogits,
    ) = object : RecordingModel(descriptor) {
        override fun answer(index: Int, chunk: MelSpectrogram) = answer(index, chunk)
    }

    private fun randomMel(frames: Int, seed: Long = 1L): MelSpectrogram {
        val random = Random(seed)
        return MelSpectrogram(128, 50.0, FloatArray(frames * 128) { random.nextFloat() * 8f + 0.5f })
    }

    /** Frame `j` of the chunk starting at frame [start] of [mel]: zeros outside the piece (the zero padding). */
    private fun expectedChunkValue(mel: MelSpectrogram, start: Int, j: Int, bin: Int): Float =
        if (start + j in 0 until mel.frameCount) mel[start + j, bin] else 0f

    private fun assertChunkContent(mel: MelSpectrogram, chunk: MelSpectrogram, start: Int) {
        for (j in 0 until chunk.frameCount) for (bin in 0 until 128) {
            if (chunk[j, bin] != expectedChunkValue(mel, start, j, bin)) {
                throw AssertionError("chunk at $start, frame $j bin $bin: ${chunk[j, bin]} != ${expectedChunkValue(mel, start, j, bin)}")
            }
        }
    }

    @Test
    fun `a model that echoes bins zero and one is reproduced exactly for every length`() {
        for (frames in listOf(1, 2, 5, 6, 7, 12, 13, 500, 1_487, 1_488, 1_489, 1_494, 1_500, 2_001, 2_976, 3_000, 4_500, 10_000)) {
            val mel = randomMel(frames, seed = frames.toLong())
            val fake = model { _, chunk ->
                ChunkLogits(
                    FloatArray(chunk.frameCount) { chunk[it, 0] },
                    FloatArray(chunk.frameCount) { chunk[it, 1] },
                )
            }

            val result = ChunkedInference.run(fake, mel)

            assertWithMessage("$frames frames").that(result.frameCount).isEqualTo(frames)
            assertThat(result.beat()).isEqualTo(FloatArray(frames) { mel[it, 0] })
            assertThat(result.downbeat()).isEqualTo(FloatArray(frames) { mel[it, 1] })
            assertThat(result.framesPerSecond).isEqualTo(50.0)
        }
    }

    @Test
    fun `chunks are at most 1500 frames, zero padded at the edges and carry the right mel frames`() {
        val cases = mapOf(
            1 to listOf(-6),
            501 to listOf(-6),
            1_488 to listOf(-6),
            1_489 to listOf(-6, -5),
            2_001 to listOf(-6, 507),
            3_000 to listOf(-6, 1_482, 1_506),
            4_464 to listOf(-6, 1_482, 2_970),
        )
        for ((frames, starts) in cases) {
            val mel = randomMel(frames)
            val fake = model { _, chunk -> ChunkLogits(FloatArray(chunk.frameCount), FloatArray(chunk.frameCount)) }

            ChunkedInference.run(fake, mel)

            assertWithMessage("$frames frames: chunk count").that(fake.chunks.size).isEqualTo(starts.size)
            for ((i, start) in starts.withIndex()) {
                val chunk = fake.chunks[i]
                assertThat(chunk.frameCount).isAtMost(1_500)
                assertThat(chunk.binCount).isEqualTo(128)
                // A full chunk is 1500 frames; the last one is padded by up to six zero frames at the end only.
                val expectedLength = minOf(start + 1_500, frames) - maxOf(start, 0) + maxOf(-start, 0) +
                    maxOf(minOf(6, start + 1_500 - frames), 0)
                assertWithMessage("$frames frames, chunk $i length").that(chunk.frameCount).isEqualTo(expectedLength)
                assertChunkContent(mel, chunk, start)
            }
        }
    }

    @Test
    fun `where chunks overlap the earlier chunk wins`() {
        // Each chunk answers with its own index; the owner of frame f is the first chunk whose start + 6 .. start + 1494 holds it.
        val cases = mapOf(
            2_001 to listOf(0 until 1_488 to 0f, 1_488 until 2_001 to 1f),
            1_489 to listOf(0 until 1_488 to 0f, 1_488 until 1_489 to 1f),
            3_000 to listOf(0 until 1_488 to 0f, 1_488 until 2_976 to 1f, 2_976 until 3_000 to 2f),
        )
        for ((frames, owners) in cases) {
            val fake = model { index, chunk -> ChunkLogits(FloatArray(chunk.frameCount) { index.toFloat() }, FloatArray(chunk.frameCount) { -index.toFloat() }) }

            val result = ChunkedInference.run(fake, randomMel(frames))

            val beat = result.beat()
            for ((range, owner) in owners) for (f in range) {
                assertWithMessage("$frames frames, frame $f").that(beat[f]).isEqualTo(owner)
                assertThat(result.downbeat()[f]).isEqualTo(-owner)
            }
        }
    }

    @Test
    fun `chunks are run in order, one at a time`() {
        val order = ArrayList<Int>()
        val fake = model { index, chunk ->
            order += index
            ChunkLogits(FloatArray(chunk.frameCount), FloatArray(chunk.frameCount))
        }

        ChunkedInference.run(fake, randomMel(4_000))

        assertThat(order).isEqualTo(listOf(0, 1, 2))
    }

    @Test
    fun `an empty spectrogram gives empty activations and never calls the model`() {
        val fake = model { _, _ -> error("must not be called") }

        val result = ChunkedInference.run(fake, MelSpectrogram(128, 50.0, FloatArray(0)))

        assertThat(result.frameCount).isEqualTo(0)
        assertThat(fake.chunks).isEmpty()
    }

    @Test
    fun `a spectrogram that does not fit the model is rejected`() {
        val fake = model { _, chunk -> ChunkLogits(FloatArray(chunk.frameCount), FloatArray(chunk.frameCount)) }

        assertThrows(IllegalArgumentException::class.java) { ChunkedInference.run(fake, MelSpectrogram(64, 50.0, FloatArray(64 * 10))) }
        assertThrows(IllegalArgumentException::class.java) { ChunkedInference.run(fake, MelSpectrogram(128, 100.0, FloatArray(128 * 10))) }
    }

    @Test
    fun `a model answering with the wrong number of frames is an error`() {
        val fake = model { _, chunk -> ChunkLogits(FloatArray(chunk.frameCount - 1), FloatArray(chunk.frameCount - 1)) }

        assertThrows(IllegalStateException::class.java) { ChunkedInference.run(fake, randomMel(100)) }
    }

    @Test
    fun `the golden raw chunk outputs stitch into the golden logits within 5e-3`() =
        checkGoldenStitching(descriptor, chunking = null)

    @Test
    fun `with the app's chunking the golden raw chunk outputs stitch into its golden logits within 5e-3`() =
        checkGoldenStitching(appDescriptor, BeatThisGoldens.APP_CHUNKING)

    @Test
    fun `the app's chunking runs 300-frame chunks that step by 252 and overlap by 48`() {
        val fake = model(appDescriptor) { _, chunk -> ChunkLogits(FloatArray(chunk.frameCount), FloatArray(chunk.frameCount)) }

        ChunkedInference.run(fake, randomMel(2_001))

        assertThat(ChunkedInference.chunkStarts(2_001, appDescriptor.chunkFrames, appDescriptor.borderFrames).toList())
            .containsExactly(-24, 228, 480, 732, 984, 1_236, 1_488, 1_725).inOrder()
        assertThat(fake.chunks.map { it.frameCount }).containsExactly(300, 300, 300, 300, 300, 300, 300, 300)
    }

    private fun checkGoldenStitching(descriptor: BeatModelDescriptor, chunking: String?) {
        for (name in BeatThisGoldens.CASES) {
            val case = BeatThisCase.load(name, chunking)
            val mel = LogMelSinkRunner.run(case.audio())
            val fake = model(descriptor) { index, chunk ->
                assertThat(chunk.frameCount).isEqualTo(case.chunkLengths[index])
                assertChunkContent(mel, chunk, case.chunkStarts[index])
                case.chunks[index]
            }

            val result = ChunkedInference.run(fake, mel)

            val golden = BeatThisGoldens.load(name, chunking).activations
            assertWithMessage("$name chunks").that(fake.chunks.size).isEqualTo(case.chunkStarts.size)
            assertWithMessage("$name frames").that(result.frameCount).isEqualTo(golden.frameCount)
            var worst = 0f
            for (f in 0 until golden.frameCount) {
                worst = maxOf(worst, abs(result.beat()[f] - golden.beat()[f]), abs(result.downbeat()[f] - golden.downbeat()[f]))
            }
            assertWithMessage("$name worst difference").that(worst).isLessThan(5e-3f)
        }
    }
}

/** Runs the whole mel sink over an array. */
private object LogMelSinkRunner {
    fun run(samples: FloatArray): MelSpectrogram {
        val sink = LogMelSink().create()
        sink.accept(samples, 0, samples.size)
        return sink.finish()
    }
}
