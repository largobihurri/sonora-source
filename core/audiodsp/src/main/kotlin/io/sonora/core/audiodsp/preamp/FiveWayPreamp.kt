package io.sonora.core.audiodsp.preamp

import io.sonora.core.audiodsp.filter.LinkwitzRiley4
import io.sonora.core.audiodsp.filter.SvfCoefficients
import io.sonora.core.audiodsp.filter.SvfState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The five-way output preamp of the DUB stage (design 10.1): the signal is split into five ways, each a band-pass of
 * its own Linkwitz-Riley 24 dB/oct high-pass and low-pass corner, with its own fader and kill, and the ways sum back
 * to the same channels (mono or stereo, interleaved float).
 *
 * - Corners: 20 Hz - 20 kHz. A high-pass at 20 Hz or a low-pass at 20 kHz is "off", a true bypass of that filter. A
 *   corner move glides over 20 ms on a log scale (coefficients recomputed per sample only while it moves), and turning
 *   a corner on or off crossfades over the same 20 ms, so a sweep never clicks.
 * - Defaults ([defaultHighPassHz], [defaultLowPassHz]): 20/80, 80/250, 250/1200, 1200/5000, 5000/20000 Hz.
 * - Fader: linear gain 0..+6 dB ([PreampFader] maps a fader position, with the 0 dB detent), smoothed over 10 ms.
 * - Kill: 5 ms linear ramp to exactly zero. DROP kills the ways of [dropWays] (default ways 1 and 2) while it is held.
 *
 * Flat sum: each way's magnitude is exactly its own high-pass times low-pass. To make adjacent ways with equal corners
 * add up flat, every way also passes through second-order allpasses at the crossover frequencies that are not its own
 * corners (the phase a Linkwitz-Riley crossover gives its sum). Those allpasses never change a way's magnitude; with the
 * default corners the five ways sum flat within 0.08 dB from 20 Hz to 20 kHz. The allpasses are shared through the
 * signal flow: three in a chain on the input (feeding ways 3, 4 and 5) and three nested in the output sum (after
 * ways 1, 2 and 3), so a stereo frame costs at most 2 x (10 Linkwitz-Riley + 6 allpass) second-order sections.
 *
 * Ways are indexed 0..4 (way 1 is index 0). Math is in double precision; times are in seconds with the sample rate of
 * [prepare], so the response is the same at every rate. Every filter is allocated in the constructor; [prepare],
 * the setters and [process] allocate nothing and take no locks. Call them from the audio thread only. Non-finite input
 * is treated as silence.
 *
 * The 26 sections of a channel (3 + 3 allpasses, 10 corners of 2) run in one loop over flat state arrays, the same
 * arithmetic as [SvfState] sample by sample (these sections are linear, so its knee never applies): a chain of small
 * objects per section cost three times as much on ART, and over the 15 % CPU budget of the DUB stage (D-11,
 * `odd/tasks/dub-mode.md` L23).
 */
class FiveWayPreamp {

    // Section states, per channel, two integrators each: [section * STATE_STRIDE + channel * 2 + (0 | 1)].
    private val state = DoubleArray(SECTION_COUNT * STATE_STRIDE)

    private val highPass = Array(WAY_COUNT) { Corner(highPass = true, DEFAULT_HIGH_PASS_HZ[it], state, highPassSection(it)) }
    private val lowPass = Array(WAY_COUNT) { Corner(highPass = false, DEFAULT_LOW_PASS_HZ[it], state, lowPassSection(it)) }

    // Crossover allpasses: boundary b sits between way b and way b + 1 (0-based).
    private val inputAllPass = Array(3) { SvfCoefficients() } // boundaries 0, 1, 2
    private val outputAllPass = Array(3) { SvfCoefficients() } // boundaries 1, 2, 3

    private val fader = Array(WAY_COUNT) { Ramp(1.0) }
    private val kill = Array(WAY_COUNT) { Ramp(1.0) }
    private val gain = DoubleArray(WAY_COUNT) { 1.0 }
    private val killed = BooleanArray(WAY_COUNT)
    private var dropActive = false

    private var channels = 0
    private var sampleRate = 48_000.0

    /** Ways that DROP kills, as a bit mask (bit 0 = way 1). */
    var dropWays: Int = DEFAULT_DROP_WAYS
        private set

    init {
        for (b in 0 until WAY_COUNT - 1) updateBoundary(b)
    }

