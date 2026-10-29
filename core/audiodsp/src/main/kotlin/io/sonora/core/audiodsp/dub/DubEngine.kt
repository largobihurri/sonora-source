package io.sonora.core.audiodsp.dub

import io.sonora.core.audiodsp.filter.MorphSvf
import io.sonora.core.audiodsp.preamp.FiveWayPreamp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The DUB stage's signal flow (design §3.3 with §10.1), on interleaved float audio of one or two channels, in place:
 *
 * 1. Every input frame goes into the [HistoryRing] (400 ms), always, so a THROW can reach back to what was heard.
 * 2. THROW is a pre-fader send of the input into the [TapeEcho], made only through the [PendingSendRing] (the
 *    latency-compensated send of §3.6): [throwDown] and [throwUp] take the heard source frame (NaN: the frontier).
 *    FREEZE closes it ([setFreeze] releases a held throw, and a throw while frozen is ignored); RUNAWAY pushes the
 *    echo into self-oscillation ([setRunaway]).
 * 3. Dry path: the input times the CUT gain (10 ms ramp, [setCut]) times the DUB STOP gain (20 ms ramp,
 *    [setDryMuted]). The echo keeps ringing under both.
 * 4. Sum = dry + echo return, through the XY [MorphSvf] ([setFilter]; a true bypass at the centre detent).
 * 5. The [FiveWayPreamp] last ([setWay], [setKilled]; DROP, [setDrop], kills the ways of [setDropWays]), so a kill also
 *    takes that band out of the echo tail. While the preamp is neutral (unity faders, default corners, nothing killed,
 *    no DROP) it is left out; it crossfades in and out over 10 ms, from a clean state, when that changes.
 * 6. A final guard: non-finite samples are silence and every sample is kept within [OUTPUT_BOUND]. The look-ahead
 *    limiter and the meters come after this, in the audio sink.
 *
 * Idle: with no throw in flight, the echo silent (below -90 dBFS for as long as its ring holds, so a repeat still on
 * its way after a silent gap is never lost), the filter centred, CUT and DUB STOP open and the preamp neutral, a block is copied through untouched (only
 * the history is written, and non-finite samples become silence), so DUB mode with nothing engaged is bit-transparent
 * and costs almost nothing ([isIdle]).
 *
 * Format: [prepare] keeps every state when the format is the same (a flush, a seek, a gapless track in the same
 * format keep the tails); a new rate or channel count allocates again and starts from silence (the tails of the
 * previous stream end with it).
 *
 * Real-time safe: [prepare] allocates when the format changes (call it from configure); every other call is
 * allocation-free and lock-free. Not thread-safe: the audio thread owns it, controls arrive between blocks.
 */
class DubEngine {

    private var history = HistoryRing(2, HistoryRing.capacityFor(TapeEcho.DEFAULT_SAMPLE_RATE))
    private var pending = PendingSendRing(history, TapeEcho.DEFAULT_SAMPLE_RATE)
    private val echo = TapeEcho()
    private val filter = MorphSvf()
    private val preamp = FiveWayPreamp()
    private var identity = FloatArray(BLOCK_FRAMES * 2)

    var sampleRate: Int = 0
        private set

    var channelCount: Int = 0
        private set

    private val cut = LinearRamp(1.0)
    private val dryGate = LinearRamp(1.0)
    private val preampMix = LinearRamp(0.0)
    private var cutFrames = 1
    private var stopFrames = 1
    private var preampFadeFrames = 1

    private var cutOn = false
    private var dropOn = false
    private var dryMuted = false
    private var frozen = false
    private var filterX = 0f
    private var filterY = 0f

    private val wayHighPass = FloatArray(FiveWayPreamp.WAY_COUNT) { FiveWayPreamp.defaultHighPassHz(it) }
    private val wayLowPass = FloatArray(FiveWayPreamp.WAY_COUNT) { FiveWayPreamp.defaultLowPassHz(it) }
    private val wayGain = FloatArray(FiveWayPreamp.WAY_COUNT) { 1f }
    private var killMask = 0

