package io.sonora.core.playback.equalizer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import io.sonora.core.domain.equalizer.Biquad
import io.sonora.core.domain.equalizer.EqualizerBand
import io.sonora.core.domain.equalizer.EqualizerLayout
import io.sonora.core.domain.equalizer.EqualizerMode
import io.sonora.core.domain.equalizer.EqualizerResponse
import io.sonora.core.domain.equalizer.EqualizerSettings
import io.sonora.core.playback.replaygain.GainStage
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Sonora's playback equalizer: a cascade of RBJ biquads (one per band of the chosen [EqualizerMode]) on every
 * channel, with an automatic preamp equal to the curve's highest boost so no frequency rises above full scale.
 *
 * - **Without the automatic preamp** ([EqualizerSettings.automaticPreamp] off, a curve on and not flat): no preamp;
 *   a [PeakLimiter] after the filters keeps every sample under its ceiling instead, so the music keeps its level and
 *   only the peaks the boosts push over full scale come down. Its look-ahead delays the stream by
 *   [PeakLimiter.LOOKAHEAD_SECONDS] (not reported to the sink: the delay only moves the sound 4 ms after its
 *   timestamps, and whatever measures the output, as the band meter does, sees the delayed audio it really plays; the
 *   last 4 ms in the delay line when a stream stops or seeks are dropped). The limiter fades in over [RAMP_SECONDS]
 *   (against the undelayed signal) before the preamp rises, and the preamp falls back before it fades out, so nothing
 *   clicks or goes over full scale on the way.
 *
 * - **Formats**: any channel count and rate; 8, 16, 24 and 32-bit integer PCM (either byte order) and float. Samples
 *   are filtered as 32-bit floats; the filter state and coefficients are doubles, which keeps a 16 Hz shelf exact at
 *   192 kHz. The output is float when [floatOutput] is set before [configure], otherwise the input's own encoding
 *   (re-quantised with triangular dither while the curve is applied).
 * - **Live settings**: [settings] is read at the start of every buffer, so a change applies from the next buffer.
 *   Gains and preamp move linearly in dB over [RAMP_SECONDS] (coefficients updated every [BLOCK_FRAMES] frames, the
 *   preamp per sample), so changes, switching on or off and mode changes (fade to flat, swap bands, fade in) do not
 *   click.
 * - **Bypass**: switched off or flat, once the ramp has settled, buffers are copied unchanged (or only widened to
 *   float), so the output is bit-identical to the input; [isPassthrough] then lets the sink skip the copy entirely.
 * - **Input gain**: ReplayGain's [GainStage] runs on the decoded samples ahead of the filters ([setInputGain]); away
 *   from unity it keeps the processor out of bypass (gain only, re-quantised with dither for integer output).
 * - **Real time**: after the first buffers sized the scratch arrays, processing allocates nothing.
 */
@UnstableApi
class EqualizerAudioProcessor(private val settings: () -> EqualizerSettings) : BaseAudioProcessor() {

    /** Whether [configure] makes the output float (otherwise it keeps the input's encoding). */
    var floatOutput: Boolean = true

    // Stream.
    private var sampleRate = 0
    private var channels = 0
    private var inEncoding = C.ENCODING_INVALID
    private var outEncoding = C.ENCODING_INVALID
    private var samples = FloatArray(0)

    // The curve being played and where it is heading.
    private var lastSettings: EqualizerSettings? = null
    private var mode = EqualizerMode.FOUR_BANDS
    private var bands: List<EqualizerBand> = EqualizerLayout.bands(mode)
    private val frequencies = DoubleArray(EqualizerLayout.MAX_BANDS)
    private val gains = DoubleArray(EqualizerLayout.MAX_BANDS)
    private val targetGains = DoubleArray(EqualizerLayout.MAX_BANDS)
    private var preampDb = 0.0
    private var targetPreampDb = 0.0
    private var rampFramesLeft = 0
    private var flat = true

    // Filters: coefficients per band, then x1, x2, y1, y2 per band and channel.
    private val coefficients = DoubleArray(EqualizerLayout.MAX_BANDS * Biquad.STRIDE)
    private val targetCoefficients = DoubleArray(EqualizerLayout.MAX_BANDS * Biquad.STRIDE)
    private var history = DoubleArray(0)
    private var historyStale = true

