package io.sonora.core.playback.loop

import io.sonora.core.djmodel.loop.LoopQuantizer
import io.sonora.core.domain.playback.ClockSample
import io.sonora.core.domain.playback.LoopClockSource
import java.util.concurrent.ConcurrentLinkedQueue

/** What the user asked of the loop, as the controller posts it for the audio thread to resolve at the audible frame. */
internal sealed interface LoopRequest {
    /** Loop In: mark the audible frame (snapped to a beat with Quantize) and keep the audio from there. */
    data object MarkIn : LoopRequest

    /** Loop Out: close the loop opened by [MarkIn] at the audible frame. */
    data object MarkOut : LoopRequest

    /** A loop of [beats] beats from the effective grid. */
    data class BeatLoop(val beats: Double) : LoopRequest

    data object HalveLoop : LoopRequest
    data object DoubleLoop : LoopRequest
    data object ExitLoop : LoopRequest
    data object Reloop : LoopRequest
    data object ClearLoop : LoopRequest

    /**
     * A saved loop: `[inMs, outMs)` on the grid's time line, used as given. The player jumps to In unless the audio heard
     * is already inside the loop.
     */
    data class Engage(val inMs: Double, val outMs: Double) : LoopRequest
}

/** Why a request did nothing. */
internal enum class LoopRefusal {
    /** Loops cannot run on this stream (DJ mode off, bit-perfect output, compressed or unsupported PCM, no audio yet). */
    NOT_AVAILABLE,

    /** Loop Out without a Loop In. */
    NO_IN,

    /** Halve, double or reloop with no loop to act on. */
    NO_LOOP,

    /** A beat loop on a track without a beat grid. */
    NO_GRID,

    /** The loop would be shorter than the minimum. */
    TOO_SHORT,

    /** The loop does not fit the capture memory. */
    TOO_LONG,

    /** The loop would end after the track. */
    PAST_END,
}

/**
 * What the audio thread needs to place loops on the beats: the effective grid of the track playing (a [LoopQuantizer]
 * over it, `null` while there is no analysis) and whether Quantize is on. Immutable: the controller swaps it.
 */
internal class LoopContext(val quantizer: LoopQuantizer?, val quantize: Boolean) {
    companion object {
        val NONE = LoopContext(null, false)
    }
}

/** The loop as the audio thread last knew it, in the time of the playback clock (ms), for the controller's state. */
internal data class LoopSnapshot(
    val available: Boolean = false,
    /** Loop In pressed, no Out yet. */
    val markedInMs: Double? = null,
    /** The loop playing, armed or remembered. */
    val loopInMs: Double? = null,
    val loopOutMs: Double? = null,
    /** A loop waits for the audio to reach its Out. */
    val armed: Boolean = false,
    /** The loop plays (at the frontier of the audio; the speaker follows within the AudioTrack's buffer). */
    val engaged: Boolean = false,
    /** The same marks on the beat grid's time line (the time saved loops are kept in); `null` as their clock times are. */
    val markedInGridMs: Double? = null,
    val loopInGridMs: Double? = null,
    val loopOutGridMs: Double? = null,
) {
    companion object {
        /** Loops not available (no session hosts the stream): the one value published over and over, so nothing is allocated. */
        val UNAVAILABLE = LoopSnapshot()
    }
}

/** The loop being heard right now, as the clock reads it: published with each position the sink reports. */
internal class AudibleLoop(val looping: Boolean, val inMs: Double, val outMs: Double) {
    fun sameAs(other: AudibleLoop): Boolean =
        looping == other.looping && (!looping || (inMs == other.inMs && outMs == other.outMs))

    companion object {
        val NONE = AudibleLoop(false, 0.0, 0.0)
    }
}

/** Hears the audio thread: called on the playback thread. */
internal interface LoopHubListener {
    fun onSnapshot(snapshot: LoopSnapshot)

    /** The audio at In is no longer in memory: seek to In (a clock time, ms) and the armed loop captures and wraps. */
    fun onNeedsSeek(inMs: Double)

    /** The loop is too long for the capture memory: the controller may run a seek based loop instead. */
    fun onTooLong(inMs: Double, outMs: Double)

    fun onRefused(refusal: LoopRefusal)
}

/**
 * The meeting point of the loop controller (main thread) and the loop audio sink (playback thread): requests go in,
 * snapshots and events come out, and the clock reads what is being heard. Thread safe; allocation-free for the clock.
 */
internal class LoopHub : LoopClockSource {
    private val requests = ConcurrentLinkedQueue<LoopRequest>()

    /** Whether DJ mode is on: the sink only hosts loops (and the AudioTrack is only kept small) while it is. */
    @Volatile
    var enabled: Boolean = false

    @Volatile
    var context: LoopContext = LoopContext.NONE

    @Volatile
    var snapshot: LoopSnapshot = LoopSnapshot()
        private set

    @Volatile
    var audible: AudibleLoop = AudibleLoop.NONE
        private set

    @Volatile
    var listener: LoopHubListener? = null

    /** Any thread. */
    fun post(request: LoopRequest) {
        requests.add(request)
    }

    /** Playback thread. */
    fun poll(): LoopRequest? = requests.poll()

    fun hasRequests(): Boolean = !requests.isEmpty()

    /** Playback thread: publishes [next] and tells the listener when it changed. */
    fun publish(next: LoopSnapshot) {
        if (next == snapshot) return
        snapshot = next
        listener?.onSnapshot(next)
    }

    /** Playback thread. */
    fun publishAudible(next: AudibleLoop) {
        if (!next.sameAs(audible)) audible = next
    }

    override fun readInto(into: ClockSample) {
        val heard = audible
        into.looping = heard.looping
        into.loopInMs = heard.inMs
        into.loopOutMs = heard.outMs
    }
}
