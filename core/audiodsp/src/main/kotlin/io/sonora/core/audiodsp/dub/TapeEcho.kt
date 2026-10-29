package io.sonora.core.audiodsp.dub

import kotlin.math.roundToInt

/**
 * The dub stage's beat-synced tape echo: one [FractionalDelayLine] per channel (2.5 s ring, Hermite read, 50 ms time
 * smoother), wet only, times a smoothed RETURN level.
 *
 * - Time: [EchoTiming] from the tempo ([setTempo]) and the division ([setDivision]), in source frames. A change glides
 *   the read head with the line's 50 ms time constant (the tape pitch dive) and never jumps.
 * - Feedback: the slider value, 0..[MAX_USER_FEEDBACK] ([setFeedback], 20 ms ramp). Higher values only come from the
 *   two performance modes, and every feedback move is a linear ramp, so the loop gain never steps.
 * - FREEZE ([setFreeze]): the input closes (5 ms), feedback goes to [FREEZE_FEEDBACK] in front of the saturator (5 ms),
 *   the tape damping fades out (20 ms) and the loop saturator crossfades into a [KneeLimiter] (5 ms), unity below
 *   about -3 dBFS, so the held loop keeps its level and its top end even for a near full-scale throw (the stage runs
 *   before ReplayGain). On release the input reopens, the damping and the saturator come back and the feedback returns
 *   to the slider over 50 ms.
 * - RUNAWAY ([setRunaway]): feedback rises to [RUNAWAY_FEEDBACK] over 50 ms, so the loop self-oscillates; the loop
 *   saturator keeps every recirculated sample within +-1 (the output limiter downstream handles the sum); it stays the
 *   saturator even under FREEZE. On release the feedback glides back over 250 ms (to 1.0 while FREEZE is still held,
 *   with the knee limiter crossfading back in over the same 250 ms).
 *
 * Output: `outLeft`/`outRight` after [processFrame] (mono echoes copy left to right), or interleaved through
 * [process]. No NaN escapes: non-finite sends are silence, and every loop state is denormal-flushed.
 *
 * Real-time safe: [prepare] allocates (call it from configure); every other call is allocation-free and lock-free.
 * Not thread-safe: the audio thread owns it, controls arrive as already-snapshotted values.
 */
class TapeEcho {
    private val lines = arrayOf(FractionalDelayLine(), FractionalDelayLine())

    var sampleRate: Int = DEFAULT_SAMPLE_RATE
        private set

    var channelCount: Int = 2
        private set

    var bpm: Double = DEFAULT_BPM
        private set

    /** The requested division. */
    var division: EchoDivision = EchoDivision.QUARTER
        private set

    /** The division in use after the 2.0 s fallback. */
    var effectiveDivision: EchoDivision = EchoDivision.QUARTER
        private set

    /** The target echo time in frames (the read head glides towards it). */
    var delayFrames: Double = 0.0
        private set

    /** The slider feedback. */
    var feedback: Float = DEFAULT_FEEDBACK
        private set

    var returnLevel: Float = DEFAULT_RETURN
        private set

    var isFrozen: Boolean = false
        private set

    var isRunaway: Boolean = false
        private set

    var outLeft: Float = 0f
        private set

    var outRight: Float = 0f
        private set

    private val feedbackRamp = LinearRamp(DEFAULT_FEEDBACK.toDouble())
    private val inputGate = LinearRamp(1.0)
    private val damping = LinearRamp(1.0)
    private val loopKnee = LinearRamp(0.0)
    private val returnSmoother = Smoother().apply { setTarget(DEFAULT_RETURN.toDouble()) }

    /** The loop feedback in use this frame. */
    val currentFeedback: Float get() = feedbackRamp.value.toFloat()

    /** Where the read head is now, in frames. */
    val currentDelayFrames: Double get() = lines[0].currentDelayFrames

    init {
        prepare(DEFAULT_SAMPLE_RATE, 2)
    }