    /** Sets the sample rate and channel count (1 or 2); every ramp jumps to its target and the filters are cleared. */
    fun prepare(sampleRateHz: Int, channels: Int) {
        require(sampleRateHz > 0) { "sampleRateHz must be positive: $sampleRateHz" }
        require(channels in 1..LinkwitzRiley4.MAX_CHANNELS) { "channels must be 1..2: $channels" }
        this.channels = channels
        sampleRate = sampleRateHz.toDouble()
        val glideFrames = framesFor(CORNER_GLIDE_SECONDS)
        for (c in highPass) c.prepare(sampleRate, glideFrames)
        for (c in lowPass) c.prepare(sampleRate, glideFrames)
        for (r in fader) r.prepare(framesFor(FADER_SECONDS))
        for (r in kill) r.prepare(framesFor(KILL_SECONDS))
        for (w in 0 until WAY_COUNT) gain[w] = fader[w].value * kill[w].value
        for (b in 0 until WAY_COUNT - 1) updateBoundary(b)
        state.fill(0.0)
    }

    /** Clears the filter states, keeping every setting. */
    fun reset() = state.fill(0.0)

    /** High-pass corner of [way] in Hz, clamped to 20 Hz - 20 kHz; 20 Hz (or below) is off. */
    fun setHighPassHz(way: Int, hz: Float) = highPass[way].setTarget(hz)

    /** Low-pass corner of [way] in Hz, clamped to 20 Hz - 20 kHz; 20 kHz (or above) is off. */
    fun setLowPassHz(way: Int, hz: Float) = lowPass[way].setTarget(hz)

    fun highPassHz(way: Int): Float = highPass[way].targetHz

    fun lowPassHz(way: Int): Float = lowPass[way].targetHz

    /** Linear fader gain of [way], clamped to 0..[PreampFader.MAX_GAIN]; non-finite means 0. */
    fun setFaderGain(way: Int, linearGain: Float) {
        val g = if (linearGain.isFinite()) linearGain.coerceIn(0f, PreampFader.MAX_GAIN) else 0f
        fader[way].setTarget(g.toDouble())
    }

    fun setKilled(way: Int, killed: Boolean) {
        this.killed[way] = killed
        updateKillTargets()
    }

    fun isKilled(way: Int): Boolean = killed[way]

    /** DROP held ([active]) kills the ways of [dropWays] on top of their own kill switches. */
    fun setDropActive(active: Boolean) {
        dropActive = active
        updateKillTargets()
    }

    /** Sets the ways DROP kills, as a bit mask (bit 0 = way 1); bits above way 5 are ignored. */
    fun setDropWays(mask: Int) {
        dropWays = mask and ALL_WAYS
        updateKillTargets()
    }

    /** Processes `frames` interleaved frames of `buffer` in place, from `offset`. */
    fun process(buffer: FloatArray, offset: Int, frames: Int) {
        val ch = channels
        if (ch == 0) return
        val inAp = inputAllPass
        val outAp = outputAllPass
        val g = gain
        var i = offset
        val end = offset + frames * ch
        while (i < end) {
            advanceControls()
            for (c in 0 until ch) {
                val raw = buffer[i]
                val x = if (raw.isFinite()) raw.toDouble() else 0.0
                val at = c * 2
                val x1 = allPass(inAp[0], INPUT_ALL_PASS + at, x)
                val x2 = allPass(inAp[1], INPUT_ALL_PASS + STATE_STRIDE + at, x1)
                val x3 = allPass(inAp[2], INPUT_ALL_PASS + 2 * STATE_STRIDE + at, x2)
                val w0 = way(0, at, x)
                val w1 = way(1, at, x)
                val w2 = way(2, at, x1)
                val w3 = way(3, at, x2)
                val w4 = way(4, at, x3)
                var sum = allPass(outAp[0], OUTPUT_ALL_PASS + at, g[0] * w0) + g[1] * w1
                sum = allPass(outAp[1], OUTPUT_ALL_PASS + STATE_STRIDE + at, sum) + g[2] * w2
                sum = allPass(outAp[2], OUTPUT_ALL_PASS + 2 * STATE_STRIDE + at, sum) + g[3] * w3 + g[4] * w4
                buffer[i] = if (sum.isFinite()) sum.toFloat() else 0f
                i++
            }
        }
    }

    /** One way of one channel (state offset [at]): its high-pass corner, then its low-pass corner. */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun way(w: Int, at: Int, x: Double): Double = lowPass[w].let { lp ->
        val h = highPass[w].let { hp -> mixed(hp.mix, x) { corner(hp.coefficients, hp.section + at, x, high = true) } }
        mixed(lp.mix, h) { corner(lp.coefficients, lp.section + at, h, high = false) }
    }

