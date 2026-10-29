package io.sonora.core.playback.dub

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import io.sonora.core.audiodsp.dub.DubEngine
import io.sonora.core.audiodsp.dub.PresentationClock
import io.sonora.core.djloop.PcmFormat
import io.sonora.core.dubmodel.DubPadKind
import io.sonora.core.playback.equalizer.PeakLimiter
import io.sonora.core.playback.loop.LoopHosting
import io.sonora.core.playback.output.BitPerfectGate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10

/**
 * The DUB stage in the session player's audio path (design §3.1): a forwarding sink between the DJ loop sink and the
 * equalizer sink, so loops feed the echo and the equalizer, ReplayGain and the band meter hear the effects. It is a
 * sink, not an AudioProcessor, because with float output the default sink skips custom processors for
 * high-resolution PCM.
 *
 * - **Hosting** is decided when a stream is configured ([DubHosting]): DUB mode on, bit-perfect output not armed, the
 *   session not on a remote receiver, plain PCM of one or two channels. A hosted stream is handed on as float (the
 *   [DubEngine] works in float, and the limiter needs the headroom); a bypassed one is handed on untouched, its own
 *   buffers in its own encoding, with no work at all. DUB mode turned on mid-stream waits for the next configure (the
 *   controller re-prepares the player at once); turned off mid-stream, the stage lets go of every pad and goes idle,
 *   which is bit-transparent, until the next configure hands the stream on untouched again.
 * - **Per buffer**: the settings snapshot ([DubHub.params]), the filter, the kills, the stop gate and the pad edges are
 *   read once, then the buffer is decoded to float, run through the engine and the [PeakLimiter] (ceiling 0.966, 4 ms
 *   look-ahead), metered, and handed on. Pad edges map their touch time to the heard source frame through a
 *   [PresentationClock] fed with the position the sink below reports.
 * - **Pending-buffer rule**: once a buffer is processed, the same output goes back to the sink below until it has
 *   taken all of it, so the stage's state never advances twice for one buffer.
 * - **Flushes** (seeks, cues, gapless tracks in the same format) keep the echo's tails; a new rate or channel count
 *   starts the stage from silence.
 * - **DUB STOP** ([DubHub.dryMuted]): the dry signal fades out and the tail rings on while the player keeps running;
 *   once the output has stayed below -70 dBFS for 200 ms and one echo period (no throw still sending), or
 *   after 8 s of tail, the sink tells its owner once ([DubHubListener.onTailDone]), which pauses the player.
 * - **End of the queue**: [playToEndOfStream] first plays the tail out (silence through the stage, the limiter's
 *   look-ahead included) until it is quiet in the same sense, or for at most 10 s, and only then tells the sink below;
 *   [hasPendingData] and [isEnded] count it, as the DJ loop sink counts a held loop.
 *
 * The buffers are reused; one grows only when a longer buffer than any before arrives. Nothing in the per-buffer path
 * takes a lock or allocates (`a hosted buffer allocates nothing on the render path`).
 */
