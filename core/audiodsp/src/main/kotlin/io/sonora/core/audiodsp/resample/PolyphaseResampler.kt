package io.sonora.core.audiodsp.resample

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming sample-rate converter for interleaved float32 frames: a Kaiser-windowed sinc low-pass applied as a
 * polyphase filter bank for the rational ratio `upFactor / downFactor` (96 kHz -> 44.1 kHz is 147 / 320).
 *
 * Design, for any ratio: passband flat to 20 kHz (or 90.7 % of the lower Nyquist frequency when that is lower),
 * stopband from the lower Nyquist frequency on, [STOPBAND_ATTENUATION_DB] Kaiser design target. The filter length
 * follows from the transition band: 366 taps per output sample at 96 kHz -> 44.1 kHz, 732 at 192 kHz. Each phase is
 * normalised to unity DC gain. The filter is centred on the output instant, so output frame `m` sits at input time
 * `m * inputRate / outputRate`: no delay, no time shift of cue points.
 *
 * Stateful: [process] blocks of any size, then [flush] once; the result is identical for every block size and holds
 * exactly [outputFrameCount] frames, the input duration at the new rate rounded up. Coefficients are computed once
 * in the constructor; [process] allocates nothing once its history buffer has grown to the block size.
 * Not thread-safe.
 */
