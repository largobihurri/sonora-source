package io.sonora.core.audiodsp.dub

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp

/**
 * Mono tape-style delay line whose feedback can self-oscillate (ported from the developer's own siren engine).
 *
 * ```
 * wet   = ring read at the (smoothed, fractional) delay time
 * loop  = saturate(feedback * dcBlock(damp(wet)))
 * write : ring <- input + loop
 * out   = (1 - mix) * input + mix * wet          (equal-gain crossfade)
 * ```
 *
 * Echo amplitudes (small signals): echo 1 is an exact copy of the input scaled by mix; echo k >= 2 carries
 * `mix * feedback^(k-1)` of the input's area, softened in peak and brightness by the tape damping. The first echo is
 * undamped because the damping lives only in the loop.
 *
 * Time: [MIN_TIME_MS]..[MAX_TIME_MS] (or the capacity given to [prepare]), smoothed with a one-pole (time constant
 * [TIME_SMOOTHING_SECONDS], about 95 % settled after 150 ms) and read with 4-point cubic Hermite (Catmull-Rom)
 * interpolation. Moving the time therefore slews the read head like a tape echo (a pitch glide) instead of jumping
 * (a click). Hermite rather than linear: linear interpolation is a fraction-dependent low-pass, so a gliding time
 * would modulate brightness and every recirculation would dull the echo further.
 *
 * Feedback 0..[MAX_FEEDBACK]. Above 1 the loop self-oscillates; it stays bounded because the saturator caps every
 * recirculated sample to [-1, 1]. Loop elements: [SoftSaturator]; a fixed one-pole low-pass at [DAMPING_HZ] (each
 * repeat gets darker, and a runaway loop settles into a warm drone instead of a whistle), which [setDampingAmount]
 * can fade out; a [DcBlocker] at [DC_BLOCK_HZ]. [setLoopKneeAmount] crossfades the saturator into a [KneeLimiter],
 * which is unity below its knee, so a held loop at feedback 1.0 keeps its level at loud program levels.
 *
 * Denormals: loop states and ring writes below [DENORMAL_THRESHOLD] are flushed to zero. Setters clamp (NaN maps to the
 * minimum). Non-finite input (or beyond +-1e6) is treated as silence, so one bad sample can never poison the ring.
 *
 * Real-time safe: only the constructor and [prepare] allocate; [reset], the setters and [process] never allocate,
 * lock or throw. A default instance behaves as if `prepare(48000)` had been called. Not thread-safe.
 */
class FractionalDelayLine {
    private var ring = FloatArray(0)
    private var writeIndex = 0

    /** Frames per second the time and the filters are computed for. */
    var sampleRate: Float = DEFAULT_SAMPLE_RATE
        private set

    /** The longest time this line can hold, from the last [prepare]. */
    var maxTimeMs: Float = MAX_TIME_MS
        private set

    /** The requested delay time (the smoothed read head glides towards it). */
    var timeMs: Float = DEFAULT_TIME_MS
        private set

    /** The requested delay in frames (the smoothed read head glides towards it). */
    var delayFrames: Double = 0.0
        private set

    var feedback: Float = DEFAULT_FEEDBACK
        private set

    private val delaySmoother = Smoother()
    private val mixSmoother = Smoother().apply { setTarget(DEFAULT_MIX.toDouble()) }
    private val dcBlocker = DcBlocker()
    private var dampCoefficient = 0f
    private var dampAmount = 1f
    private var dampState = 0f
    private var kneeAmount = 0f

    /** The requested mix (the smoothed mix glides towards it). */
    val mix: Float get() = mixSmoother.target.toFloat()

    /** Where the read head is now, in frames (it trails [delayFrames] during a glide). */
    val currentDelayFrames: Double get() = delaySmoother.value

    /** Frames the ring holds, including the interpolation guard. */
    val ringFrames: Int get() = ring.size

    init {
        prepare(DEFAULT_SAMPLE_RATE)
    }

