package io.sonora.core.playback.loop

import io.sonora.core.djloop.LoopCommand
import io.sonora.core.djloop.LoopEngine
import io.sonora.core.djloop.LoopEvent
import io.sonora.core.djloop.LoopListener
import io.sonora.core.djloop.LoopOutput
import io.sonora.core.djloop.LoopRejection
import io.sonora.core.djloop.LoopState
import io.sonora.core.djloop.LoopStatus
import io.sonora.core.djloop.PcmFormat
import io.sonora.core.djmodel.loop.LoopFrames
import java.nio.ByteBuffer

/**
 * One stream format's loop machinery on the playback thread, behind [LoopAudioSink]: the [LoopEngine], the requests the
 * user made resolved at the frame being heard, and the mapping of the inner sink's position back to the source.
 *
 * Time. The sink hands the inner sink the engine's output with a linear presentation time `base + output frame / rate`,
 * where `base` is the first buffer's time after a flush: without a loop that is exactly the time the renderer gave, and
 * a loop only adds the time spent in it, so the inner sink never sees a jump and its position runs on in output time.
 * [mapPosition] turns that position back into the source's: it finds the output frame being heard (the position minus
 * `base`, plus the frames the sink has trimmed, see [TrimModel]), asks the engine's timeline which source frame that is,
 * and shifts the position by the difference. Each wrap of the source is announced through [onDiscontinuity] first, which
 * is what lets the audio renderer take a position that goes back.
 *
 * Playback thread only, except through [hub].
 */
