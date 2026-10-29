package io.sonora.core.djloop

import io.sonora.core.djmodel.loop.LoopFrames
import java.nio.ByteBuffer

/**
 * Where the engine writes the audio it produces, normally the inner audio sink.
 *
 * [write] consumes some or all of [buffer] (advancing its position, like `AudioSink.handleBuffer`) and returns `true`
 * when nothing remains. When it returns `false` the engine calls again later with the very same buffer instance and
 * the same [firstOutputFrame]: the output frame (counted from the first one after a flush) of the buffer's first frame,
 * which gives the sink its linear presentation timeline.
 */
fun interface LoopOutput {
    fun write(buffer: ByteBuffer, firstOutputFrame: Long): Boolean
}

/** What the controller asks of the engine; sent from any thread with [LoopEngine.submit]. */
sealed interface LoopCommand {
    /** Arm the loop [loop] (source frames): it engages when the audio reaches Out. */
    data class Set(val loop: LoopFrames) : LoopCommand

    /** Stop wrapping and play on linearly from wherever the playhead is: no jump, no blend. The loop is remembered. */
    data object Exit : LoopCommand

    /** Arm the remembered loop again. */
    data object Reloop : LoopCommand

    /** Halve the loop: Out moves to the middle (the loop wraps at once when the playhead is already beyond it). */
    data object Halve : LoopCommand

    /** Double the loop: the audio after Out plays (and is captured) and the loop wraps at the new Out. */
    data object Double : LoopCommand

    /** Forget the loop: playback goes on linearly if it was looping. */
    data object Clear : LoopCommand

    /**
     * Keep at least [frames] frames of captured audio from now on (capped by the memory cap): sent when the user marks
     * Loop In, so the audio from In is still in memory when the Out comes seconds later. Nothing is dropped, and the
     * memory is not given back while the engine lives (the sink builds a new engine for every stream).
     */
    data class Reserve(val frames: Long) : LoopCommand
}

enum class LoopRejection {
    /** The loop is shorter than the engine's minimum (5 ms). */
    TOO_SHORT,

    /** The loop (with its blend) does not fit the memory cap: use the seek based loop instead. */
    TOO_LONG,

    /** There is no remembered loop to repeat, halve or double. */
    NO_LOOP,
}

/** Things the engine reports to its [LoopListener], from the playback thread. */
sealed interface LoopEvent {
    /** The audio reached Out and the loop took over: the source position jumps back to In. */
    data class Engaged(val loop: LoopFrames) : LoopEvent

    /** The loop was left: the audio continues linearly from the source position at that moment. */
    data class Released(val loop: LoopFrames?) : LoopEvent

    /**
     * The loop could not engage because the audio at In is no longer in memory (or the memory cap would be exceeded):
     * seek to In and the armed loop will capture and wrap sample-accurately from there.
     */
    data class NeedsSeek(val loop: LoopFrames) : LoopEvent

    data class Rejected(val reason: LoopRejection) : LoopEvent
}

fun interface LoopListener {
    fun onEvent(event: LoopEvent)
}

enum class LoopState {
    /** No loop is armed; one may be remembered for [LoopCommand.Reloop]. */
    OFF,

    /** A loop is armed and waits for the audio to reach its Out. */
    ARMED,

    /** The loop is playing. */
    LOOPING,
}

/** A snapshot of the engine for other threads: [loop] is the armed, playing or remembered loop. */
data class LoopStatus(val state: LoopState, val loop: LoopFrames?, val seekNeeded: Boolean) {
    companion object {
        val OFF = LoopStatus(LoopState.OFF, null, false)
    }
}