    /**
     * Frames the echo keeps running after the last send or the last audible repeat: its ring can hold audio that comes
     * back this much later, with silence in between (a short throw on a long delay).
     */
    private var echoHoldFrames = 0
    private var echoHold = 0
    private var echoRunning = false

    /** True when the last block was copied through untouched (see the class comment). */
    var isIdle: Boolean = true
        private set

    /** The loudest sample of the last [process] call, after the stage. */
    var blockPeak: Float = 0f
        private set

    /** Frames received since the last [prepare] that changed the format: the frontier, in source frames. */
    val framesHandled: Long get() = history.newestFrame + 1

    /** Whether the echo is running: it was sent something, or gave back something audible, within its ring's length. */
    val echoActive: Boolean get() = echoRunning || pending.isActive

    /** Whether a throw is still sending (held, or fading out): its audio has not all entered the echo yet. */
    val sendInFlight: Boolean get() = pending.isActive

    /** Whether a throw is held (pressed and not released). */
    val throwHeld: Boolean get() = pending.isActive && !pending.isReleased

    /** The echo's current time in frames (gliding after a tempo or division change). */
    val echoDelayFrames: Double get() = echo.currentDelayFrames

    /**
     * Sets the format. The same rate and channel count keep every state and return false; a new one allocates, clears
     * every tail and returns true. Not real-time safe when it returns true.
     */
    fun prepare(sampleRate: Int, channels: Int): Boolean {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(channels in 1..TapeEcho.MAX_CHANNELS) { "channels must be 1 or 2: $channels" }
        if (sampleRate == this.sampleRate && channels == channelCount) return false
        this.sampleRate = sampleRate
        channelCount = channels
        history = HistoryRing(channels, HistoryRing.capacityFor(sampleRate))
        pending = PendingSendRing(history, sampleRate)
        echo.prepare(sampleRate, channels)
        filter.prepare(sampleRate, channels)
        filter.setPosition(filterX, filterY)
        preamp.prepare(sampleRate, channels)
        if (identity.size < BLOCK_FRAMES * channels) identity = FloatArray(BLOCK_FRAMES * channels)
        cutFrames = frames(CUT_SECONDS)
        stopFrames = frames(STOP_SECONDS)
        preampFadeFrames = frames(PREAMP_FADE_SECONDS)
        echoHoldFrames = frames(TapeEcho.RING_SECONDS + HistoryRing.HISTORY_SECONDS)
        cut.setTarget(if (cutOn) 0.0 else 1.0, 0)
        dryGate.setTarget(if (dryMuted) 0.0 else 1.0, 0)
        preampMix.setTarget(0.0, 0)
        echoHold = 0
        echoRunning = false
        isIdle = true
        return true
    }

    // --- The echo ---------------------------------------------------------------------------------------------------

    fun setTempo(bpm: Double) = echo.setTempo(bpm)

    fun setDivision(division: EchoDivision) = echo.setDivision(division)

    fun setFeedback(feedback: Float) = echo.setFeedback(feedback)

    fun setReturnLevel(level: Float) = echo.setReturnLevel(level)

    // --- The XY filter and the preamp -------------------------------------------------------------------------------

    /** X in -1..1 (low-pass, centre detent, high-pass), Y in 0..1 (resonance). */
    fun setFilter(x: Float, y: Float) {
        filterX = if (x.isNaN()) 0f else x.coerceIn(-1f, 1f)
        filterY = if (y.isNaN()) 0f else y.coerceIn(0f, 1f)
        filter.setPosition(filterX, filterY)
    }

    /** Way [way] (0..4): its corners in Hz (20 Hz high-pass and 20 kHz low-pass are off) and its linear fader gain. */
    fun setWay(way: Int, highPassHz: Float, lowPassHz: Float, gain: Float) {
        if (way !in 0 until FiveWayPreamp.WAY_COUNT) return
        preamp.setHighPassHz(way, highPassHz)
        preamp.setLowPassHz(way, lowPassHz)
        preamp.setFaderGain(way, gain)
        wayHighPass[way] = preamp.highPassHz(way)
        wayLowPass[way] = preamp.lowPassHz(way)
        wayGain[way] = if (gain.isFinite()) gain else 0f
    }

