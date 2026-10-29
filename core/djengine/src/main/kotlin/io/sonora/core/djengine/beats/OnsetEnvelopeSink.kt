package io.sonora.core.djengine.beats

import io.sonora.core.djmodel.MonoSink
import io.sonora.core.djmodel.MonoSinkFactory
import io.sonora.core.djmodel.OnsetEnvelope
import kotlin.math.roundToInt

/**
 * The low-band onset envelope stage: turns the mono audio of a track into an [OnsetEnvelope], the kick-region onset
 * strength at a fine hop, which [PhaseRefiner] uses to put a beat grid on the transients (the kick attack) where a
 * beat model's activations sit some ten to twenty milliseconds late.
 *
 * ## What is computed
 * See [OnsetChain]: a Bessel band-pass of [lowHz]..[highHz] (default 40..200 Hz), the amplitude envelope of that band
 * (square root of its power smoothed over [smoothingMs]) and its half-wave rectified rise over one hop. Values are
 * normalised by their maximum at the end, so the envelope is 0..1 with at least one 1 (all zeros for silence).
 *
 * ## Resolution and timing
 * One frame per [hopMs] (default 2 ms: 32 samples at the default 16 kHz, so 500 frames per second; a hop that is not
 * a whole number of samples is rounded, and [OnsetEnvelope.framesPerSecond] reports the true rate). Frame `f` is at
 * `f / framesPerSecond`. The band-pass, the smoother and the difference delay a transient's response; the sink
 * measures that delay once, on a unit impulse, and reads the filtered signal that many **samples** later, so the
 * response to an ideal transient peaks exactly at its own time, to the sample (0.06 ms at 16 kHz), whatever the
 * frame phase. A real kick has a rise time of its own, so its peak sits that much after the start of the attack:
 * the calibration against real material belongs to the calibration tool.
 *
 * ## Allocation and invariance
 * Nothing is allocated per sample or per block; the frames grow by doubling (four bytes each). The output depends
 * only on the samples and the parameters, never on the block sizes (the state advances sample by sample). Non-finite
 * input samples count as silence.
 *
 * This object is the factory (one [create] per track); the sink it creates is not thread-safe.
 *
 * @param sampleRateHz the rate the pipeline resamples the track to for this stage (4..192 kHz); 16 kHz is plenty for
 *   a band below 200 Hz.
 * @param hopMs time between frames, 0.5..20 ms.
 * @param lowHz lower band edge, at least 5 Hz.
 * @param highHz upper band edge, above [lowHz] and at most a quarter of the sample rate.
 * @param smoothingMs time constant of the envelope smoother, 0.1..20 ms.
 */
