package io.sonora.core.playback.loop

import io.sonora.core.djloop.LoopCommand
import io.sonora.core.djloop.LoopState
import io.sonora.core.djloop.LoopStatus
import io.sonora.core.djmodel.loop.Loop
import io.sonora.core.djmodel.loop.LoopFrames
import io.sonora.core.djmodel.loop.LoopQuantizer
import io.sonora.core.djmodel.loop.LoopTime

/**
 * What a [LoopRequest] comes to: the engine [commands], the Loop In mark afterwards ([markedIn], a decoder frame, or
 * `null` for none) and, when nothing can be done, the [refusal] (then the commands are empty and the mark unchanged).
 * [jumpToFrame] is a decoder frame the player must seek to (a saved loop engaged from outside it), `null` for none.
 */
internal class LoopResolution(
    val commands: List<LoopCommand>,
    val markedIn: Long?,
    val refusal: LoopRefusal?,
    val jumpToFrame: Long? = null,
)

/**
 * Turns the user's presses into loop engine commands. Pure: the audio thread gives it the frame being heard
 * ([nowFrame], a decoder frame), the stream's time base, the grid and Quantize ([LoopContext]), the Loop In mark and
 * the engine's status.
 *
 * With Quantize on and a grid, In and Out snap to the nearest beat of the effective grid (computed on grid time, played
 * on decoder frames: [StreamTimeBase]); otherwise the loop is exactly the frames heard at the two presses.
 */
internal object LoopRequestResolver {
    /** How much audio is kept for a Loop In whose Out has not come yet. */
    const val MARK_RESERVE_SECONDS: Int = 20

    fun resolve(
        request: LoopRequest,
        nowFrame: Long,
        time: StreamTimeBase,
        context: LoopContext,
        markedIn: Long?,
        status: LoopStatus,
    ): LoopResolution {
        val quantizer = context.quantizer
        return when (request) {
            LoopRequest.MarkIn -> markIn(nowFrame, time, context)
            LoopRequest.MarkOut -> markOut(nowFrame, time, context, markedIn)
            is LoopRequest.BeatLoop -> beatLoop(request.beats, nowFrame, time, context, markedIn, status)
            LoopRequest.HalveLoop -> {
                if (status.loop == null) refused(LoopRefusal.NO_LOOP, markedIn) else commands(markedIn, LoopCommand.Halve)
            }
            LoopRequest.DoubleLoop -> {
                val loop = status.loop
                when {
                    loop == null -> refused(LoopRefusal.NO_LOOP, markedIn)
                    quantizer != null && quantizer.double(loop.asLoop(time)) == null -> refused(LoopRefusal.PAST_END, markedIn)
                    else -> commands(markedIn, LoopCommand.Double)
                }
            }
            LoopRequest.ExitLoop -> commands(markedIn, LoopCommand.Exit)
            LoopRequest.Reloop -> commands(markedIn, LoopCommand.Reloop)
            LoopRequest.ClearLoop -> commands(null, LoopCommand.Clear)
            is LoopRequest.Engage -> engage(request, nowFrame, time, markedIn)
        }
    }

    /** A saved loop at its grid times; out of the audio heard now, the player goes to its In (the armed loop then captures). */
    private fun engage(request: LoopRequest.Engage, nowFrame: Long, time: StreamTimeBase, markedIn: Long?): LoopResolution {
        if (!(request.outMs > request.inMs)) return refused(LoopRefusal.TOO_SHORT, markedIn)
        val loop = time.gridFrames(request.inMs.coerceAtLeast(0.0), request.outMs)
        val inside = nowFrame >= loop.inFrame && nowFrame < loop.outFrame
        return LoopResolution(listOf(LoopCommand.Set(loop)), null, null, jumpToFrame = if (inside) null else loop.inFrame)
    }

    private fun markIn(nowFrame: Long, time: StreamTimeBase, context: LoopContext): LoopResolution {
        val inFrame = snapped(nowFrame, time, context)
        // A new In forgets the loop that is set (Exit never forgets it; this does) and keeps the audio from In on.
        val reserve = LoopCommand.Reserve(MARK_RESERVE_SECONDS.toLong() * time.sampleRate)
        return LoopResolution(listOf(LoopCommand.Clear, reserve), inFrame, null)
    }

    private fun markOut(nowFrame: Long, time: StreamTimeBase, context: LoopContext, markedIn: Long?): LoopResolution {
        val inFrame = markedIn ?: return refused(LoopRefusal.NO_IN, null)
        val quantizer = context.quantizer
        val loop: LoopFrames = if (context.quantize && quantizer != null && quantizer.hasGrid) {
            val manual = quantizer.manualLoop(time.frameToGridMs(inFrame).coerceAtLeast(0.0), time.frameToGridMs(nowFrame), quantize = true)
                ?: return refused(shortOrPastEnd(time.frameToGridMs(nowFrame) - time.frameToGridMs(inFrame)), markedIn)
            time.gridFrames(manual.inMs, manual.outMs)
        } else {
            if (nowFrame - inFrame < LoopTime.msToFrames(Loop.MIN_LENGTH_MS, time.sampleRate)) {
                return refused(LoopRefusal.TOO_SHORT, markedIn)
            }
            LoopFrames(inFrame, nowFrame)
        }
        return LoopResolution(listOf(LoopCommand.Set(loop)), null, null)
    }

    private fun beatLoop(
        beats: Double,
        nowFrame: Long,
        time: StreamTimeBase,
        context: LoopContext,
        markedIn: Long?,
        status: LoopStatus,
    ): LoopResolution {
        val quantizer = context.quantizer
        if (quantizer == null || !quantizer.hasGrid) return refused(LoopRefusal.NO_GRID, markedIn)
        val active = status.loop?.takeIf { status.state == LoopState.LOOPING || status.state == LoopState.ARMED }
        val loop = if (active != null) {
            // Another size from the loop that is set keeps its In.
            quantizer.beatLoop(time.frameToGridMs(active.inFrame).coerceAtLeast(0.0), beats, quantizeIn = false)
        } else {
            quantizer.beatLoop(time.frameToGridMs(nowFrame).coerceAtLeast(0.0), beats, quantizeIn = context.quantize)
        } ?: return refused(LoopRefusal.PAST_END, markedIn)
        return LoopResolution(listOf(LoopCommand.Set(time.gridFrames(loop.inMs, loop.outMs))), null, null)
    }

    /** [frame], or the frame of the nearest beat to it when Quantize is on and the track has a grid. */
    private fun snapped(frame: Long, time: StreamTimeBase, context: LoopContext): Long {
        val quantizer = context.quantizer
        if (!context.quantize || quantizer == null || !quantizer.hasGrid) return frame
        return time.gridMsToFrame(quantizer.snap(time.frameToGridMs(frame).coerceAtLeast(0.0)))
    }

    private fun shortOrPastEnd(lengthMs: Double): LoopRefusal =
        if (lengthMs < Loop.MIN_LENGTH_MS) LoopRefusal.TOO_SHORT else LoopRefusal.PAST_END

    private fun LoopFrames.asLoop(time: StreamTimeBase): Loop =
        Loop(time.frameToGridMs(inFrame).coerceAtLeast(0.0), time.frameToGridMs(outFrame).coerceAtLeast(1.0))

    private fun commands(markedIn: Long?, vararg commands: LoopCommand) = LoopResolution(commands.toList(), markedIn, null)

    private fun refused(refusal: LoopRefusal, markedIn: Long?) = LoopResolution(emptyList(), markedIn, refusal)
}