    fun setKilled(way: Int, killed: Boolean) {
        if (way !in 0 until FiveWayPreamp.WAY_COUNT) return
        preamp.setKilled(way, killed)
        killMask = if (killed) killMask or (1 shl way) else killMask and (1 shl way).inv()
    }

    fun isKilled(way: Int): Boolean = way in 0 until FiveWayPreamp.WAY_COUNT && (killMask shr way) and 1 == 1

    /** The ways DROP kills, as a bit mask (bit 0 = way 1). */
    fun setDropWays(mask: Int) = preamp.setDropWays(mask)

    // --- The pads ---------------------------------------------------------------------------------------------------

    /**
     * Opens the throw from [heardFrame] (the source frame heard when the finger landed; NaN is the frontier). Returns
     * how it sends, or null when it is ignored (FREEZE is engaged).
     */
    fun throwDown(heardFrame: Double): ThrowMode? {
        if (frozen) return null
        return pending.press(heardFrame, echo.currentDelayFrames)
    }

    /** Closes the throw at [heardFrame] (NaN is the frontier); the tail rings on. */
    fun throwUp(heardFrame: Double) = pending.release(heardFrame)

    fun setCut(on: Boolean) {
        cutOn = on
        cut.setTarget(if (on) 0.0 else 1.0, cutFrames)
    }

    fun setDrop(on: Boolean) {
        dropOn = on
        preamp.setDropActive(on)
    }

    fun setFreeze(on: Boolean) {
        frozen = on
        if (on) pending.releaseAtFrontier()
        echo.setFreeze(on)
    }

    fun setRunaway(on: Boolean) = echo.setRunaway(on)

    /**
     * DUB STOP: [muted] fades the dry signal out over 20 ms and closes a held throw, so only the tail is left and it
     * can end; false brings the dry signal back over the same 20 ms.
     */
    fun setDryMuted(muted: Boolean) {
        dryMuted = muted
        if (muted) pending.releaseAtFrontier()
        dryGate.setTarget(if (muted) 0.0 else 1.0, stopFrames)
    }

    /** Lets go of every pad, kill and the filter (backgrounded, screen off, focus lost); the tails ring on. */
    fun releaseAll() {
        pending.releaseAtFrontier()
        setCut(false)
        setDrop(false)
        setFreeze(false)
        setRunaway(false)
        for (w in 0 until FiveWayPreamp.WAY_COUNT) setKilled(w, false)
        setFilter(0f, filterY)
    }

    // --- The audio --------------------------------------------------------------------------------------------------

    /** Runs [frames] interleaved frames of [buffer] from [offset] through the stage, in place. */
    fun process(buffer: FloatArray, offset: Int, frames: Int) {
        check(channelCount > 0) { "prepare first" }
        var peak = 0f
        var done = 0
        while (done < frames) {
            val n = minOf(BLOCK_FRAMES, frames - done)
            val start = offset + done * channelCount
            peak = maxOf(peak, if (needsProcessing()) processBlock(buffer, start, n) else copyBlock(buffer, start, n))
            done += n
        }
        blockPeak = peak
    }

    private fun needsProcessing(): Boolean {
        val preampWanted = !preampNeutral()
        if (preampWanted && preampMix.target == 0.0) {
            if (preampMix.value == 0.0) preamp.reset()
            preampMix.setTarget(1.0, preampFadeFrames)
        } else if (!preampWanted && preampMix.target == 1.0) {
            preampMix.setTarget(0.0, preampFadeFrames)
        }
        if (pending.isActive) echoHold = echoHoldFrames
        echoRunning = echoHold > 0
        return echoRunning ||
            abs(filterX) > MorphSvf.DETENT_HALF_WIDTH || !filter.isBypassed ||
            cutOn || !cut.isSettled || dryMuted || !dryGate.isSettled ||
            preampWanted || preampMix.value != 0.0
    }

    private fun preampNeutral(): Boolean {
        if (killMask != 0 || (dropOn && preamp.dropWays != 0)) return false
        for (w in 0 until FiveWayPreamp.WAY_COUNT) {
            if (wayGain[w] != 1f) return false
            if (wayHighPass[w] != FiveWayPreamp.defaultHighPassHz(w)) return false
            if (wayLowPass[w] != FiveWayPreamp.defaultLowPassHz(w)) return false
        }
        return true
    }