    /** A corner crossfaded with its input by [mix]: skipped at 0, the filter alone at 1. */
    private inline fun mixed(mix: Double, x: Double, filter: () -> Double): Double {
        if (mix == 0.0) return x
        val y = filter()
        return if (mix == 1.0) y else x + mix * (y - x)
    }

    /** A Linkwitz-Riley corner: two Butterworth sections (states at [at] and the next section's), as LinkwitzRiley4. */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun corner(c: SvfCoefficients, at: Int, input: Double, high: Boolean): Double {
        val x = if (input.isFinite()) input else 0.0
        return if (high) {
            tick(c, at + STATE_STRIDE, tick(c, at, x) { v, b, l, k -> v - k * b - l }) { v, b, l, k -> v - k * b - l }
        } else {
            tick(c, at + STATE_STRIDE, tick(c, at, x) { _, _, l, _ -> l }) { _, _, l, _ -> l }
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun allPass(c: SvfCoefficients, at: Int, x: Double): Double =
        tick(c, at, x) { v, b, _, k -> v - 2.0 * k * b }

    /** [SvfState]'s tick for the state at [at] (linear: no knee), returning the [response] of its input and nodes. */
    private inline fun tick(
        c: SvfCoefficients,
        at: Int,
        x: Double,
        response: (x: Double, band: Double, low: Double, k: Double) -> Double,
    ): Double {
        val s = state
        val ic1 = s[at]
        val ic2 = s[at + 1]
        val v3 = x - ic2
        val v1 = c.a1 * ic1 + c.a2 * v3
        val v2 = ic2 + c.a2 * ic1 + c.a3 * v3
        s[at] = flush(2.0 * v1 - ic1)
        s[at + 1] = flush(2.0 * v2 - ic2)
        return response(x, v1, v2, c.k)
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun flush(v: Double): Double = if (abs(v) < SvfState.DENORMAL_FLOOR) 0.0 else v

    private fun advanceControls() {
        for (w in 0 until WAY_COUNT) {
            if (highPass[w].advance() && w > 0) updateBoundary(w - 1)
            if (lowPass[w].advance() && w < WAY_COUNT - 1) updateBoundary(w)
            val f = fader[w]
            val k = kill[w]
            if (f.advance() or k.advance()) gain[w] = f.value * k.value
        }
    }

    private fun updateBoundary(b: Int) {
        val hz = sqrt(lowPass[b].currentHz * highPass[b + 1].currentHz)
        if (b <= 2) inputAllPass[b].set(hz, LinkwitzRiley4.BUTTERWORTH_Q, sampleRate)
        if (b >= 1) outputAllPass[b - 1].set(hz, LinkwitzRiley4.BUTTERWORTH_Q, sampleRate)
    }

    private fun updateKillTargets() {
        for (w in 0 until WAY_COUNT) {
            val off = killed[w] || (dropActive && (dropWays shr w) and 1 == 1)
            kill[w].setTarget(if (off) 0.0 else 1.0)
        }
    }

    private fun framesFor(seconds: Double): Int = max(1, (seconds * sampleRate).roundToInt())

    companion object {
        const val WAY_COUNT = 5
        const val MIN_CORNER_HZ = 20f
        const val MAX_CORNER_HZ = 20_000f

        /** DROP kills ways 1 and 2 by default. */
        const val DEFAULT_DROP_WAYS = 0b00011

        const val CORNER_GLIDE_SECONDS = 0.020
        const val FADER_SECONDS = 0.010
        const val KILL_SECONDS = 0.005

        private const val ALL_WAYS = (1 shl WAY_COUNT) - 1
        private val DEFAULT_HIGH_PASS_HZ = floatArrayOf(MIN_CORNER_HZ, 80f, 250f, 1200f, 5000f)
        private val DEFAULT_LOW_PASS_HZ = floatArrayOf(80f, 250f, 1200f, 5000f, MAX_CORNER_HZ)

        // State layout: per section two channels of two integrators. Sections: 3 input allpasses, the 5 high-pass
        // corners (2 sections each), the 5 low-pass corners (2 each), 3 output allpasses.
        internal const val STATE_STRIDE = LinkwitzRiley4.MAX_CHANNELS * 2
        private const val SECTION_COUNT = 3 + 2 * WAY_COUNT * 2 + 3
        private const val INPUT_ALL_PASS = 0
        private const val OUTPUT_ALL_PASS = (3 + 2 * WAY_COUNT * 2) * STATE_STRIDE

        private fun highPassSection(way: Int) = (3 + 2 * way) * STATE_STRIDE
        private fun lowPassSection(way: Int) = (3 + 2 * WAY_COUNT + 2 * way) * STATE_STRIDE

        fun defaultHighPassHz(way: Int): Float = DEFAULT_HIGH_PASS_HZ[way]

        fun defaultLowPassHz(way: Int): Float = DEFAULT_LOW_PASS_HZ[way]
    }
}

/** A linear ramp that reaches its target in a fixed number of frames and then holds it exactly. */
internal class Ramp(initial: Double) {
    var value = initial
        private set
    private var target = initial
    private var step = 0.0
    private var frames = 1

    fun prepare(frames: Int) {
        this.frames = frames
        value = target
        step = 0.0
    }

    fun setTarget(target: Double) {
        if (target == this.target) return
        this.target = target
        step = (target - value) / frames
    }

    /** Advances one frame; true when the value changed. */
    fun advance(): Boolean {
        if (value == target) return false
        val next = value + step
        value = if ((step > 0 && next >= target) || (step <= 0 && next <= target)) target else next
        return true
    }
}

/**
 * One Linkwitz-Riley corner of a way with its glide and its on/off crossfade. "Off" is the range end (20 Hz for a
 * high-pass, 20 kHz for a low-pass): the filter then fades out and is skipped, a true bypass. Its two sections' states
 * live in the preamp's [state], from [section]; the preamp runs them with [coefficients] and [mix].
 */
internal class Corner(private val highPass: Boolean, defaultHz: Float, private val state: DoubleArray, val section: Int) {
    val coefficients = SvfCoefficients()
    private val offHz = if (highPass) FiveWayPreamp.MIN_CORNER_HZ else FiveWayPreamp.MAX_CORNER_HZ
    private var sampleRate = 48_000.0
    private var glideFrames = 1

    var targetHz: Float = defaultHz
        private set
    private var logTarget = log2(defaultHz.toDouble())
    private var logHz = logTarget
    private var logStep = 0.0
    private var remaining = 0

    /** The filter's share of the output, 0 (bypassed) to 1. */
    var mix = if (isOff(defaultHz)) 0.0 else 1.0
        private set
    private var mixTarget = mix

    /** Current (gliding) corner in Hz; the range end while off. */
    val currentHz: Double get() = exp2(logHz)

    init {
        setCutoff()
    }

    fun prepare(sampleRateHz: Double, glideFrames: Int) {
        sampleRate = sampleRateHz
        this.glideFrames = glideFrames
        logHz = logTarget
        remaining = 0
        mix = mixTarget
        setCutoff()
        clearState()
    }

    fun setTarget(hz: Float) {
        val clamped = if (hz.isNaN()) offHz else hz.coerceIn(FiveWayPreamp.MIN_CORNER_HZ, FiveWayPreamp.MAX_CORNER_HZ)
        if (clamped == targetHz) return
        targetHz = clamped
        val off = isOff(clamped)
        if (!off && mix == 0.0 && mixTarget == 0.0) {
            // Turning on from a full bypass: start clean from the range end.
            clearState()
            logHz = log2(offHz.toDouble())
        }
        logTarget = log2((if (off) offHz else clamped).toDouble())
        logStep = (logTarget - logHz) / glideFrames
        remaining = glideFrames
        mixTarget = if (off) 0.0 else 1.0
    }

    /** Advances one frame; true when the corner frequency moved. */
    fun advance(): Boolean {
        if (mix != mixTarget) {
            val step = 1.0 / glideFrames
            mix = if (mixTarget > mix) minOf(mixTarget, mix + step) else maxOf(mixTarget, mix - step)
        }
        if (remaining == 0) return false
        remaining--
        logHz = if (remaining == 0) logTarget else logHz + logStep
        setCutoff()
        return true
    }

    private fun setCutoff() = coefficients.set(currentHz, LinkwitzRiley4.BUTTERWORTH_Q, sampleRate)

    private fun clearState() = state.fill(0.0, section, section + 2 * FiveWayPreamp.STATE_STRIDE)

    private fun isOff(hz: Float) = if (highPass) hz <= FiveWayPreamp.MIN_CORNER_HZ else hz >= FiveWayPreamp.MAX_CORNER_HZ

    private companion object {
        fun log2(v: Double) = kotlin.math.log2(v)
        fun exp2(v: Double) = Math.pow(2.0, v)
    }
}
