package io.sonora.core.playback.equalizer

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A transparent look-ahead peak limiter: every sample comes out at or below [CEILING], and a signal that stays below it
 * comes out bit-identical, [latencyFrames] later.
 *
 * - **Look-ahead**: frames wait [LOOKAHEAD_SECONDS] in a delay line. The gain each frame needs (ceiling over its
 *   peak) is held at the lowest of the look-ahead window and then averaged over the same window, so it falls along a
 *   straight line during the look-ahead and is exactly the needed gain when the peak leaves the delay line: no sample
 *   is ever clipped, and the gain never steps.
 * - **Release**: back up towards unity on a one-pole curve with a [RELEASE_SECONDS] time constant (about 95% after
 *   three of them), slow enough not to pump on mastered music; exactly unity once within [UNITY_SNAP] of it.
 * - **Stereo link**: one gain from the loudest channel of each frame, applied to all, so the image does not move.
 * - **Real time**: arrays are sized by [configure]; [process] and [reset] allocate nothing.
 */
class PeakLimiter {

    private var channels = 0

    /** The delay in frames: what the limiter adds to the stream's latency. */
    var latencyFrames = 0
        private set

    /** The look-ahead window, the frame coming in included. */
    private var window = 0
    private var delay = FloatArray(0)
    private var delayPosition = 0

    // Sliding minimum of the needed gain over the window: a monotonic queue (ring) of values and the frame each came in.
    private var queueValues = DoubleArray(0)
    private var queueFrames = LongArray(0)
    private var queueHead = 0
    private var queueSize = 0

    // Moving average of the held gain over the window; [belowUnity] counts held values under 1, so that a window
    // back at unity reads exactly 1 whatever rounding the running sum gathered.
    private var box = DoubleArray(0)
    private var boxPosition = 0
    private var boxSum = 0.0
    private var belowUnity = 0

    private var releaseCoefficient = 0.0
    private var frame = 0L

    /** The gain applied to the frame that last came out. */
    var gain = 1.0
        private set

    /** Whether the delay line is full of the signal (not of the silence [reset] left). */
    val primed: Boolean get() = frame >= latencyFrames

    fun configure(sampleRate: Int, channels: Int) {
        this.channels = channels
        latencyFrames = (LOOKAHEAD_SECONDS * sampleRate).roundToInt().coerceAtLeast(1)
        window = latencyFrames + 1
        if (delay.size < latencyFrames * channels) delay = FloatArray(latencyFrames * channels)
        if (box.size < window) {
            box = DoubleArray(window)
            queueValues = DoubleArray(window)
            queueFrames = LongArray(window)
        }
        releaseCoefficient = 1 - exp(-1.0 / (RELEASE_SECONDS * sampleRate))
        reset()
    }

    /** Forgets the signal: an empty (silent) delay line and unity gain. */
    fun reset() {
        delay.fill(0f, 0, latencyFrames * channels)
        delayPosition = 0
        queueHead = 0
        queueSize = 0
        box.fill(1.0, 0, window)
        boxPosition = 0
        boxSum = window.toDouble()
        belowUnity = 0
        gain = 1.0
        frame = 0
    }

    /**
     * Takes the frame at [input] from [inputOffset] and writes the frame that came in [latencyFrames] earlier, limited,
     * to [output] from [outputOffset]. [output] may be [input], at the same offset. A frame taken with [limit] false
     * asks for no gain reduction: once the gain is back at unity such frames come out bit-identical, only delayed.
     */
    fun process(input: FloatArray, inputOffset: Int, output: FloatArray, outputOffset: Int, limit: Boolean = true) {
        var peak = 0f
        if (limit) for (ch in 0 until channels) peak = max(peak, abs(input[inputOffset + ch]))
        val needed = if (peak > CEILING) CEILING / peak.toDouble() else 1.0

        // Hold: the lowest needed gain of the window (the frame that left it first, then the higher ones it hides).
        if (queueSize > 0 && queueFrames[queueHead] <= frame - window) {
            queueHead = (queueHead + 1) % window
            queueSize--
        }
        while (queueSize > 0 && queueValues[(queueHead + queueSize - 1) % window] >= needed) queueSize--
        val tail = (queueHead + queueSize) % window
        queueValues[tail] = needed
        queueFrames[tail] = frame
        queueSize++
        val held = queueValues[queueHead]

        // Average: a straight fall over the window.
        val leaving = box[boxPosition]
        if (leaving < 1.0) belowUnity--
        if (held < 1.0) belowUnity++
        box[boxPosition] = held
        boxPosition = (boxPosition + 1) % window
        boxSum += held - leaving
        val smoothed = if (belowUnity == 0) {
            boxSum = window.toDouble()
            1.0
        } else {
            boxSum / window
        }

        // Down at once (already smooth), up on the release curve.
        gain = if (smoothed < gain) smoothed else gain + (smoothed - gain) * releaseCoefficient
        if (gain > 1 - UNITY_SNAP && smoothed == 1.0) gain = 1.0

        val p = delayPosition * channels
        for (ch in 0 until channels) {
            val incoming = input[inputOffset + ch]
            val leavingSample = delay[p + ch]
            delay[p + ch] = incoming
            output[outputOffset + ch] = if (gain == 1.0) leavingSample else (leavingSample * gain).toFloat()
        }
        delayPosition = (delayPosition + 1) % latencyFrames
        frame++
    }

    companion object {
        /** The highest sample value that comes out: −0.3 dBFS. */
        const val CEILING = 0.9660509f

        const val LOOKAHEAD_SECONDS = 0.004
        const val RELEASE_SECONDS = 0.05

        private const val UNITY_SNAP = 1e-6
    }
}