    private fun copyBlock(buffer: FloatArray, start: Int, frames: Int): Float {
        if (!isIdle) {
            // Leaving the work: the echo's ring holds nothing audible any more, start it clean next time.
            echo.reset()
            isIdle = true
        }
        val stereo = channelCount == 2
        var peak = 0f
        var i = start
        for (f in 0 until frames) {
            val l = buffer[i]
            val left = if (l.isFinite()) l else 0f.also { buffer[i] = 0f }
            var right = 0f
            if (stereo) {
                val r = buffer[i + 1]
                right = if (r.isFinite()) r else 0f.also { buffer[i + 1] = 0f }
                peak = maxOf(peak, abs(right))
            }
            peak = maxOf(peak, abs(left))
            history.write(left, right)
            pending.advance()
            i += channelCount
        }
        return peak
    }

    private fun processBlock(buffer: FloatArray, start: Int, frames: Int): Float {
        isIdle = false
        val ch = channelCount
        val stereo = ch == 2
        var echoMax = 0f
        var i = start
        for (f in 0 until frames) {
            val l = buffer[i]
            val left = if (l.isFinite()) l else 0f
            val right = if (stereo) buffer[i + 1].let { if (it.isFinite()) it else 0f } else 0f
            history.write(left, right)
            pending.advance()
            val dry = (cut.next() * dryGate.next()).toFloat()
            if (echoRunning) {
                echo.processFrame(0f, 0f, pending)
                val el = echo.outLeft
                buffer[i] = left * dry + el
                echoMax = maxOf(echoMax, abs(el))
                if (stereo) {
                    val er = echo.outRight
                    buffer[i + 1] = right * dry + er
                    echoMax = maxOf(echoMax, abs(er))
                }
            } else {
                buffer[i] = left * dry
                if (stereo) buffer[i + 1] = right * dry
            }
            i += ch
        }
        if (echoRunning) echoHold = if (echoMax > TAIL_FLOOR) echoHoldFrames else maxOf(0, echoHold - frames)
        filter.process(buffer, start, frames)
        mixPreamp(buffer, start, frames)
        return guard(buffer, start, frames * ch)
    }

    /** The preamp, crossfaded with its own input while [preampMix] moves; left out while it is 0 and staying there. */
    private fun mixPreamp(buffer: FloatArray, start: Int, frames: Int) {
        if (preampMix.value == 0.0 && preampMix.isSettled) return
        val ch = channelCount
        if (preampMix.value == 1.0 && preampMix.isSettled) {
            preamp.process(buffer, start, frames)
            return
        }
        val n = frames * ch
        System.arraycopy(buffer, start, identity, 0, n)
        preamp.process(buffer, start, frames)
        var i = 0
        for (f in 0 until frames) {
            val m = preampMix.next().toFloat()
            for (c in 0 until ch) {
                val k = start + i
                buffer[k] = identity[i] + m * (buffer[k] - identity[i])
                i++
            }
        }
    }

    private fun guard(buffer: FloatArray, start: Int, samples: Int): Float {
        var peak = 0f
        for (k in start until start + samples) {
            val x = buffer[k]
            val y = if (!x.isFinite()) 0f else if (x > OUTPUT_BOUND) OUTPUT_BOUND else if (x < -OUTPUT_BOUND) -OUTPUT_BOUND else x
            buffer[k] = y
            peak = maxOf(peak, abs(y))
        }
        return peak
    }

    private fun frames(seconds: Double): Int = maxOf(1, (seconds * sampleRate).roundToInt())

    companion object {
        const val CUT_SECONDS = 0.010
        const val STOP_SECONDS = 0.020
        const val PREAMP_FADE_SECONDS = 0.010

        /** -90 dBFS: an echo tail below it is silence. */
        const val TAIL_FLOOR = 3.1622776e-5f

        /** The stage never hands on a sample louder than this (+18 dBFS); the limiter after it brings it down. */
        const val OUTPUT_BOUND = 8f

        /** The stage works in blocks of at most this many frames (the preamp crossfade's scratch size). */
        const val BLOCK_FRAMES = 512
    }
}