    /**
     * Allocates the ring for up to [maxSeconds] (clamped to the time range; NaN means the maximum), recomputes the
     * coefficients and resets. Non-positive or NaN sample rates are ignored. Not real-time safe: call it from
     * configure, never per buffer.
     */
    fun prepare(sampleRate: Float, maxSeconds: Float = MAX_TIME_MS / 1_000f) {
        if (!(sampleRate > 0f) || sampleRate.isInfinite()) return
        this.sampleRate = sampleRate
        val maxMs = maxSeconds * 1_000f
        maxTimeMs = if (maxMs.isNaN()) MAX_TIME_MS else maxMs.coerceIn(MIN_TIME_MS, MAX_TIME_MS)
        val capacity = ceil(maxTimeMs.toDouble() * sampleRate / 1_000.0).toInt()
        ring = FloatArray(capacity + INTERPOLATION_GUARD)

        delaySmoother.configure(TIME_SMOOTHING_SECONDS, sampleRate.toDouble())
        mixSmoother.configure(MIX_SMOOTHING_SECONDS, sampleRate.toDouble())
        // One-pole low-pass y += (1 - a)(x - y) with a = e^(-2 pi fc / fs).
        dampCoefficient = exp(-TWO_PI_F * DAMPING_HZ / sampleRate)
        dcBlocker.prepare(DC_BLOCK_HZ, sampleRate)

        // Re-clamp the time to the new capacity, then snap the read head to it: a glide left over from the previous
        // configuration must never point past the new ring.
        setTimeMs(timeMs)
        reset()
    }

    /** Clamped to [MIN_TIME_MS, maxTimeMs]; NaN maps to the minimum. */
    fun setTimeMs(ms: Float) {
        // Written so that NaN fails the first comparison and lands on the minimum.
        val clamped = if (!(ms >= MIN_TIME_MS)) MIN_TIME_MS else if (ms > maxTimeMs) maxTimeMs else ms
        timeMs = clamped
        delayFrames = clamped.toDouble() * sampleRate / 1_000.0
        delaySmoother.setTarget(delayFrames)
    }

    /** The delay in frames (beat-synced times are exact frame counts), clamped like [setTimeMs]. */
    fun setDelayFrames(frames: Double) {
        val min = MIN_TIME_MS.toDouble() * sampleRate / 1_000.0
        val max = maxTimeMs.toDouble() * sampleRate / 1_000.0
        val clamped = if (!(frames >= min)) min else if (frames > max) max else frames
        delayFrames = clamped
        timeMs = (clamped * 1_000.0 / sampleRate).toFloat()
        delaySmoother.setTarget(clamped)
    }

    /** Clamped to [0, MAX_FEEDBACK]; NaN maps to 0. */
    fun setFeedback(feedback: Float) {
        this.feedback = if (!(feedback >= 0f)) 0f else if (feedback > MAX_FEEDBACK) MAX_FEEDBACK else feedback
    }

    /** Clamped to [0, 1]; NaN maps to 0. */
    fun setMix(mix: Float) {
        mixSmoother.setTarget((if (!(mix >= 0f)) 0f else if (mix > 1f) 1f else mix).toDouble())
    }

    /**
     * How much of the tape damping the loop applies, 0 (none: the loop is flat) to 1 (the full [DAMPING_HZ] low-pass);
     * NaN maps to 1. Changing it per frame is click-free: it only moves the one-pole's coefficient.
     */
    fun setDampingAmount(amount: Float) {
        dampAmount = if (!(amount <= 1f)) 1f else if (amount < 0f) 0f else amount
    }

    /**
     * How much of the loop's limiting is the [KneeLimiter] instead of the [SoftSaturator], 0 (the saturator) to 1 (the
     * knee limiter); NaN maps to 0. Both are bounded by +-1, so any blend is too; changing it per frame is click-free.
     */
    fun setLoopKneeAmount(amount: Float) {
        kneeAmount = if (!(amount >= 0f)) 0f else if (amount > 1f) 1f else amount
    }

    /** Clears the ring and the loop filters (the tail stops) and snaps the smoothed time and mix. Never allocates. */
    fun reset() {
        ring.fill(0f)
        writeIndex = 0
        dampState = 0f
        dcBlocker.reset()
        delaySmoother.snap()
        mixSmoother.snap()
    }

    /** Processes one frame. */
    fun process(input: Float): Float = process(input, null, 0)