class PolyphaseResampler(
    val inputRateHz: Int,
    val outputRateHz: Int,
    val channelCount: Int,
) {
    /** L: the upsampling factor, which is also the number of filter phases. */
    val upFactor: Int

    /** M: the decimation factor. */
    val downFactor: Int

    /** K: input frames under the filter for each output frame (even). */
    val tapsPerPhase: Int

    /** Phase-major: `coefficients[p * K + j]` weighs input frame `i - K + 1 + j` for an output at phase `p`. */
    private val coefficients: FloatArray

    /** Interleaved input frames; frame 0 is absolute input frame [historyStart] (negative for the zero pre-roll). */
    private var history: FloatArray
    private var historyFrames: Int
    private var historyStart: Long

    private var inputFrames = 0L
    private var outputFrames = 0L

    /** Newest input frame under the filter for the next output, and the phase of that output. */
    private var nextIndex: Long
    private var nextPhase: Int
    private var flushed = false

    init {
        require(inputRateHz > 0 && outputRateHz > 0) { "Sample rates must be positive: $inputRateHz -> $outputRateHz" }
        require(channelCount > 0) { "channelCount must be positive: $channelCount" }
        val (up, down) = boundedRatio(outputRateHz.toLong(), inputRateHz.toLong(), MAX_PHASES.toLong())
        upFactor = up.toInt()
        downFactor = down.toInt()

        val nyquist = minOf(inputRateHz, outputRateHz) / 2.0
        val passbandEdge = minOf(PASSBAND_EDGE_HZ, nyquist * PASSBAND_FRACTION)
        val transition = nyquist - passbandEdge
        val cutoff = (passbandEdge + nyquist) / 2
        // Kaiser's estimate of the filter length, in input frames.
        val length = (STOPBAND_ATTENUATION_DB - 7.95) * inputRateHz / (14.36 * transition)
        tapsPerPhase = maxOf(4, 2 * ceil(length / 2).toInt())
        coefficients = design(upFactor, tapsPerPhase, cutoff / inputRateHz)

        val k = tapsPerPhase
        history = FloatArray((k + DEFAULT_BLOCK) * channelCount)
        historyFrames = k - 1 // zero pre-roll: the input before the first frame is silence
        historyStart = -(k - 1).toLong()
        // Output 0 is centred on input 0: the filter centre (L * K / 2 in the upsampled domain) lands on it.
        nextIndex = (k / 2).toLong()
        nextPhase = 0
    }

    /** Output capacity [process] and [flush] need, in frames, for blocks of up to [inputFrames] frames. */
    fun maxOutputFrames(inputFrames: Int): Int =
        ((inputFrames.toLong() + tapsPerPhase) * upFactor / downFactor + 2).toInt()

    /** Frames the whole conversion delivers for [inputFrames] input frames: `ceil(inputFrames * L / M)`. */
    fun outputFrameCount(inputFrames: Long): Long = (inputFrames * upFactor + downFactor - 1) / downFactor

    /**
     * Consumes [frames] frames from [input] and writes the output frames that are now complete to [output] from
     * index 0; returns how many. [output] must hold [maxOutputFrames] (`frames`) frames.
     */
    fun process(input: FloatArray, frames: Int, output: FloatArray): Int {
        check(!flushed) { "Resampler already flushed" }
        require(frames >= 0 && frames.toLong() * channelCount <= input.size) {
            "$frames frames of $channelCount channels do not fit ${input.size} samples"
        }
        require(output.size >= maxOutputFrames(frames).toLong() * channelCount) {
            "Output of ${output.size} samples cannot hold ${maxOutputFrames(frames)} frames"
        }
        append(input, frames)
        inputFrames += frames
        return produce(output, Long.MAX_VALUE)
    }

    /** Runs the filter out past the last input frame and writes the remaining output frames; call once, last. */
    fun flush(output: FloatArray): Int {
        check(!flushed) { "Resampler already flushed" }
        require(output.size >= maxOutputFrames(0).toLong() * channelCount) {
            "Output of ${output.size} samples cannot hold ${maxOutputFrames(0)} frames"
        }
        flushed = true
        val total = outputFrameCount(inputFrames)
        if (outputFrames >= total) return 0
        val lastNeeded = ((total - 1) * downFactor + upFactor.toLong() * tapsPerPhase / 2) / upFactor
        val zeros = (lastNeeded - (historyStart + historyFrames - 1)).coerceAtLeast(0).toInt()
        appendSilence(zeros)
        return produce(output, total)
    }

    private fun produce(output: FloatArray, limit: Long): Int {
        val c = channelCount
        val k = tapsPerPhase
        val newest = historyStart + historyFrames - 1
        var written = 0
        while (nextIndex <= newest && outputFrames < limit) {
            val start = ((nextIndex - k + 1 - historyStart) * c).toInt()
            val taps = nextPhase * k
            val at = written * c
            when (c) {
                1 -> output[at] = dot1(taps, start)
                2 -> dot2(taps, start, output, at)
                else -> for (ch in 0 until c) output[at + ch] = dotN(taps, start + ch, c)
            }
            written++
            outputFrames++
            nextPhase += downFactor
            nextIndex += nextPhase / upFactor
            nextPhase %= upFactor
        }
        discardConsumed()
        return written
    }

    private fun dot1(taps: Int, start: Int): Float {
        val h = coefficients
        val x = history
        var sum = 0f
        for (j in 0 until tapsPerPhase) sum += h[taps + j] * x[start + j]
        return sum
    }

    private fun dot2(taps: Int, start: Int, output: FloatArray, at: Int) {
        val h = coefficients
        val x = history
        var left = 0f
        var right = 0f
        var i = start
        for (j in 0 until tapsPerPhase) {
            val w = h[taps + j]
            left += w * x[i]
            right += w * x[i + 1]
            i += 2
        }
        output[at] = left
        output[at + 1] = right
    }

    private fun dotN(taps: Int, start: Int, stride: Int): Float {
        var sum = 0f
        var i = start
        for (j in 0 until tapsPerPhase) {
            sum += coefficients[taps + j] * history[i]
            i += stride
        }
        return sum
    }

    /** Drops history frames no future output needs (older than `nextIndex - K + 1`). */
    private fun discardConsumed() {
        val keepFrom = nextIndex - tapsPerPhase + 1
        val drop = (keepFrom - historyStart).coerceIn(0, historyFrames.toLong()).toInt()
        if (drop == 0) return
        val c = channelCount
        history.copyInto(history, 0, drop * c, historyFrames * c)
        historyFrames -= drop
        historyStart += drop
    }

    private fun append(input: FloatArray, frames: Int) {
        ensureCapacity(frames)
        input.copyInto(history, historyFrames * channelCount, 0, frames * channelCount)
        historyFrames += frames
    }

    private fun appendSilence(frames: Int) {
        ensureCapacity(frames)
        history.fill(0f, historyFrames * channelCount, (historyFrames + frames) * channelCount)
        historyFrames += frames
    }

    private fun ensureCapacity(extraFrames: Int) {
        val needed = (historyFrames + extraFrames) * channelCount
        if (needed > history.size) history = history.copyOf(maxOf(needed, history.size * 2))
    }

    companion object {
        /** Kaiser design target for the stopband. */
        const val STOPBAND_ATTENUATION_DB: Double = 120.0

        /** Kaiser window shape for [STOPBAND_ATTENUATION_DB]: `0.1102 * (A - 8.7)`. */
        const val KAISER_BETA: Double = 0.1102 * (STOPBAND_ATTENUATION_DB - 8.7)

        /** Most filter phases (L); rarer ratios are approximated to within a few ppm. */
        const val MAX_PHASES: Int = 1_024

        private const val PASSBAND_EDGE_HZ = 20_000.0
        private const val PASSBAND_FRACTION = 0.907
        private const val DEFAULT_BLOCK = 4_096

        /**
         * `L / M = outputRate / inputRate` in lowest terms; when L exceeds [maxUp], the closest continued-fraction
         * convergent with `L <= maxUp`.
         */
        internal fun boundedRatio(output: Long, input: Long, maxUp: Long): Pair<Long, Long> {
            val g = gcd(output, input)
            if (output / g <= maxUp) return output / g to input / g
            // Convergents h/k of output/input; keep the last one whose numerator fits.
            var h0 = 0L
            var h1 = 1L
            var k0 = 1L
            var k1 = 0L
            var num = output
            var den = input
            var best = 1L to maxOf(1L, input / output)
            while (den != 0L) {
                val a = num / den
                val h2 = a * h1 + h0
                val k2 = a * k1 + k0
                if (h2 > maxUp) break
                if (h2 > 0) best = h2 to k2
                h0 = h1; h1 = h2; k0 = k1; k1 = k2
                val r = num - a * den
                num = den
                den = r
            }
            return best
        }

        private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

        /**
         * The prototype low-pass at `L * inputRate`, cut off at [cutoff] (a fraction of the input rate), split into
         * L phases of K taps stored in reverse so [process] walks the history forwards.
         */
        private fun design(up: Int, taps: Int, cutoff: Double): FloatArray {
            val centre = up.toLong() * taps / 2 // prototype tap n sits at upsampled offset n - centre
            val i0Beta = besselI0(KAISER_BETA)
            val result = FloatArray(up * taps)
            val phase = DoubleArray(taps)
            for (p in 0 until up) {
                var sum = 0.0
                for (j in 0 until taps) {
                    val n = p + j.toLong() * up
                    val offset = (n - centre).toDouble()
                    val t = offset / up // in input frames
                    val x = offset / centre // window position in [-1, 1)
                    val window = besselI0(KAISER_BETA * sqrt(maxOf(0.0, 1 - x * x))) / i0Beta
                    val value = 2 * cutoff * sinc(2 * cutoff * t) * window
                    phase[j] = value
                    sum += value
                }
                for (j in 0 until taps) result[p * taps + (taps - 1 - j)] = (phase[j] / sum).toFloat()
            }
            return result
        }

        private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        /** Modified Bessel function of the first kind, order 0, by its power series. */
        private fun besselI0(x: Double): Double {
            val half = x / 2
            var term = 1.0
            var sum = 1.0
            var k = 1
            while (term > sum * 1e-17) {
                term *= (half / k) * (half / k)
                sum += term
                k++
            }
            return sum
        }
    }
}