    /** Allocates the rings for [sampleRate] and [channels] (1 or 2) and resets. Not real-time safe. */
    fun prepare(sampleRate: Int, channels: Int) {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(channels in 1..MAX_CHANNELS) { "channels must be 1 or 2: $channels" }
        this.sampleRate = sampleRate
        channelCount = channels
        for (c in 0 until channels) {
            lines[c].prepare(sampleRate.toFloat(), RING_SECONDS)
            lines[c].setMix(1f)
        }
        returnSmoother.configure(RETURN_SMOOTHING_SECONDS, sampleRate.toDouble())
        retime()
        reset()
    }

    /** The tempo the time locks to; non-finite or non-positive values are ignored. */
    fun setTempo(bpm: Double) {
        if (!(bpm > 0.0) || bpm.isInfinite()) return
        this.bpm = bpm
        retime()
    }

    fun setDivision(division: EchoDivision) {
        this.division = division
        retime()
    }

    /** The slider value, clamped to [0, MAX_USER_FEEDBACK]; NaN maps to 0. */
    fun setFeedback(feedback: Float) {
        this.feedback = if (!(feedback >= 0f)) 0f else if (feedback > MAX_USER_FEEDBACK) MAX_USER_FEEDBACK else feedback
        retargetFeedback(SLIDER_SECONDS)
    }

    /** RETURN, clamped to [0, 1]; NaN maps to 0. Smoothed with a 20 ms time constant. */
    fun setReturnLevel(level: Float) {
        returnLevel = if (!(level >= 0f)) 0f else if (level > 1f) 1f else level
        returnSmoother.setTarget(returnLevel.toDouble())
    }

    fun setFreeze(on: Boolean) {
        if (on == isFrozen) return
        isFrozen = on
        inputGate.setTarget(if (on) 0.0 else 1.0, frames(INPUT_GATE_SECONDS))
        damping.setTarget(if (on) 0.0 else 1.0, frames(if (on) FREEZE_DAMPING_FADE_SECONDS else FREEZE_RELEASE_SECONDS))
        retargetFeedback(if (on) FREEZE_ENGAGE_SECONDS else FREEZE_RELEASE_SECONDS)
        retargetLoopKnee(if (on) FREEZE_ENGAGE_SECONDS else FREEZE_RELEASE_SECONDS)
    }

    fun setRunaway(on: Boolean) {
        if (on == isRunaway) return
        isRunaway = on
        retargetFeedback(if (on) RUNAWAY_ENGAGE_SECONDS else RUNAWAY_RELEASE_SECONDS)
        retargetLoopKnee(if (on) RUNAWAY_ENGAGE_SECONDS else RUNAWAY_RELEASE_SECONDS)
    }

    /** Clears the tails and snaps every glide to its target. Never allocates. */
    fun reset() {
        for (c in 0 until channelCount) lines[c].reset()
        feedbackRamp.snap()
        inputGate.snap()
        damping.snap()
        loopKnee.snap()
        returnSmoother.snap()
        outLeft = 0f
        outRight = 0f
    }

    /**
     * Processes one frame of the send; the echo return lands in [outLeft] and [outRight].
     *
     * With [pending] (the latency-compensated THROW), call it once per frame right after `history.write` and
     * `pending.advance()` for the same frame: every tap then also reads the pending send of the same age, and the
     * pending frame that is about to leave the pending window is committed into the ring, where it keeps its place.
     * The pending window must be shorter than the echo ring (400 ms against 2.5 s). The input gate (FREEZE) applies
     * to [sendLeft]/[sendRight] only: the owner releases the throw when FREEZE engages.
     */
    fun processFrame(sendLeft: Float, sendRight: Float, pending: PendingSendRing? = null) {
        val gate = inputGate.next().toFloat()
        val fb = feedbackRamp.next().toFloat()
        val damp = damping.next().toFloat()
        val knee = loopKnee.next().toFloat()
        val ret = returnSmoother.next().toFloat()

        val left = lines[0]
        left.setFeedback(fb)
        left.setDampingAmount(damp)
        left.setLoopKneeAmount(knee)
        outLeft = left.process(sendLeft * gate, pending, 0) * ret
        if (channelCount == 2) {
            val right = lines[1]
            right.setFeedback(fb)
            right.setDampingAmount(damp)
            right.setLoopKneeAmount(knee)
            outRight = right.process(sendRight * gate, pending, 1) * ret
        } else {
            outRight = outLeft
        }
        if (pending != null) commit(pending)
    }

