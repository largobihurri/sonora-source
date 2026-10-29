package io.sonora.core.audiodsp.preamp

import io.sonora.core.audiodsp.filter.LinkwitzRiley4
import io.sonora.core.audiodsp.filter.SecondOrderAllPass
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The per-sample FiveWayPreamp before the fused loop (D-11, L23), kept as the bit-exact reference of its tests. */
internal class ReferenceFiveWayPreamp {

    private val highPass = Array(WAY_COUNT) { ReferenceCorner(highPass = true, DEFAULT_HIGH_PASS_HZ[it]) }
    private val lowPass = Array(WAY_COUNT) { ReferenceCorner(highPass = false, DEFAULT_LOW_PASS_HZ[it]) }

    // Crossover allpasses: boundary b sits between way b and way b + 1 (0-based).
    private val inputAllPass = Array(3) { SecondOrderAllPass() } // boundaries 0, 1, 2
    private val outputAllPass = Array(3) { SecondOrderAllPass() } // boundaries 1, 2, 3

    private val fader = Array(WAY_COUNT) { ReferenceRamp(1.0) }
    private val kill = Array(WAY_COUNT) { ReferenceRamp(1.0) }
    private val gain = DoubleArray(WAY_COUNT) { 1.0 }
    private val killed = BooleanArray(WAY_COUNT)
    private var dropActive = false

    private var channels = 0
    private var sampleRate = 48_000.0

    /** Ways that DROP kills, as a bit mask (bit 0 = way 1). */
    var dropWays: Int = DEFAULT_DROP_WAYS
        private set

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
        for (a in inputAllPass) a.reset()
        for (a in outputAllPass) a.reset()
    }

    /** Clears the filter states, keeping every setting. */
    fun reset() {
        for (c in highPass) c.reset()
        for (c in lowPass) c.reset()
        for (a in inputAllPass) a.reset()
        for (a in outputAllPass) a.reset()
    }

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
        val hp = highPass
        val lp = lowPass
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
                val x1 = inAp[0].process(x, c)
                val x2 = inAp[1].process(x1, c)
                val x3 = inAp[2].process(x2, c)
                val w0 = lp[0].process(hp[0].process(x, c), c)
                val w1 = lp[1].process(hp[1].process(x, c), c)
                val w2 = lp[2].process(hp[2].process(x1, c), c)
                val w3 = lp[3].process(hp[3].process(x2, c), c)
                val w4 = lp[4].process(hp[4].process(x3, c), c)
                var sum = outAp[0].process(g[0] * w0, c) + g[1] * w1
                sum = outAp[1].process(sum, c) + g[2] * w2
                sum = outAp[2].process(sum, c) + g[3] * w3 + g[4] * w4
                buffer[i] = if (sum.isFinite()) sum.toFloat() else 0f
                i++
            }
        }
    }

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
        if (b <= 2) inputAllPass[b].setCenter(hz, sampleRate)
        if (b >= 1) outputAllPass[b - 1].setCenter(hz, sampleRate)
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

        fun defaultHighPassHz(way: Int): Float = DEFAULT_HIGH_PASS_HZ[way]

        fun defaultLowPassHz(way: Int): Float = DEFAULT_LOW_PASS_HZ[way]
    }
}

/** A linear ramp that reaches its target in a fixed number of frames and then holds it exactly. */
private class ReferenceRamp(initial: Double) {
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
 * high-pass, 20 kHz for a low-pass): the filter then fades out and is skipped, a true bypass.
 */
private class ReferenceCorner(private val highPass: Boolean, defaultHz: Float) {
    private val filter = LinkwitzRiley4(if (highPass) LinkwitzRiley4.Type.HIGH_PASS else LinkwitzRiley4.Type.LOW_PASS)
    private val offHz = if (highPass) FiveWayPreamp.MIN_CORNER_HZ else FiveWayPreamp.MAX_CORNER_HZ
    private var sampleRate = 48_000.0
    private var glideFrames = 1

    var targetHz: Float = defaultHz
        private set
    private var logTarget = log2(defaultHz.toDouble())
    private var logHz = logTarget
    private var logStep = 0.0
    private var remaining = 0
    private var mix = if (isOff(defaultHz)) 0.0 else 1.0
    private var mixTarget = mix

    /** Current (gliding) corner in Hz; the range end while off. */
    val currentHz: Double get() = exp2(logHz)

    fun prepare(sampleRateHz: Double, glideFrames: Int) {
        sampleRate = sampleRateHz
        this.glideFrames = glideFrames
        logHz = logTarget
        remaining = 0
        mix = mixTarget
        filter.setCutoff(currentHz, sampleRate)
        filter.reset()
    }

    fun reset() = filter.reset()

    fun setTarget(hz: Float) {
        val clamped = if (hz.isNaN()) offHz else hz.coerceIn(FiveWayPreamp.MIN_CORNER_HZ, FiveWayPreamp.MAX_CORNER_HZ)
        if (clamped == targetHz) return
        targetHz = clamped
        val off = isOff(clamped)
        if (!off && mix == 0.0 && mixTarget == 0.0) {
            // Turning on from a full bypass: start clean from the range end.
            filter.reset()
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
        filter.setCutoff(currentHz, sampleRate)
        return true
    }

    fun process(x: Double, channel: Int): Double {
        val m = mix
        if (m == 0.0) return x
        val y = filter.process(x, channel)
        return if (m == 1.0) y else x + m * (y - x)
    }

    private fun isOff(hz: Float) = if (highPass) hz <= FiveWayPreamp.MIN_CORNER_HZ else hz >= FiveWayPreamp.MAX_CORNER_HZ

    private companion object {
        fun log2(v: Double) = kotlin.math.log2(v)
        fun exp2(v: Double) = Math.pow(2.0, v)
    }
}