internal class LoopSession(
    val format: PcmFormat,
    private val hub: LoopHub,
    private val trim: TrimModel,
    streamOffsetUs: Long,
    /** The inner sink: takes [ByteBuffer] at presentation time (us) like `AudioSink.handleBuffer`. */
    private val output: (ByteBuffer, Long) -> Boolean,
    /** `AudioSink.Listener.onPositionDiscontinuity`: the position about to be reported goes back. */
    private val onDiscontinuity: () -> Unit,
) {
    private val rate = format.sampleRate

    private val engine = LoopEngine(
        format,
        LoopOutput { buffer, firstOutputFrame -> output(buffer, baseUs + firstOutputFrame * MICROS_PER_SECOND / rate) },
    )

    /** The presentation time of the first buffer after a flush, and its frame (the engine's own rounding). */
    private var baseUs = 0L
    private var anchorFrame = 0L
    private var baseKnown = false

    private var streamOffsetFrames = framesOf(streamOffsetUs)
    private var time = timeBase()
    private var timeVersion = 0

    private var markedIn: Long? = null
    private var lastRequested: LoopFrames? = null
    private var lastEngaged: LoopFrames? = null
    private var lastWraps = 0L

    // What was last published, to publish only changes.
    private var publishedStatus: LoopStatus? = null
    private var publishedMark: Long? = null
    private var publishedVersion = -1
    private var audibleLooping = false
    private var audibleIn = -1L
    private var audibleOut = -1L
    private var audibleVersion = -1

    init {
        engine.listener = LoopListener(::onEvent)
        publishSnapshot()
    }

    /** `AudioSink.handleBuffer`: true when [input] is fully consumed; offer the same buffer again otherwise. */
    fun write(input: ByteBuffer, presentationTimeUs: Long): Boolean {
        if (!baseKnown && input.hasRemaining()) {
            baseKnown = true
            baseUs = presentationTimeUs
            anchorFrame = Math.round(presentationTimeUs * rate / 1_000_000.0)
        }
        val done = engine.write(input, presentationTimeUs)
        publishSnapshot()
        return done
    }

    /** Lets the engine run without input (a loop playing, or the captured tail after Exit). */
    fun pump() {
        engine.pump()
        publishSnapshot()
    }

    /** `AudioSink.playToEndOfStream`: true once everything has been played out and the inner sink may be told. */
    fun finish(): Boolean {
        engine.endOfInput()
        engine.pump()
        publishSnapshot()
        return engine.isEnded()
    }

    fun isEnded(): Boolean = engine.isEnded()

    fun hasPendingData(): Boolean = engine.hasPendingData()

    /** Nothing is held, armed or marked: the session can be dropped without losing anything. */
    fun isIdle(): Boolean = !engine.hasPendingData() && engine.status.state != LoopState.ARMED && markedIn == null

    /** Resolves the user's pending requests at the frame heard when the inner sink's position was [innerPositionUs]. */
    fun drain(innerPositionUs: Long) {
        var request = hub.poll() ?: return
        if (!baseKnown) {
            do {
                hub.listener?.onRefused(LoopRefusal.NOT_AVAILABLE)
                request = hub.poll() ?: break
            } while (true)
            return
        }
        val now = engine.timeline.sourceFrameAt(heardFrame(innerPositionUs))
        var pending: LoopRequest? = request
        while (pending != null) {
            val result = LoopRequestResolver.resolve(pending, now, time, hub.context, markedIn, engine.status)
            if (result.refusal != null) {
                hub.listener?.onRefused(result.refusal)
            } else {
                for (command in result.commands) {
                    if (command is LoopCommand.Set) lastRequested = command.loop
                    engine.submit(command)
                }
            }
            markedIn = result.markedIn
            result.jumpToFrame?.let { hub.listener?.onNeedsSeek(time.frameToClockMs(it)) }
            pending = hub.poll()
        }
        publishSnapshot()
    }

    /**
     * The inner sink's position [innerPositionUs] as the source's: the same while no loop has played, the source frame
     * being heard inside and after a loop. Announces a wrap before returning the position that follows it.
     */
    fun mapPosition(innerPositionUs: Long): Long {
        if (!baseKnown) return innerPositionUs
        val heard = heardFrame(innerPositionUs)
        val timeline = engine.timeline
        publishAudible(timeline.isLoopingAt(heard))
        val wraps = timeline.wrapsAt(heard)
        if (wraps > lastWraps) {
            lastWraps = wraps
            onDiscontinuity()
        }
        val shift = timeline.sourceFrameAt(heard) - (anchorFrame + heard)
        return if (shift == 0L) innerPositionUs else innerPositionUs + Math.round(shift * MICROS_PER_SECOND.toDouble() / rate)
    }

    /** A stream is configured (the same format, no new session): the stream starts again, the armed loop stays. */
    fun restart() {
        flush()
        timeChanged()
    }

    /** A seek or a discontinuity: the audio and the output timeline go, the armed loop stays. */
    fun flush() {
        engine.flush()
        baseKnown = false
        lastWraps = 0L
        publishAudible(false)
        publishSnapshot()
    }

    /** The renderer's offset for the stream now playing changed. */
    fun setStreamOffsetUs(us: Long) {
        streamOffsetFrames = framesOf(us)
        timeChanged()
    }

    /** The trimming model or the offset changed: loop times are recomputed. */
    fun timeChanged() {
        time = timeBase()
        timeVersion++
        publishSnapshot()
    }

    /** The session is dropped: loops are not available any more. */
    fun release() {
        hub.publishAudible(AudibleLoop.NONE)
        hub.publish(LoopSnapshot(available = false))
    }

    private fun gridMs(frame: Long): Double = time.frameToGridMs(frame).coerceAtLeast(0.0)

    private fun timeBase() = StreamTimeBase(rate, trim.delayFrames, streamOffsetFrames, trim.trimmedFrames)

    private fun framesOf(us: Long): Long = Math.round(us * rate / 1_000_000.0)

    /** The output frame being heard when the inner sink's position is [innerPositionUs]. */
    private fun heardFrame(innerPositionUs: Long): Long =
        (Math.round((innerPositionUs - baseUs) * rate / 1_000_000.0) + trim.trimmedFrames).coerceAtLeast(0L)

    private fun onEvent(event: LoopEvent) {
        when (event) {
            is LoopEvent.Engaged -> lastEngaged = event.loop
            is LoopEvent.Released -> Unit
            is LoopEvent.NeedsSeek -> hub.listener?.onNeedsSeek(time.frameToClockMs(event.loop.inFrame))
            is LoopEvent.Rejected -> when (event.reason) {
                LoopRejection.TOO_LONG -> {
                    val loop = lastRequested ?: engine.status.loop
                    if (loop != null) {
                        hub.listener?.onTooLong(time.frameToClockMs(loop.inFrame), time.frameToClockMs(loop.outFrame))
                    } else {
                        hub.listener?.onRefused(LoopRefusal.TOO_LONG)
                    }
                }
                LoopRejection.TOO_SHORT -> hub.listener?.onRefused(LoopRefusal.TOO_SHORT)
                LoopRejection.NO_LOOP -> hub.listener?.onRefused(LoopRefusal.NO_LOOP)
            }
        }
        publishSnapshot()
    }

    private fun publishSnapshot() {
        val status = engine.status
        if (status === publishedStatus && markedIn == publishedMark && timeVersion == publishedVersion) return
        publishedStatus = status
        publishedMark = markedIn
        publishedVersion = timeVersion
        val loop = status.loop
        hub.publish(
            LoopSnapshot(
                available = true,
                markedInMs = markedIn?.let(time::frameToClockMs),
                loopInMs = loop?.let { time.frameToClockMs(it.inFrame) },
                loopOutMs = loop?.let { time.frameToClockMs(it.outFrame) },
                armed = status.state == LoopState.ARMED,
                engaged = status.state == LoopState.LOOPING,
                markedInGridMs = markedIn?.let(::gridMs),
                loopInGridMs = loop?.let { gridMs(it.inFrame) },
                loopOutGridMs = loop?.let { gridMs(it.outFrame) },
            ),
        )
    }

    /** Tells the clock whether the audio heard now is inside a loop, and which (allocating only when that changes). */
    private fun publishAudible(looping: Boolean) {
        if (!looping) {
            if (audibleLooping) {
                audibleLooping = false
                hub.publishAudible(AudibleLoop.NONE)
            }
            return
        }
        val status = engine.status
        val loop = (if (status.state == LoopState.LOOPING) status.loop else null) ?: lastEngaged ?: return
        if (audibleLooping && loop.inFrame == audibleIn && loop.outFrame == audibleOut && audibleVersion == timeVersion) return
        audibleLooping = true
        audibleIn = loop.inFrame
        audibleOut = loop.outFrame
        audibleVersion = timeVersion
        hub.publishAudible(AudibleLoop(true, time.frameToClockMs(loop.inFrame), time.frameToClockMs(loop.outFrame)))
    }

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000L
    }
}
