package io.sonora.core.playback.loop

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import io.sonora.core.djloop.PcmFormat
import io.sonora.core.playback.output.BitPerfectGate
import java.nio.ByteBuffer

/**
 * Sample-accurate DJ loops in front of [sink] (the equalizer sink over the default one): the audio the renderer hands
 * over goes through a [LoopSession] while DJ mode is on, and passes untouched when it is off.
 *
 * While DJ mode is on ([LoopHub.enabled]) and the stream is plain 16/24/32-bit or float PCM, the loop engine (see
 * [io.sonora.core.djloop.LoopEngine]) sits between the renderer and [sink]. It copies the audio through, byte for byte,
 * until the user sets a loop, then repeats it from memory and stops taking input, so the decoder stalls where it is (no
 * seek, no gap). Everything else is the sink below's: speed, pitch and volume are untouched because the loop is in
 * source frames, ahead of them. Not hosted (the audio goes straight through, and requests are refused): DJ mode off,
 * bit-perfect output armed (like the tempo, loops are held off there), compressed streams, 8-bit or big-endian PCM, a
 * channel mapping. DJ mode turned on or off in mid-stream takes effect at the next buffer, and a host that holds a loop
 * or a mark is kept until it is free.
 *
 * Position. [getCurrentPositionUs] is the source's: inside a loop it wraps from Out to In, and the renderer is told
 * through the listener before each wrap so that it accepts a position that goes back (otherwise it would keep the
 * highest one). The player's clock therefore reads the loop's own position. [hasPendingData] and [isEnded] count the
 * loop, so a loop held at the end of the track keeps the stream alive and [playToEndOfStream] does not reach [sink]
 * until it is left.
 *
 * Tempo: the player's speed is applied below, to the AudioTrack, so a loop of N source frames takes N / speed of time.
 * The AudioTrack's depth (kept small in DJ mode, see [DjAudioTrackBufferSizeProvider]) is what delays a loop's engagement
 * after the press.
 */
@UnstableApi
internal class LoopAudioSink(
    sink: AudioSink,
    private val hub: LoopHub,
    private val bitPerfect: BitPerfectGate? = null,
) : ForwardingAudioSink(sink) {

    private var listener: AudioSink.Listener? = null
    private var session: LoopSession? = null
    private val trim = TrimModel()

    /** The configured stream as the engine would host it, or why it cannot. */
    private var stream: StreamHosting = StreamHosting.NONE

    /** What was decided last, to log only changes. */
    private var decided: LoopHostingDecision? = null
    private var streamOffsetUs = 0L

    /** A buffer has been handed over and not taken entirely: the same one comes back, so nothing changes until it does. */
    private var inFlight = false

    init {
        DjLoopLog.v { "loop sink in the chain; DJ mode ${if (hub.enabled) "on" else "off"}, bit-perfect armed=${bitPerfect?.armed}" }
    }

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        super.setListener(listener)
    }

    override fun configure(config: AudioSink.AudioSinkConfig) {
        inFlight = false
        stream = LoopHosting.ofStream(config.format, hasChannelMapping = config.outputChannelMapping != null)
        DjLoopLog.v { "stream configured: $stream" }
        trim.configured(if (stream is StreamHosting.Hostable) config.format.encoderDelay.toLong() else 0L)
        syncSession(newStream = true)
        super.configure(config)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        streamOffsetUs = outputStreamOffsetUs
        session?.setStreamOffsetUs(outputStreamOffsetUs)
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!inFlight) syncSession(newStream = false)
        if (buffer.hasRemaining()) trim.audioHandled()
        val host = session
        if (host == null) {
            refuseRequests()
            val done = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            inFlight = !done
            return done
        }
        if (hub.hasRequests()) drain(host, super.getCurrentPositionUs(false))
        val done = host.write(buffer, presentationTimeUs)
        inFlight = !done
        return done
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        // The renderer asks for the position on every pass, also while it hands no audio over (paused, waiting): a change of
        // DJ mode is taken here too, not only with the next buffer.
        if (!inFlight) syncSession(newStream = false)
        val position = super.getCurrentPositionUs(sourceEnded)
        val host = session ?: return position
        if (position == AudioSink.CURRENT_POSITION_NOT_SET) return position
        if (hub.hasRequests()) drain(host, position)
        return host.mapPosition(position)
    }

    override fun playToEndOfStream() {
        val host = session
        if (host == null || host.finish()) super.playToEndOfStream()
    }

    override fun isEnded(): Boolean = session?.let { it.isEnded() && super.isEnded() } ?: super.isEnded()

    override fun hasPendingData(): Boolean = session?.hasPendingData() == true || super.hasPendingData()

    override fun flush() {
        inFlight = false
        trim.flushed()
        session?.apply {
            flush()
            timeChanged()
        }
        super.flush()
    }

    override fun reset() {
        inFlight = false
        trim.reset()
        session?.apply {
            flush()
            timeChanged()
        }
        super.reset()
    }

    override fun release() {
        dropSession()
        super.release()
    }

    private fun drain(host: LoopSession, position: Long) {
        if (position == AudioSink.CURRENT_POSITION_NOT_SET) refuseRequests() else host.drain(position)
    }

    private fun refuseRequests() {
        while (hub.poll() != null) hub.listener?.onRefused(LoopRefusal.NOT_AVAILABLE)
    }

    /**
     * Hosts the stream when it can be and DJ mode wants it, drops the host when it cannot or should not be. A new
     * stream ([newStream]) always takes the decision; in mid-stream a host is only dropped once it holds nothing.
     */
    private fun syncSession(newStream: Boolean) {
        val decision = LoopHosting.decide(stream, djMode = hub.enabled, bitPerfectArmed = bitPerfect?.armed == true)
        val wanted = (decision as? LoopHostingDecision.Host)?.format
        val current = session
        if (decision != decided) {
            decided = decision
            DjLoopLog.v {
                when (decision) {
                    is LoopHostingDecision.Host -> "hosting loops on ${decision.format}"
                    is LoopHostingDecision.PassThrough -> "loops not hosted: ${decision.reason} (${decision.detail})"
                }
            }
        }
        when {
            wanted == null -> {
                if (current != null && (newStream || current.isIdle())) dropSession()
                if (session == null) hub.publish(LoopSnapshot.UNAVAILABLE)
            }
            current == null -> startSession(wanted)
            current.format.sameAs(wanted) -> if (newStream) current.apply { restart() }
            newStream -> {
                dropSession()
                startSession(wanted)
            }
        }
    }

    private fun startSession(format: PcmFormat) {
        DjLoopLog.v { "session started on $format" }
        session = LoopSession(
            format,
            hub,
            trim,
            streamOffsetUs,
            output = { buffer, presentationTimeUs -> super.handleBuffer(buffer, presentationTimeUs, 1) },
            onDiscontinuity = { listener?.onPositionDiscontinuity() },
        )
    }

    private fun dropSession() {
        if (session != null) DjLoopLog.v { "session dropped" }
        session?.release()
        session = null
    }

    private fun PcmFormat.sameAs(other: PcmFormat) =
        sampleRate == other.sampleRate && channelCount == other.channelCount && encoding == other.encoding
}