    // The limiter in place of the preamp: [limiterMix] fades its (delayed) output in over the unlimited signal.
    private val limiter = PeakLimiter()
    private var limiterMix = 0.0
    private var limiterTarget = 0.0
    private var limiterStale = true
    private var undelayed = FloatArray(0)

    /** ReplayGain, ahead of the filters. */
    private val inputGain = GainStage()

    /** Frames still to run on the flat curve before a ramp may start, so restarted filters settle on the signal. */
    private var primeFrames = 0
    private var dither = 0x2545F491

    init {
        loadBands()
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (bytesPerSample(inputAudioFormat.encoding) == 0) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        val output = if (floatOutput) C.ENCODING_PCM_FLOAT else inputAudioFormat.encoding
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, output)
    }

    /** A new stream (or a seek in it): the filters forget the old signal; the curve and any ramp carry on. */
    override fun onFlush() {
        val rateChanged = inputAudioFormat.sampleRate != sampleRate
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        inEncoding = inputAudioFormat.encoding
        outEncoding = outputAudioFormat.encoding
        val historySize = channels * EqualizerLayout.MAX_BANDS * HISTORY_STRIDE
        if (history.size < historySize) history = DoubleArray(historySize)
        history.fill(0.0)
        historyStale = false
        primeFrames = PRIME_FRAMES
        // The delay line restarts empty: a stream the limiter is on starts 4 ms of silence late, like any other.
        limiter.configure(sampleRate, channels)
        limiterStale = false
        if (undelayed.size < channels) undelayed = FloatArray(channels)
        inputGain.configure(sampleRate)
        if (rateChanged) {
            designCurrent()
            retarget()
        }
    }

    override fun onReset() {
        sampleRate = 0
        channels = 0
        inEncoding = C.ENCODING_INVALID
        outEncoding = C.ENCODING_INVALID
        samples = FloatArray(0)
        history = DoubleArray(0)
        historyStale = true
        inputGain.reset()
    }

    /**
     * Sets the linear gain applied ahead of the filters (ReplayGain): reached over [GainStage.RAMP_SECONDS], or at
     * once when [immediate] (a stream starting from silence). Set before the buffer it applies to.
     */
    fun setInputGain(gain: Double, immediate: Boolean = false) = inputGain.setTarget(gain, immediate)

    /**
     * Whether the next buffer would leave this processor unchanged, so the caller may hand the stream's own buffer
     * on instead (bypassed or flat, at unity input gain, and no change of encoding). Reads the latest settings. When it says so, the
     * filters and the limiter stop following the signal as in [queueInput]'s bypass, since that buffer never reaches
     * them: switched back on, they restart clean (primed from silence) instead of from the audio before the bypass.
     */
    fun isPassthrough(): Boolean {
        val passthrough = updateCurve() && inEncoding == outEncoding && inputGain.isUnity
        if (passthrough) {
            historyStale = true
            limiterStale = true
        }
        return passthrough
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val frames = size / (bytesPerSample(inEncoding) * channels)
        val count = frames * channels
        val output = replaceOutputBuffer(count * bytesPerSample(outEncoding))
        if (updateCurve()) {
            // Bypassed: the filters stop following the signal; they restart from silence, exact for a flat curve.
            historyStale = true
            limiterStale = true
            if (!inputGain.isUnity) {
                decode(inputBuffer, count)
                inputGain.process(samples, frames, channels)
                encode(output, count, dithered = outEncoding != C.ENCODING_PCM_FLOAT)
            } else if (inEncoding == outEncoding) {
                output.put(inputBuffer)
            } else {
                decode(inputBuffer, count)
                encode(output, count, dithered = false)
            }
        } else {
            if (historyStale) {
                // Back from bypass (or onto a new mode's bands), the filters are flat: two frames on them from
                // silence leave a history that matches the signal, so the ramp that follows starts without a step.
                history.fill(0.0)
                historyStale = false
                primeFrames = PRIME_FRAMES
            }
            decode(inputBuffer, count)
            inputGain.process(samples, frames, channels)
            filter(frames)
            encode(output, count, dithered = outEncoding != C.ENCODING_PCM_FLOAT)
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    // The curve.

    /** Follows the latest settings; returns whether the curve is flat and settled, without the limiter (the bypass). */
    private fun updateCurve(): Boolean {
        val latest = settings()
        if (latest !== lastSettings) {
            lastSettings = latest
            retarget()
        }
        // A new mode's bands take over once the old curve has faded to flat.
        if (flat && rampFramesLeft == 0 && latest.mode != mode) {
            mode = latest.mode
            loadBands()
            historyStale = true
            retarget()
        }
        return flat && rampFramesLeft == 0 && limiterMix == 0.0 && limiterTarget == 0.0
    }

    private fun loadBands() {
        bands = EqualizerLayout.bands(mode)
        for (i in bands.indices) frequencies[i] = bands[i].frequencyHz
        gains.fill(0.0)
        targetGains.fill(0.0)
        preampDb = 0.0
        targetPreampDb = 0.0
        flat = true
        designCurrent()
    }

    /**
     * Points the ramp at the latest settings: their curve, or flat while switched off or changing modes; and the
     * limiter, on while they are on with a curve and without the automatic preamp (across a change of modes too).
     */
    private fun retarget() {
        val latest = lastSettings ?: return
        val curve = latest.enabled && latest.mode == mode
        val stored = latest.gainsFor(mode)
        val active = latest.activeGains
        var boosted = false
        for (i in active.indices) if (active[i] != 0f) boosted = true
        limiterTarget = if (latest.enabled && !latest.automaticPreamp && boosted) 1.0 else 0.0
        var changed = false
        for (i in bands.indices) {
            targetGains[i] = if (curve) stored[i].toDouble() else 0.0
            if (targetGains[i] != gains[i]) changed = true
        }
        if (sampleRate > 0) {
            for (i in bands.indices) design(i, targetGains[i], targetCoefficients)
            val peak = EqualizerResponse.peakDb(targetCoefficients, frequencies, bands.size, sampleRate)
            targetPreampDb = if (peak > HEADROOM_EPSILON_DB && limiterTarget == 0.0) -peak else 0.0
        }
        if (targetPreampDb != preampDb) changed = true
        rampFramesLeft = if (changed && sampleRate > 0) (RAMP_SECONDS * sampleRate).toInt().coerceAtLeast(1) else 0
        if (rampFramesLeft > 0) flat = false
    }

    /**
     * Moves [frames] further on: into the limiter first, so the preamp only rises once the limiter holds the peaks
     * (and once its delay line has the signal), then along the curve's ramp, and out of the limiter last, once the
     * preamp is back down.
     */
    private fun advance(frames: Int) {
        val step = frames / (RAMP_SECONDS * sampleRate)
        when {
            limiterTarget > limiterMix -> if (limiter.primed) limiterMix = min(1.0, limiterMix + step)
            rampFramesLeft > 0 -> stepRamp(frames)
            limiterTarget < limiterMix -> limiterMix = max(0.0, limiterMix - step)
        }
    }

    /** Moves the gains [frames] further along the ramp. */
    private fun stepRamp(frames: Int) {
        if (frames >= rampFramesLeft) {
            for (i in bands.indices) gains[i] = targetGains[i]
            preampDb = targetPreampDb
            rampFramesLeft = 0
            flat = preampDb == 0.0
            for (i in bands.indices) if (gains[i] != 0.0) flat = false
        } else {
            val t = frames.toDouble() / rampFramesLeft
            for (i in bands.indices) gains[i] += (targetGains[i] - gains[i]) * t
            preampDb += (targetPreampDb - preampDb) * t
            rampFramesLeft -= frames
        }
        designCurrent()
    }

    private fun designCurrent() {
        if (sampleRate > 0) for (i in bands.indices) design(i, gains[i], coefficients)
    }

    private fun design(band: Int, gainDb: Double, out: DoubleArray) {
        val spec = bands[band]
        Biquad.design(spec.shape, spec.frequencyHz, spec.q, gainDb, sampleRate, out, band * Biquad.STRIDE)
    }

    // The signal.

    private fun filter(frames: Int) {
        val bandCount = bands.size
        var frame = 0
        while (frame < frames) {
            val priming = primeFrames > 0
            val block = min(if (priming) primeFrames else BLOCK_FRAMES, frames - frame)
            val preampFrom = linear(preampDb)
            val mixFrom = limiterMix
            // The limiter's delay line follows the signal only while it is (or is going) on; back on, it starts afresh.
            val limiting = limiterTarget > 0.0 || mixFrom > 0.0
            if (!limiting) {
                limiterStale = true
            } else if (limiterStale) {
                limiter.reset()
                limiterStale = false
            }
            if (priming) primeFrames -= block else advance(block)
            val preampTo = linear(preampDb)
            val preampStep = (preampTo - preampFrom) / block
            val mixStep = (limiterMix - mixFrom) / block
            for (f in 0 until block) {
                val preamp = preampFrom + preampStep * (f + 1)
                val base = (frame + f) * channels
                for (ch in 0 until channels) {
                    var x = samples[base + ch] * preamp
                    var h = ch * EqualizerLayout.MAX_BANDS * HISTORY_STRIDE
                    for (b in 0 until bandCount) {
                        val c = b * Biquad.STRIDE
                        val y = coefficients[c] * x + coefficients[c + 1] * history[h] + coefficients[c + 2] * history[h + 1] -
                            coefficients[c + 3] * history[h + 2] - coefficients[c + 4] * history[h + 3]
                        history[h + 1] = history[h]
                        history[h] = x
                        history[h + 3] = history[h + 2]
                        history[h + 2] = y
                        x = y
                        h += HISTORY_STRIDE
                    }
                    samples[base + ch] = x.toFloat()
                }
                if (limiting) limit(base, mixFrom + mixStep * (f + 1))
            }
            frame += block
        }
    }

    /** Replaces the frame at [base] with the limiter's output, crossfaded at [mix] over the frame itself. */
    private fun limit(base: Int, mix: Double) {
        if (mix >= 1.0) {
            limiter.process(samples, base, samples, base)
            return
        }
        limiter.process(samples, base, undelayed, 0)
        if (mix <= 0.0) return
        for (ch in 0 until channels) samples[base + ch] = (samples[base + ch] * (1 - mix) + undelayed[ch] * mix).toFloat()
    }

    private fun decode(input: ByteBuffer, count: Int) {
        if (samples.size < count) samples = FloatArray(count)
        var p = input.position()
        when (inEncoding) {
            C.ENCODING_PCM_8BIT -> for (i in 0 until count) {
                samples[i] = ((input.get(p).toInt() and 0xFF) - 128) * (1f / 128)
                p += 1
            }
            C.ENCODING_PCM_16BIT -> for (i in 0 until count) {
                samples[i] = ((input.get(p + 1).toInt() shl 8) or u(input, p)) * (1f / 32_768)
                p += 2
            }
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> for (i in 0 until count) {
                samples[i] = ((input.get(p).toInt() shl 8) or u(input, p + 1)) * (1f / 32_768)
                p += 2
            }
            C.ENCODING_PCM_24BIT -> for (i in 0 until count) {
                samples[i] = ((input.get(p + 2).toInt() shl 16) or (u(input, p + 1) shl 8) or u(input, p)) * (1f / 8_388_608)
                p += 3
            }
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> for (i in 0 until count) {
                samples[i] = ((input.get(p).toInt() shl 16) or (u(input, p + 1) shl 8) or u(input, p + 2)) * (1f / 8_388_608)
                p += 3
            }
            C.ENCODING_PCM_32BIT -> for (i in 0 until count) {
                samples[i] = (int32(input, p, bigEndian = false) * (1.0 / 2_147_483_648.0)).toFloat()
                p += 4
            }
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> for (i in 0 until count) {
                samples[i] = (int32(input, p, bigEndian = true) * (1.0 / 2_147_483_648.0)).toFloat()
                p += 4
            }
            C.ENCODING_PCM_FLOAT -> for (i in 0 until count) {
                samples[i] = Float.fromBits(int32(input, p, bigEndian = false))
                p += 4
            }
            C.ENCODING_PCM_FLOAT_BIG_ENDIAN -> for (i in 0 until count) {
                samples[i] = Float.fromBits(int32(input, p, bigEndian = true))
                p += 4
            }
        }
    }

    /** Writes the first [count] samples into [output] (native order) in the output encoding. */
    private fun encode(output: ByteBuffer, count: Int, dithered: Boolean) {
        when (outEncoding) {
            C.ENCODING_PCM_FLOAT -> for (i in 0 until count) output.putFloat(samples[i])
            C.ENCODING_PCM_FLOAT_BIG_ENDIAN -> for (i in 0 until count) putInt32(output, samples[i].toRawBits(), bigEndian = true)
            C.ENCODING_PCM_8BIT -> for (i in 0 until count) output.put((quantize(samples[i], 7, dithered) + 128).toByte())
            C.ENCODING_PCM_16BIT -> for (i in 0 until count) {
                val v = quantize(samples[i], 15, dithered)
                output.put(v.toByte())
                output.put((v shr 8).toByte())
            }
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> for (i in 0 until count) {
                val v = quantize(samples[i], 15, dithered)
                output.put((v shr 8).toByte())
                output.put(v.toByte())
            }
            C.ENCODING_PCM_24BIT -> for (i in 0 until count) {
                val v = quantize(samples[i], 23, dithered)
                output.put(v.toByte())
                output.put((v shr 8).toByte())
                output.put((v shr 16).toByte())
            }
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> for (i in 0 until count) {
                val v = quantize(samples[i], 23, dithered)
                output.put((v shr 16).toByte())
                output.put((v shr 8).toByte())
                output.put(v.toByte())
            }
            C.ENCODING_PCM_32BIT -> for (i in 0 until count) putInt32(output, quantize(samples[i], 31, dithered), bigEndian = false)
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> for (i in 0 until count) putInt32(output, quantize(samples[i], 31, dithered), bigEndian = true)
        }
    }

    /** [sample] as a signed integer of `bits + 1` bits, rounded (with triangular dither when [dithered]) and clamped. */
    private fun quantize(sample: Float, bits: Int, dithered: Boolean): Int {
        val scale = (1L shl bits).toDouble()
        val noise = if (dithered) nextUniform() + nextUniform() - 1.0 else 0.0
        val v = Math.round(sample * scale + noise)
        return v.coerceIn(-(1L shl bits), (1L shl bits) - 1).toInt()
    }

    /** Uniform in [0, 1) from a xorshift generator: cheap, allocation-free, and plenty for dither. */
    private fun nextUniform(): Double {
        var x = dither
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        dither = x
        return (x ushr 8) * (1.0 / (1 shl 24))
    }

    private fun u(buffer: ByteBuffer, index: Int): Int = buffer.get(index).toInt() and 0xFF

    private fun int32(buffer: ByteBuffer, p: Int, bigEndian: Boolean): Int =
        if (bigEndian) {
            (buffer.get(p).toInt() shl 24) or (u(buffer, p + 1) shl 16) or (u(buffer, p + 2) shl 8) or u(buffer, p + 3)
        } else {
            (buffer.get(p + 3).toInt() shl 24) or (u(buffer, p + 2) shl 16) or (u(buffer, p + 1) shl 8) or u(buffer, p)
        }

    private fun putInt32(output: ByteBuffer, v: Int, bigEndian: Boolean) {
        if (bigEndian) {
            output.put((v shr 24).toByte()).put((v shr 16).toByte()).put((v shr 8).toByte()).put(v.toByte())
        } else {
            output.put(v.toByte()).put((v shr 8).toByte()).put((v shr 16).toByte()).put((v shr 24).toByte())
        }
    }

    private fun linear(db: Double): Double = if (db == 0.0) 1.0 else 10.0.pow(db / 20)

    companion object {
        /** How long a change takes to reach its new curve. */
        const val RAMP_SECONDS = 0.03

        /** Frames between coefficient updates during a ramp. */
        const val BLOCK_FRAMES = 32

        private const val HISTORY_STRIDE = 4
        private const val PRIME_FRAMES = 2
        private const val HEADROOM_EPSILON_DB = 1e-6

        /** Bytes per sample of the PCM encodings the equalizer handles; 0 for any other. */
        fun bytesPerSample(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT_BIG_ENDIAN -> 4
            else -> 0
        }
    }
}
