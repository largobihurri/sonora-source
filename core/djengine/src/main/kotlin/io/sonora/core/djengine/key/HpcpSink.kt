package io.sonora.core.djengine.key

import io.sonora.core.djmodel.MonoSink
import io.sonora.core.djmodel.MonoSinkFactory
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The chroma front end of the key detector: a Harmonic Pitch Class Profile per frame (Gomez 2006, see
 * [HpcpFrameAnalyzer] for the algorithm) at 36 bins, bin 0 being C, bin 27 being A (see [ChromaFrames]).
 *
 * Frames do not overlap: the frame and the hop are [frameSizeFor] samples, 4096 at 44.1 kHz (93 ms, 10.8 frames per
 * second). Other sample rates get the power of two nearest to the same 93 ms (a radix-2 FFT needs one): 2048 at 22.05
 * and 16 kHz, 4096 at 32 and 48 kHz, 8192 at 96 kHz. The key is a slowly changing property, so non-overlapping
 * frames lose nothing the matcher would use.
 *
 * The frame count is the number of samples divided by the frame size, rounded down: a last partial frame is dropped
 * (at most 93 ms of a track; zero-padding it would smear the spectrum and give the matcher a bad frame).
 *
 * [referenceFrequencyHz] is the tuning of A (440 Hz). Estimating the tuning of a track from the peak deviations is not
 * done here: it needs a second pass over the peaks, so it is left to the pipeline, which can create the sink again with
 * the estimated reference.
 */
class HpcpSink(
    override val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    val referenceFrequencyHz: Double = 440.0,
) : MonoSinkFactory<ChromaFrames> {

    init {
        require(sampleRateHz in 8_000..192_000) { "sampleRateHz must be 8000..192000: $sampleRateHz" }
        require(referenceFrequencyHz.isFinite() && referenceFrequencyHz in 400.0..480.0) {
            "referenceFrequencyHz must be 400..480: $referenceFrequencyHz"
        }
    }

    override fun create(): MonoSink<ChromaFrames> = Sink(sampleRateHz, referenceFrequencyHz)

    private class Sink(private val sampleRateHz: Int, referenceFrequencyHz: Double) : MonoSink<ChromaFrames> {
        private val frameSize = frameSizeFor(sampleRateHz)
        private val analyzer = HpcpFrameAnalyzer(sampleRateHz, frameSize, referenceFrequencyHz)
        private val frame = FloatArray(frameSize)
        private var filled = 0
        private var frames = 0
        private var output = FloatArray(INITIAL_FRAMES * ChromaFrames.BINS_PER_FRAME)

        override fun accept(samples: FloatArray, offset: Int, count: Int) {
            require(offset >= 0 && count >= 0 && offset + count <= samples.size) { "range $offset+$count outside ${samples.size}" }
            var at = offset
            var left = count
            while (left > 0) {
                val n = minOf(left, frameSize - filled)
                System.arraycopy(samples, at, frame, filled, n)
                filled += n
                at += n
                left -= n
                if (filled == frameSize) {
                    emit()
                    filled = 0
                }
            }
        }

        override fun finish(): ChromaFrames {
            filled = 0 // a last partial frame is dropped
            return ChromaFrames(output.copyOf(frames * ChromaFrames.BINS_PER_FRAME), sampleRateHz.toDouble() / frameSize)
        }

        private fun emit() {
            val needed = (frames + 1) * ChromaFrames.BINS_PER_FRAME
            if (needed > output.size) output = output.copyOf(maxOf(needed, output.size * 2))
            analyzer.analyze(frame, output, frames * ChromaFrames.BINS_PER_FRAME)
            frames++
        }
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ: Int = 44_100

        private const val REFERENCE_FRAME_SIZE = 4_096
        private const val INITIAL_FRAMES = 32

        /** The frame and hop size in samples at [sampleRateHz]: the power of two nearest to 4096 samples at 44.1 kHz. */
        fun frameSizeFor(sampleRateHz: Int): Int {
            require(sampleRateHz > 0) { "sampleRateHz must be positive: $sampleRateHz" }
            val exponent = ln(REFERENCE_FRAME_SIZE.toDouble() * sampleRateHz / DEFAULT_SAMPLE_RATE_HZ) / ln(2.0)
            return 1 shl exponent.roundToInt().coerceAtLeast(1)
        }
    }
}