@UnstableApi
internal class DubAudioSink(
    sink: AudioSink,
    private val hub: DubHub,
    private val bitPerfect: BitPerfectGate? = null,
    private val nanoTime: NanoClock = NanoClock(System::nanoTime),
) : ForwardingAudioSink(sink) {

    private val engine = DubEngine()
    private val limiter = PeakLimiter()
    private var clock = PresentationClock(DEFAULT_RATE)

    /** The format of the stream being hosted, null while the stream is bypassed. */
    private var hosted: PcmFormat? = null

    private var samples = FloatArray(0)
    private var output: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var pendingOutput: ByteBuffer? = null

    /** Media time (µs) of the first frame handed on after configure or a flush, and that frame's index. */
    private var anchorUs = C.TIME_UNSET
    private var anchorFrame = 0L

    private var appliedParams: DubParams? = null
    private var appliedRelease = 0
    private var appliedKills = 0
    private var appliedFilterX = Float.NaN
    private var appliedFilterY = Float.NaN
    private var appliedMuted = false
    private var appliedEnabled = false

    /** DUB STOP's tail: frames since the dry signal was muted, the current run of quiet frames, and whether it was reported. */
    private var mutedFrames = 0L
    private var stopQuietFrames = 0L
    private var stopReported = false

    /** The end of the stream: the tail being played out, how long it has run and been quiet, and whether it is over. */
    private var ending = false
    private var tailOver = false
    private var tailFrames = 0L
    private var tailQuietFrames = 0L
    private var nextPresentationUs = 0L

    override fun configure(config: AudioSink.AudioSinkConfig) {
        pendingOutput = null
        endTail()
        val format = config.format
        val stream = LoopHosting.ofStream(format, hasChannelMapping = config.outputChannelMapping != null)
        val decision = DubHosting.decide(hub.enabled, bitPerfect?.armed == true, hub.remoteRouted, stream)
        hub.publishHosting(decision)
        if (decision !is DubHostingDecision.Hosted) {
            hosted = null
            super.configure(config)
            return
        }
        val pcm = decision.format
        hosted = pcm
        if (engine.prepare(pcm.sampleRate, pcm.channelCount)) {
            limiter.configure(pcm.sampleRate, pcm.channelCount)
            clock = PresentationClock(pcm.sampleRate)
            appliedParams = null
        }
        anchorUs = C.TIME_UNSET
        super.configure(
            AudioSink.AudioSinkConfig.Builder(format.buildUpon().setPcmEncoding(C.ENCODING_PCM_FLOAT).build())
                .setPreferredBufferSizeOverride(config.preferredBufferSizeOverride)
                .setTimeline(config.timeline)
                .setMediaPeriodId(config.mediaPeriodId)
                .build(),
        )
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val format = hosted ?: return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        pendingOutput?.let { waiting ->
            val done = super.handleBuffer(waiting, presentationTimeUs, encodedAccessUnitCount)
            if (done) pendingOutput = null
            return done
        }
        val frames = buffer.remaining() / format.frameBytes
        if (frames == 0) {
            buffer.position(buffer.limit())
            return true
        }
        if (anchorUs == C.TIME_UNSET) {
            anchorUs = presentationTimeUs
            anchorFrame = engine.framesHandled
        }
        recordClock()
        applyControls()
        val count = frames * format.channelCount
        if (samples.size < count) samples = FloatArray(count)
        PcmFloat.decode(buffer, format.encoding, samples, count)
        buffer.position(buffer.limit())
        val out = render(frames, format.channelCount)
        followStop(frames, hub.peak)
        nextPresentationUs = presentationTimeUs + frames * MICROS_PER_SECOND / format.sampleRate
        val done = super.handleBuffer(out, presentationTimeUs, encodedAccessUnitCount)
        if (!done) pendingOutput = out
        return done
    }

    override fun playToEndOfStream() {
        val format = hosted
        if (format == null || tailOver) {
            super.playToEndOfStream()
            return
        }
        ending = true
        while (true) {
            pendingOutput?.let { waiting ->
                if (!super.handleBuffer(waiting, nextPresentationUs, 1)) return
                pendingOutput = null
                nextPresentationUs += TAIL_CHUNK_FRAMES * MICROS_PER_SECOND / format.sampleRate
            }
            if (tailFinished(format.sampleRate)) break
            applyControls()
            val count = TAIL_CHUNK_FRAMES * format.channelCount
            if (samples.size < count) samples = FloatArray(count)
            samples.fill(0f, 0, count)
            pendingOutput = render(TAIL_CHUNK_FRAMES, format.channelCount)
            tailFrames += TAIL_CHUNK_FRAMES
            tailQuietFrames = if (hub.peak < QUIET_PEAK && !engine.sendInFlight) tailQuietFrames + TAIL_CHUNK_FRAMES else 0
        }
        tailOver = true
        super.playToEndOfStream()
    }

    override fun hasPendingData(): Boolean = (hosted != null && ending && !tailOver) || super.hasPendingData()

    override fun isEnded(): Boolean = (hosted == null || !ending || tailOver) && super.isEnded()

    override fun flush() {
        pendingOutput = null
        endTail()
        anchorUs = C.TIME_UNSET
        // The limiter's 4 ms belong to the old position; the echo's tails are kept.
        if (hosted != null) limiter.reset()
        super.flush()
    }

    override fun reset() {
        pendingOutput = null
        endTail()
        anchorUs = C.TIME_UNSET
        hosted = null
        super.reset()
    }

    // --- Per buffer -------------------------------------------------------------------------------------------------

    /** Runs [frames] frames of [samples] through the stage and the limiter, meters them, and encodes them for the sink below. */
    private fun render(frames: Int, channels: Int): ByteBuffer {
        engine.process(samples, 0, frames)
        limitAndMeter(frames, channels)
        val count = frames * channels
        val out = outputFor(count * FLOAT_BYTES)
        PcmFloat.encodeFloat(samples, count, out)
        out.flip()
        return out
    }

    /** Follows DUB STOP's tail and reports its end once. */
    private fun followStop(frames: Int, peak: Float) {
        if (!appliedMuted) {
            mutedFrames = 0
            stopQuietFrames = 0
            stopReported = false
            return
        }
        mutedFrames += frames
        stopQuietFrames = if (peak < QUIET_PEAK && !engine.sendInFlight) stopQuietFrames + frames else 0
        val rate = engine.sampleRate.toLong()
        if (!stopReported && (stopQuietFrames >= quietFrames() || mutedFrames * MS_PER_SECOND >= STOP_CAP_MS * rate)) {
            stopReported = true
            hub.listener?.onTailDone()
        }
    }

    private fun tailFinished(rate: Int): Boolean {
        if (tailFrames < limiter.latencyFrames) return false
        if (engine.isIdle || tailFrames * MS_PER_SECOND >= END_CAP_MS * rate) return true
        return tailQuietFrames >= quietFrames()
    }

    /**
     * How long the output must stay below -70 dBFS for the tail to be over: 200 ms, and, while the echo runs, at least
     * one echo period, since every sample in its loop comes back once per period (a gap between repeats is not the end).
     */
    private fun quietFrames(): Long {
        val rate = engine.sampleRate.toLong()
        if (!engine.echoActive) return QUIET_MS * rate / MS_PER_SECOND
        return maxOf(QUIET_MS * rate / MS_PER_SECOND, engine.echoDelayFrames.toLong() + QUIET_MARGIN_MS * rate / MS_PER_SECOND)
    }

    private fun endTail() {
        ending = false
        tailOver = false
        tailFrames = 0
        tailQuietFrames = 0
    }

    private fun recordClock() {
        val positionUs = super.getCurrentPositionUs(false)
        val presented = if (positionUs == AudioSink.CURRENT_POSITION_NOT_SET || anchorUs == C.TIME_UNSET) {
            Double.NaN
        } else {
            PresentationClock.presentedFrame(positionUs, anchorUs, anchorFrame, clock.sampleRate)
        }
        clock.record(nanoTime.now(), engine.framesHandled, presented, super.getPlaybackParameters().speed.toDouble())
    }

    /** The engine frame heard at [uptimeNanos]: the output frame then playing, minus the limiter's look-ahead. */
    private fun heardFrame(uptimeNanos: Long): Double = clock.heardFrameAt(uptimeNanos) - limiter.latencyFrames

    private fun applyControls() {
        val params = hub.params
        if (params !== appliedParams) {
            appliedParams = params
            engine.setTempo(params.bpm)
            engine.setDivision(params.division)
            engine.setFeedback(params.feedback)
            engine.setReturnLevel(params.returnLevel)
            for (w in 0 until WAYS) engine.setWay(w, params.highPassHz(w), params.lowPassHz(w), params.gain(w))
            engine.setDropWays(params.dropWays)
        }
        val enabled = hub.enabled
        val release = hub.releaseGeneration
        if (release != appliedRelease || (appliedEnabled && !enabled)) {
            appliedRelease = release
            engine.releaseAll()
            appliedKills = 0
            appliedFilterX = 0f
        }
        appliedEnabled = enabled
        // DUB mode turned off mid-stream: everything let go, the stage idles (bit-transparent) until the next configure.
        val kills = if (enabled) hub.kills else 0
        if (kills != appliedKills) {
            for (w in 0 until WAYS) {
                val bit = 1 shl w
                if ((kills xor appliedKills) and bit != 0) engine.setKilled(w, kills and bit != 0)
            }
            appliedKills = kills
        }
        val x = if (enabled) hub.filterX else 0f
        val y = if (enabled) hub.filterY else appliedFilterY.let { if (it.isNaN()) 0f else it }
        if (x != appliedFilterX || y != appliedFilterY) {
            engine.setFilter(x, y)
            appliedFilterX = x
            appliedFilterY = y
        }
        val muted = enabled && hub.dryMuted
        if (muted != appliedMuted) {
            engine.setDryMuted(muted)
            appliedMuted = muted
        }
        while (true) {
            val event = hub.poll() ?: break
            if (enabled) apply(event)
        }
    }

    private fun apply(event: DubEvent) {
        when (event.pad) {
            DubPadKind.THROW ->
                if (event.engaged) engine.throwDown(heardFrame(event.uptimeNanos)) else engine.throwUp(heardFrame(event.uptimeNanos))
            DubPadKind.CUT -> engine.setCut(event.engaged)
            DubPadKind.DROP -> engine.setDrop(event.engaged)
            DubPadKind.FREEZE -> engine.setFreeze(event.engaged)
            DubPadKind.RUNAWAY -> engine.setRunaway(event.engaged)
        }
    }

    private fun limitAndMeter(frames: Int, channels: Int) {
        var peak = 0f
        var minGain = 1.0
        var i = 0
        for (f in 0 until frames) {
            limiter.process(samples, i, samples, i)
            if (limiter.gain < minGain) minGain = limiter.gain
            for (c in 0 until channels) {
                val a = samples[i + c]
                peak = maxOf(peak, if (a < 0f) -a else a)
            }
            i += channels
        }
        hub.peak = peak
        hub.gainReductionDb = if (minGain < 1.0) (-20.0 * log10(minGain)).toFloat() else 0f
    }

    private fun outputFor(bytes: Int): ByteBuffer {
        if (output.capacity() < bytes) output = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        output.clear()
        return output
    }

    private companion object {
        const val DEFAULT_RATE = 48_000
        const val FLOAT_BYTES = 4
        const val WAYS = 5
        const val MICROS_PER_SECOND = 1_000_000L
        const val MS_PER_SECOND = 1_000L

        /** -70 dBFS: a tail below it is over (once it stays there for [QUIET_MS]). */
        const val QUIET_PEAK = 3.1622776e-4f
        const val QUIET_MS = 200L
        const val QUIET_MARGIN_MS = 10L

        /** DUB STOP gives the tail at most this long before the player pauses. */
        const val STOP_CAP_MS = 8_000L

        /** The end of the queue holds the stream open for the tail at most this long. */
        const val END_CAP_MS = 10_000L

        /** The tail at the end of the stream is played out in buffers of this many frames. */
        const val TAIL_CHUNK_FRAMES = 1_024
    }
}

/**
 * The monotonic clock the sink stamps each buffer with, in nanoseconds (the base of `System.nanoTime`). A primitive
 * `fun interface` rather than `() -> Long`, whose generic `invoke` would box a `Long` on every buffer.
 */
internal fun interface NanoClock {
    fun now(): Long
}
