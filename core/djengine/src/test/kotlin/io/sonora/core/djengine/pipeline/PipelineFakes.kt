package io.sonora.core.djengine.pipeline

import io.sonora.core.djmodel.Accelerator
import io.sonora.core.djmodel.AnalysisProvenance
import io.sonora.core.djmodel.AnalysisStage
import io.sonora.core.djmodel.AnalysisTelemetry
import io.sonora.core.djmodel.AnalysisTimings
import io.sonora.core.djmodel.AudioId
import io.sonora.core.djmodel.BeatActivationModel
import io.sonora.core.djmodel.BeatActivationModelFactory
import io.sonora.core.djmodel.BeatModelDescriptor
import io.sonora.core.djmodel.ChunkLogits
import io.sonora.core.djmodel.MelSpectrogram
import io.sonora.core.djmodel.MonoSink
import io.sonora.core.djmodel.MonoSinkFactory

val TEST_AUDIO_ID: AudioId = AudioId.of(AudioId.PAYLOAD_V1, "ab".repeat(32))

fun beatThisDescriptor(): BeatModelDescriptor = BeatModelDescriptor(
    id = "beatthis-small",
    version = "1",
    sampleRateHz = 22_050,
    melBinCount = 128,
    framesPerSecond = 50.0,
    chunkFrames = 1_500,
    borderFrames = 6,
    accelerator = Accelerator.CPU,
)

/** Interleaved samples served in reads of at most [maxReadFrames] frames; counts reads and decode passes. */
class ArrayPcmSource(
    private val samples: FloatArray,
    override val sampleRateHz: Int,
    override val channelCount: Int = 1,
    private val lengthKnown: Boolean = true,
    private val maxReadFrames: Int = Int.MAX_VALUE,
    private val restartable: Boolean = false,
) : PcmSource {
    private var position = 0
    var reads = 0
        private set
    var passes = 1
        private set
    var restartRequests = 0
        private set

    val frames: Int get() = samples.size / channelCount

    override val totalFrames: Long? get() = if (lengthKnown) frames.toLong() else null

    override fun read(buffer: FloatArray): Int {
        reads++
        val left = frames - position
        if (left == 0) return -1
        val count = minOf(left, buffer.size / channelCount, maxReadFrames)
        samples.copyInto(buffer, 0, position * channelCount, (position + count) * channelCount)
        position += count
        return count
    }

    override fun restart(): Boolean {
        restartRequests++
        if (!restartable) return false
        position = 0
        passes++
        return true
    }
}

/** A sink factory whose sinks count what they get and answer [result]; the sum of the samples checks the downmix. */
class CountingSinks<R>(override val sampleRateHz: Int, private val result: () -> R) : MonoSinkFactory<R> {
    var created = 0
        private set
    var blocks = 0
        private set
    var samples = 0L
        private set
    var sum = 0.0
        private set
    var finished = 0
        private set
    var acceptAction: () -> Unit = {}

    override fun create(): MonoSink<R> {
        created++
        return object : MonoSink<R> {
            override fun accept(samples: FloatArray, offset: Int, count: Int) {
                acceptAction()
                blocks++
                this@CountingSinks.samples += count
                for (i in offset until offset + count) sum += samples[i]
            }

            override fun finish(): R {
                finished++
                return result()
            }
        }
    }

    fun asStage(): StageSinks<R> = StageSinks { this }
}

/** Zero-order hold resampler: output sample m is input sample floor(m * in / out). Counts how often it was opened. */
class HoldResampler : Resampler {
    var opened = 0
        private set

    override fun into(target: MonoSink<*>, inputRateHz: Int, outputRateHz: Int): MonoSink<Unit> {
        opened++
        return object : MonoSink<Unit> {
            private var consumed = 0L
            private var produced = 0L
            private var out = FloatArray(0)

            override fun accept(samples: FloatArray, offset: Int, count: Int) {
                val end = consumed + count
                var n = 0
                if (out.size < count * outputRateHz / inputRateHz + 2) out = FloatArray(count * outputRateHz / inputRateHz + 2)
                while (true) {
                    val index = produced * inputRateHz / outputRateHz
                    if (index >= end) break
                    out[n++] = samples[offset + (index - consumed).toInt()]
                    produced++
                }
                consumed = end
                if (n > 0) target.accept(out, 0, n)
            }

            override fun finish() = Unit
        }
    }
}

/** A resampler the test expects never to be used. */
object UnusedResampler : Resampler {
    override fun into(target: MonoSink<*>, inputRateHz: Int, outputRateHz: Int): MonoSink<Unit> =
        error("no stage should need resampling here ($inputRateHz -> $outputRateHz)")
}

class RecordingTelemetry : AnalysisTelemetry {
    val analysed = ArrayList<Triple<AudioId, AnalysisTimings, AnalysisProvenance>>()
    val failed = ArrayList<Pair<AnalysisStage, Throwable>>()

    override fun analysed(audioId: AudioId, timings: AnalysisTimings, provenance: AnalysisProvenance) {
        analysed += Triple(audioId, timings, provenance)
    }

    override fun failed(stage: AnalysisStage, cause: Throwable) {
        failed += stage to cause
    }
}

/** Every monotonic reading is one ms after the previous one; the wall clock is fixed. */
class SteppingClock(private val epochMs: Long = EPOCH_MS) : PipelineClock {
    private var nanos = 0L

    override fun nowEpochMs(): Long = epochMs

    override fun monotonicNanos(): Long {
        nanos += 1_000_000
        return nanos
    }

    companion object {
        const val EPOCH_MS: Long = 1_791_331_200_000
    }
}

/** A model that answers each chunk with [answer] (default: all-zero logits of the chunk's length) and records calls. */
open class FakeModel(
    override val descriptor: BeatModelDescriptor = beatThisDescriptor(),
    private val answer: (index: Int, chunk: MelSpectrogram) -> ChunkLogits = { _, chunk ->
        ChunkLogits(FloatArray(chunk.frameCount), FloatArray(chunk.frameCount))
    },
) : BeatActivationModel {
    var calls = 0
        private set
    var closed = 0
        private set

    override fun infer(chunk: MelSpectrogram): ChunkLogits = answer(calls, chunk).also { calls++ }

    override fun close() {
        closed++
    }
}

class FakeModelFactory(private val open: () -> BeatActivationModel?) : BeatActivationModelFactory {
    var requests = 0
        private set

    override suspend fun create(preferred: Accelerator): BeatActivationModel? {
        requests++
        return open()
    }
}

/** A mel spectrogram of [frames] frames of 128 bins at 50 frames per second. */
fun melOf(frames: Int): MelSpectrogram = MelSpectrogram(128, 50.0, FloatArray(frames * 128) { 1f })

/** Interleaves [channels] (one array per channel, equal lengths) into frames. */
fun interleave(channels: List<FloatArray>): FloatArray {
    val frames = channels.first().size
    return FloatArray(frames * channels.size) { channels[it % channels.size][it / channels.size] }
}