    /**
     * Moves the oldest pending frame into the ring before the pending window drops it. Relative to the next write it
     * is `capacityFrames` frames old; the tap sum (ring + pending) for that frame is the same before and after.
     */
    private fun commit(pending: PendingSendRing) {
        val age = pending.capacityFrames
        if (age >= lines[0].ringFrames - FractionalDelayLine.INTERPOLATION_GUARD) return
        for (c in 0 until channelCount) {
            val v = pending.tap(age - 1, c)
            if (v != 0f) lines[c].addWritten(age, v)
        }
    }

    /** Interleaved block form of [processFrame]: [frames] frames of [channelCount] channels. */
    fun process(send: FloatArray, sendOffset: Int, out: FloatArray, outOffset: Int, frames: Int) {
        val ch = channelCount
        for (f in 0 until frames) {
            val i = sendOffset + f * ch
            val o = outOffset + f * ch
            if (ch == 2) {
                processFrame(send[i], send[i + 1])
                out[o] = outLeft
                out[o + 1] = outRight
            } else {
                processFrame(send[i], 0f)
                out[o] = outLeft
            }
        }
    }

    private fun retime() {
        effectiveDivision = EchoTiming.effectiveDivision(sampleRate, bpm, division)
        val frames = EchoTiming.delayFrames(sampleRate, bpm, division)
        for (c in 0 until channelCount) lines[c].setDelayFrames(frames)
        delayFrames = lines[0].delayFrames
    }

    private fun retargetFeedback(seconds: Double) {
        val target = when {
            isRunaway -> RUNAWAY_FEEDBACK
            isFrozen -> FREEZE_FEEDBACK
            else -> feedback
        }.toDouble()
        if (target != feedbackRamp.target) feedbackRamp.setTarget(target, frames(seconds))
    }

    /** The knee limiter holds the loop only under FREEZE without RUNAWAY; otherwise the loop is the saturator. */
    private fun retargetLoopKnee(seconds: Double) {
        val target = if (isFrozen && !isRunaway) 1.0 else 0.0
        if (target != loopKnee.target) loopKnee.setTarget(target, frames(seconds))
    }

    private fun frames(seconds: Double): Int = (seconds * sampleRate).roundToInt()

    companion object {
        const val MAX_CHANNELS = 2
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val DEFAULT_BPM = 120.0
        const val DEFAULT_FEEDBACK = 0.55f
        const val DEFAULT_RETURN = 0.8f
        const val MAX_USER_FEEDBACK = 0.95f
        const val FREEZE_FEEDBACK = 1f
        const val RUNAWAY_FEEDBACK = 1.12f
        const val RING_SECONDS = FractionalDelayLine.MAX_TIME_MS / 1_000f

        const val SLIDER_SECONDS = 0.02
        const val INPUT_GATE_SECONDS = 0.005
        const val FREEZE_ENGAGE_SECONDS = 0.005
        const val FREEZE_DAMPING_FADE_SECONDS = 0.02
        const val FREEZE_RELEASE_SECONDS = 0.05
        const val RUNAWAY_ENGAGE_SECONDS = 0.05
        const val RUNAWAY_RELEASE_SECONDS = 0.25
        const val RETURN_SMOOTHING_SECONDS = 0.02
    }
}