class OnsetEnvelopeSink(
    override val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    val hopMs: Double = DEFAULT_HOP_MS,
    val lowHz: Double = DEFAULT_LOW_HZ,
    val highHz: Double = DEFAULT_HIGH_HZ,
    val smoothingMs: Double = DEFAULT_SMOOTHING_MS,
) : MonoSinkFactory<OnsetEnvelope> {

    private val hopSamples: Int
    private val latencySamples: Int

    init {
        require(sampleRateHz in MIN_SAMPLE_RATE_HZ..MAX_SAMPLE_RATE_HZ) {
            "sampleRateHz must be $MIN_SAMPLE_RATE_HZ..$MAX_SAMPLE_RATE_HZ: $sampleRateHz"
        }
        require(hopMs.isFinite() && hopMs in MIN_HOP_MS..MAX_HOP_MS) { "hopMs must be $MIN_HOP_MS..$MAX_HOP_MS: $hopMs" }
        require(lowHz.isFinite() && lowHz >= MIN_LOW_HZ) { "lowHz must be at least $MIN_LOW_HZ: $lowHz" }
        require(highHz.isFinite() && highHz > lowHz && highHz <= sampleRateHz / 4.0) {
            "highHz must be above lowHz ($lowHz) and at most a quarter of the sample rate: $highHz"
        }
        require(smoothingMs.isFinite() && smoothingMs in MIN_SMOOTHING_MS..MAX_SMOOTHING_MS) {
            "smoothingMs must be $MIN_SMOOTHING_MS..$MAX_SMOOTHING_MS: $smoothingMs"
        }
        hopSamples = (hopMs * sampleRateHz / 1000.0).roundToInt().coerceAtLeast(1)
        latencySamples = measureLatencySamples()
    }

    /** Frames per second of the envelopes this factory's sinks produce. */
    val framesPerSecond: Double get() = sampleRateHz.toDouble() / hopSamples

    override fun create(): MonoSink<OnsetEnvelope> = Sink()

    /** Samples between a unit impulse and the peak of the onset function it causes. */
    private fun measureLatencySamples(): Int {
        val chain = newChain()
        var best = -1.0
        var bestAt = 0
        for (n in 0 until sampleRateHz / CALIBRATION_WINDOW_DIVISOR) {
            val value = chain.process(if (n == 0) 1.0 else 0.0)
            if (value > best) {
                best = value
                bestAt = n
            }
        }
        return bestAt
    }

    private fun newChain() = OnsetChain(sampleRateHz, hopSamples, lowHz, highHz, smoothingMs)

    private inner class Sink : MonoSink<OnsetEnvelope> {
        private val chain = newChain()
        private var frames = FloatArray(INITIAL_CAPACITY)
        private var frameCount = 0
        private var maxValue = 0f

        /** Index of the next filtered sample that is a frame: frame `f` reads sample `f * hop + latency`. */
        private var nextFrameSample = latencySamples.toLong()
        private var position = 0L
        private var untilFlush = hopSamples
        private var finished = false

        override fun accept(samples: FloatArray, offset: Int, count: Int) {
            check(!finished) { "OnsetEnvelopeSink is finished" }
            require(offset >= 0 && count >= 0 && offset <= samples.size - count) {
                "range $offset + $count outside 0..${samples.size}"
            }
            for (i in offset until offset + count) push(samples[i].toDouble())
        }

        override fun finish(): OnsetEnvelope {
            check(!finished) { "OnsetEnvelopeSink is finished" }
            finished = true
            // The last frames read filtered samples `latency` after the input ended: run silence through to reach them.
            repeat(latencySamples) { push(0.0) }
            val values = frames.copyOf(frameCount)
            if (maxValue > 0f) for (f in values.indices) values[f] /= maxValue
            return OnsetEnvelope(values, framesPerSecond)
        }

        private fun push(raw: Double) {
            val value = chain.process(if (raw.isFinite()) raw else 0.0).toFloat()
            if (position == nextFrameSample) {
                if (frameCount == frames.size) frames = frames.copyOf(frames.size * 2)
                frames[frameCount++] = value
                if (value > maxValue) maxValue = value
                nextFrameSample += hopSamples
            }
            position++
            if (--untilFlush == 0) {
                chain.flushDenormals()
                untilFlush = hopSamples
            }
        }
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ: Int = 16_000
        const val DEFAULT_HOP_MS: Double = 2.0
        const val DEFAULT_LOW_HZ: Double = 40.0
        const val DEFAULT_HIGH_HZ: Double = 200.0
        const val DEFAULT_SMOOTHING_MS: Double = 2.0

        const val MIN_SAMPLE_RATE_HZ: Int = 4_000
        const val MAX_SAMPLE_RATE_HZ: Int = 192_000
        private const val MIN_HOP_MS = 0.5
        private const val MAX_HOP_MS = 20.0
        private const val MIN_LOW_HZ = 5.0
        private const val MIN_SMOOTHING_MS = 0.1
        private const val MAX_SMOOTHING_MS = 20.0
        private const val INITIAL_CAPACITY = 16_384

        /** The calibration looks at the first tenth of a second after the impulse. */
        private const val CALIBRATION_WINDOW_DIVISOR = 10
    }
}