    /**
     * Processes one frame, reading every tap as the ring sample plus [overlay]'s sample of the same age for
     * [channel]: the overlay acts as extra input written in the past (the latency-compensated send), so it is heard
     * at the read head and recirculates through the loop like any other input.
     */
    fun process(input: Float, overlay: DelayOverlay?, channel: Int): Float {
        val x = if (abs(input) <= MAX_INPUT) input else 0f
        val wet = readDelayed(delaySmoother.next(), overlay, channel)

        // Feedback loop: tape damping -> DC blocker -> gain -> saturator (blended into the knee limiter when held).
        dampState = flushDenormal(wet + dampCoefficient * dampAmount * (dampState - wet))
        val gained = feedback * dcBlocker.process(dampState)
        val saturated = SoftSaturator.process(gained)
        val loop =
            if (kneeAmount == 0f) saturated else saturated + kneeAmount * (KneeLimiter.process(gained) - saturated)

        ring[writeIndex] = flushDenormal(x + loop)
        if (++writeIndex == ring.size) writeIndex = 0

        val mix = mixSmoother.next().toFloat()
        return (1f - mix) * x + mix * wet
    }

    /**
     * Adds [value] to the frame written [framesAgo] frames before the next write (1 is the frame just written), as if
     * it had been part of that frame's input. Ages outside [1, ringFrames - 1] and non-finite values are ignored.
     */
    fun addWritten(framesAgo: Int, value: Float) {
        if (framesAgo < 1 || framesAgo >= ring.size || !(abs(value) <= MAX_INPUT)) return
        var i = writeIndex - framesAgo
        if (i < 0) i += ring.size
        ring[i] = flushDenormal(ring[i] + value)
    }

    private fun readDelayed(delay: Double, overlay: DelayOverlay?, channel: Int): Float {
        // prepare() re-clamps the time and snaps the smoother, so the delay already fits the ring; this clamp keeps
        // the taps (whole - 1 .. whole + 2) inside [0, size) whatever the caller did.
        val maxDelay = (ring.size - INTERPOLATION_GUARD).toDouble()
        val d = if (!(delay >= 1.0)) 1.0 else if (delay > maxDelay) maxDelay else delay
        val whole = d.toInt()
        val t = (d - whole).toFloat()
        // 4-point, 3rd-order Hermite (Catmull-Rom) between delays `whole` and `whole + 1`.
        val xm1 = at(whole - 1, overlay, channel)
        val x0 = at(whole, overlay, channel)
        val x1 = at(whole + 1, overlay, channel)
        val x2 = at(whole + 2, overlay, channel)
        val c1 = 0.5f * (x1 - xm1)
        val c2 = xm1 - 2.5f * x0 + 2f * x1 - 0.5f * x2
        val c3 = 0.5f * (x2 - xm1) + 1.5f * (x0 - x1)
        return ((c3 * t + c2) * t + c1) * t + x0
    }

    /** The frame written [framesAgo] frames before the one about to be written, plus the overlay's sample. */
    private fun at(framesAgo: Int, overlay: DelayOverlay?, channel: Int): Float {
        var i = writeIndex - framesAgo
        if (i < 0) i += ring.size
        return if (overlay == null) ring[i] else ring[i] + overlay.tap(framesAgo, channel)
    }

    companion object {
        const val MIN_TIME_MS = 20f

        /** The ring length: 2.5 s, so a 2.0 s echo has headroom for a glide. */
        const val MAX_TIME_MS = 2_500f
        const val DEFAULT_TIME_MS = 375f
        const val MAX_FEEDBACK = 1.2f
        const val DEFAULT_FEEDBACK = 0.5f
        const val DEFAULT_MIX = 0.35f
        const val TIME_SMOOTHING_SECONDS = 0.05
        const val MIX_SMOOTHING_SECONDS = 0.02
        const val DAMPING_HZ = 4_500f
        const val DC_BLOCK_HZ = 5f
        const val DEFAULT_SAMPLE_RATE = 48_000f

        /** Largest finite input accepted; anything beyond (infinity, NaN) is treated as silence. */
        private const val MAX_INPUT = 1e6f

        /** Hermite reads one frame newer and two older than the integer delay. */
        internal const val INTERPOLATION_GUARD = 4
    }
}

/**
 * Extra input for a [FractionalDelayLine], read at the taps: [tap] returns the sample for the frame [framesAgo] frames
 * before the one being processed (0 is that frame). It must be allocation-free and return finite values.
 */
fun interface DelayOverlay {
    fun tap(framesAgo: Int, channel: Int): Float
}
