package io.sonora.core.djloop

import io.sonora.core.djmodel.loop.LoopFrames
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

/**
 * Sample-accurate loops on a PCM stream, the pure heart of the DJ loop sink (a Media3 `ForwardingAudioSink` hosts it).
 *
 * Audio goes in through [write] in source order and comes out to [LoopOutput] in order too, except while a loop plays.
 * Everything is counted in frames of the source: a loop is `[In, Out)` and is independent of the playback tempo.
 *
 * - **Capture.** Every frame consumed is also kept in a ring (about 2 s, more for a longer loop, never more than the
 *   memory cap). The frame count starts at the presentation time of the first buffer after a [flush].
 * - **Engage.** The loop is armed with [LoopCommand.Set]. The audio passes (and is captured) up to Out; there the engine
 *   consumes the post-roll `x[Out + j]` and writes, for the blend length N, the equal-power mix of `x[Out + j]`
 *   (fading out) and `x[In + j]` (fading in), then `x[In + N .. Out)`. From then on it stops consuming ([write]
 *   returns `false` and the decoder stalls: no seek) and repeats from memory, with period exactly Out - In. If Out is
 *   already behind the frontier (the loop was set late) the same blend joins the loop from the frontier.
 * - **Exit never jumps.** [LoopCommand.Exit] stops the wrapping where the playhead is (after the blend, if it is in
 *   one) and the output goes on linearly from that source frame: the rest of the iteration up to Out, the captured
 *   audio up to the frontier, then live input. It is sample-identical to the original stream.
 * - **Output timeline.** The sink is fed `firstOutputFrame` counting output frames linearly, never jumping; [timeline]
 *   maps an output frame back to the source.
 *
 * Commands may come from any thread ([submit]); they take effect at the next call of [write], [flush] or [pump], on the
 * playback thread, which is the only one allowed to call anything else. Buffers are preallocated: the per-buffer path
 * does not allocate (a command, an event or a status change does).
 */
class LoopEngine(
    val format: PcmFormat,
    private val output: LoopOutput,
    memoryCapBytes: Long = DEFAULT_MEMORY_CAP_BYTES,
    private val stagingFrames: Int = DEFAULT_STAGING_FRAMES,
) {
    private enum class Phase { PASS, JOIN, LOOP, TAIL }

    /** The listener of [LoopEvent]s; called on the playback thread. */
    @Volatile
    var listener: LoopListener? = null

    /** The state for other threads (the UI): updated whenever the loop engages, exits, is set or rejected. */
    @Volatile
    var status: LoopStatus = LoopStatus.OFF
        private set

    /** Output frame to source frame; written here, read by the position clock. */
    val timeline = LoopTimeline()

    /** Output frames produced since the last flush (playback thread). */
    var outputFrames: Long = 0L
        private set

    private val frameBytes = format.frameBytes
    private val rate = format.sampleRate
    private val capFrames: Long = memoryCapBytes / frameBytes
    private val minLoopFrames: Long = maxOf(rate / 200, 16).toLong()
    private val anchorToleranceFrames: Long = (rate / 50).toLong()

    private val commands = ConcurrentLinkedQueue<LoopCommand>()
    private val ring = FrameRing(frameBytes, minOf(2L * rate, capFrames).toInt())
    private val blend = LoopBlend(format)
    private val maxBlend = LoopBlend.blendFrames(rate, Long.MAX_VALUE)
    private val scratchFadeOut = ByteArray((maxBlend + 1) * frameBytes)
    private val scratchFadeIn = ByteArray((maxBlend + 1) * frameBytes)

    // The block handed to the output, filled by several steps and written once.
    private val staging = ByteArray(stagingFrames * frameBytes)
    // Little endian, as Media3's DefaultAudioSink insists on for the buffers it is handed.
    private val stagingBuffer = ByteBuffer.wrap(staging).order(ByteOrder.LITTLE_ENDIAN)
    private var stagedFrames = 0
    private var stagedFirstFrame = 0L
    private var stagedPending = false

    // Stream position.
    private var anchored = false
    private var inputInProgress = false
    private var checkAnchor = false
    private var inputPtsUs = 0L
    private var endOfInput = false

    // The loop: `def` is the armed or remembered one, the `active*` fields the one that plays.
    private var phase = Phase.PASS
    private var def: LoopFrames? = null
    private var armed = false
    private var seekSignalled = false
    private var exitRequested = false
    private var activeIn = 0L
    private var activeOut = 0L
    private var activeLength = 0L
    private var blendFrames = 0
    private var iterationPos = 0L
    private var postBase = 0L
    private var tailPos = 0L
    private var pendingOut = NONE

    /** Queues [command]; safe from any thread. */
    fun submit(command: LoopCommand) {
        commands.add(command)
    }

    /**
     * Consumes frames of [input] (whose first frame has presentation time [presentationTimeUs]) and writes what the
     * loop makes of them to the output. Returns `true` when [input] is fully consumed, `false` when the caller must
     * offer the same buffer again (the output is full, or a loop is playing and no input is taken).
     */
    fun write(input: ByteBuffer, presentationTimeUs: Long): Boolean {
        drainCommands()
        if (input.hasRemaining() && !inputInProgress) {
            inputInProgress = true
            checkAnchor = true
            inputPtsUs = presentationTimeUs
        }
        if (stagedPending) flushStaging()
        var chunks = 0
        while (!stagedPending && chunks < MAX_CHUNKS) {
            stagedFrames = 0
            stagedFirstFrame = outputFrames
            fillStaging(input)
            if (stagedFrames == 0) break
            chunks++
            flushStaging()
        }
        if (!input.hasRemaining()) inputInProgress = false
        return !input.hasRemaining()
    }

    /** Lets the engine run without input: pending commands, the output of a loop or of the captured tail. */
    fun pump() {
        write(EMPTY, 0L)
    }

    /**
     * The audio restarts (a seek, a discontinuity): the captured audio and the output timeline go, the armed loop stays
     * and captures again from the new position. A playing loop is left (without a jump: the audio is gone anyway).
     */
    fun flush() {
        phase = Phase.PASS
        anchored = false
        inputInProgress = false
        checkAnchor = false
        endOfInput = false
        exitRequested = false
        pendingOut = NONE
        seekSignalled = false
        stagedFrames = 0
        stagedPending = false
        outputFrames = 0L
        ring.clear(0L)
        timeline.reset()
        drainCommands()
        publish()
    }

    /** A new track: [flush] and forget the loop. */
    fun clear() {
        def = null
        armed = false
        flush()
    }

    /** The decoder has no more audio ([write] gets no more input). */
    fun endOfInput() {
        endOfInput = true
    }

    /** Whether something is still to be played out: staged audio, a loop, or the captured tail. */
    fun hasPendingData(): Boolean = stagedPending || phase != Phase.PASS

    /** Whether the stream has ended and nothing is left to play: call [pump] first. */
    fun isEnded(): Boolean = endOfInput && phase == Phase.PASS && !stagedPending

    // --- Commands -------------------------------------------------------------------------------------------------

    private fun drainCommands() {
        while (true) {
            val command = commands.poll() ?: break
            apply(command)
        }
    }

    private fun apply(command: LoopCommand) {
        when (command) {
            is LoopCommand.Set -> set(command.loop)
            LoopCommand.Exit -> {
                armed = false
                if (isLooping()) exitRequested = true
            }
            LoopCommand.Reloop -> if (def == null) reject(LoopRejection.NO_LOOP) else {
                armed = true
                seekSignalled = false
                exitRequested = false
            }
            LoopCommand.Halve -> resize(halve = true)
            LoopCommand.Double -> resize(halve = false)
            LoopCommand.Clear -> {
                def = null
                armed = false
                seekSignalled = false
                if (isLooping()) exitRequested = true
            }
            is LoopCommand.Reserve -> ring.ensureCapacity(minOf(command.frames.coerceAtLeast(0L), capFrames).toInt())
        }
        publish()
    }

    private fun set(loop: LoopFrames) {
        if (loop.length < minLoopFrames) return reject(LoopRejection.TOO_SHORT)
        val blendLength = LoopBlend.blendFrames(rate, loop.length)
        // How far behind the frontier In is decides at Out whether the loop can engage (NeedsSeek); only its size is refused here.
        val needed = loop.length + blendLength
        if (needed > capFrames) return reject(LoopRejection.TOO_LONG)
        ring.ensureCapacity(needed.toInt())
        def = loop
        armed = true
        seekSignalled = false
        // A loop that plays is left, linearly; the new one takes over from the frontier.
        if (isLooping()) exitRequested = true
    }

    /** Halves or doubles the loop that plays, is armed or is remembered, keeping In. */
    private fun resize(halve: Boolean) {
        val current = if (isLooping()) LoopFrames(activeIn, activeOut) else def
        if (current == null) return reject(LoopRejection.NO_LOOP)
        val length = if (halve) current.length / 2 else current.length * 2
        if (length < minLoopFrames) return reject(LoopRejection.TOO_SHORT)
        val blendLength = LoopBlend.blendFrames(rate, length)
        if (length + blendLength > capFrames) return reject(LoopRejection.TOO_LONG)
        val resized = LoopFrames(current.inFrame, current.inFrame + length)
        def = resized
        if (halve) {
            // A playing loop shrinks in place, once it is out of its blend: it wraps at the new Out, at once when the
            // playhead is already beyond it.
            if (isLooping()) pendingOut = resized.outFrame
        } else {
            ring.ensureCapacity((length + blendLength).toInt())
            armed = true
            seekSignalled = false
            // Doubling releases the hold: the audio after Out plays, is captured, and the loop wraps at the new Out.
            if (isLooping()) exitRequested = true
        }
    }

    private fun reject(reason: LoopRejection) {
        listener?.onEvent(LoopEvent.Rejected(reason))
    }

    private fun isLooping() = phase == Phase.JOIN || phase == Phase.LOOP

    private fun publish() {
        val loop = def
        val state = when {
            isLooping() -> LoopState.LOOPING
            armed && loop != null -> LoopState.ARMED
            else -> LoopState.OFF
        }
        status = LoopStatus(state, if (isLooping()) LoopFrames(activeIn, activeOut) else loop, seekSignalled)
    }

    // --- Production -----------------------------------------------------------------------------------------------

    private fun flushStaging(): Boolean {
        if (!stagedPending) {
            stagingBuffer.clear()
            stagingBuffer.limit(stagedFrames * frameBytes)
        }
        val done = output.write(stagingBuffer, stagedFirstFrame)
        stagedPending = !done
        if (done) stagedFrames = 0
        return done
    }

    private fun fillStaging(input: ByteBuffer) {
        var guard = 0
        while (stagedFrames < stagingFrames && guard < MAX_STEPS) {
            val produced = when (phase) {
                Phase.PASS -> stepPass(input)
                Phase.JOIN -> stepJoin(input)
                Phase.LOOP -> stepLoop()
                Phase.TAIL -> stepTail()
            }
            if (produced == 0) break
            if (produced == CHANGED) guard++ else guard = 0
        }
    }

    /** Appends [frames] produced frames (already in the staging block) to the counts. */
    private fun staged(frames: Int) {
        stagedFrames += frames
        outputFrames += frames
    }

    private fun freeFrames() = stagingFrames - stagedFrames

    private fun stepPass(input: ByteBuffer): Int {
        val available = input.remaining() / frameBytes
        if (checkAnchor && available > 0) anchorToInput()
        val loop = def
        if (anchored && armed && loop != null && !seekSignalled && ring.endFrame >= loop.outFrame && tryEngage(loop)) {
            return CHANGED
        }
        if (available == 0) return 0
        var frames = minOf(available, freeFrames())
        if (anchored && armed && loop != null && !seekSignalled && ring.endFrame < loop.outFrame) {
            frames = minOf(frames.toLong(), loop.outFrame - ring.endFrame).toInt()
        }
        val sourceStart = ring.endFrame
        input.get(staging, stagedFrames * frameBytes, frames * frameBytes)
        ring.append(staging, stagedFrames * frameBytes, frames)
        timeline.appendLive(sourceStart, frames.toLong())
        staged(frames)
        return frames
    }

    /** The first buffer after a flush, or one that does not follow the last: where the source frame count stands. */
    private fun anchorToInput() {
        checkAnchor = false
        val frame = Math.round(inputPtsUs * rate / MICROS_PER_SECOND)
        if (!anchored) {
            anchored = true
            ring.clear(frame)
        } else if (abs(frame - ring.endFrame) > anchorToleranceFrames) {
            ring.clear(frame)
            if (phase == Phase.JOIN) phase = Phase.PASS
        }
    }

    /** Out is reached (or already behind the frontier): start the loop, if the audio at In is still in memory. */
    private fun tryEngage(loop: LoopFrames): Boolean {
        val blendLength = LoopBlend.blendFrames(rate, loop.length)
        val needed = ring.endFrame + blendLength - loop.inFrame
        if (loop.inFrame < ring.startFrame || needed > capFrames) {
            seekSignalled = true
            listener?.onEvent(LoopEvent.NeedsSeek(loop))
            publish()
            return false
        }
        ring.ensureCapacity(needed.toInt())
        activeIn = loop.inFrame
        activeOut = loop.outFrame
        activeLength = loop.length
        blendFrames = blendLength
        iterationPos = 0L
        postBase = activeOut
        exitRequested = false
        pendingOut = NONE
        phase = Phase.JOIN
        listener?.onEvent(LoopEvent.Engaged(loop))
        publish()
        return true
    }

    /** The first iteration's blend: the live post-roll fades out against the start of the loop. */
    private fun stepJoin(input: ByteBuffer): Int {
        val available = input.remaining() / frameBytes
        if (checkAnchor && available > 0) {
            anchorToInput()
            if (phase != Phase.JOIN) return CHANGED
        }
        if (available == 0) {
            if (endOfInput) finishJoinAtEnd()
            return if (endOfInput) CHANGED else 0
        }
        val frames = minOf(available, freeFrames(), blendFrames - iterationPos.toInt())
        input.get(scratchFadeOut, 0, frames * frameBytes)
        ring.copy(activeIn + iterationPos, frames, scratchFadeIn, 0)
        blend.blend(
            scratchFadeOut, 0, scratchFadeIn, 0, staging, stagedFrames * frameBytes, frames, iterationPos.toInt(), blendFrames,
        )
        ring.append(scratchFadeOut, 0, frames)
        timeline.appendLoop(activeIn, activeLength, iterationPos, frames.toLong())
        staged(frames)
        iterationPos += frames
        if (iterationPos >= blendFrames) phase = Phase.LOOP
        return frames
    }

    /** The track ended inside the first blend: later repeats blend as much post-roll as there is. */
    private fun finishJoinAtEnd() {
        blendFrames = minOf(iterationPos, maxOf(0L, ring.endFrame - activeOut)).toInt()
        phase = Phase.LOOP
    }

    private fun stepLoop(): Int {
        if (exitRequested && iterationPos >= blendFrames) {
            leaveLoop()
            return CHANGED
        }
        if (pendingOut != NONE && iterationPos >= blendFrames) applyResize()
        if (iterationPos >= activeLength) {
            iterationPos = 0L
            postBase = activeOut
        }
        val offset = stagedFrames * frameBytes
        val frames: Int
        if (iterationPos < blendFrames) {
            frames = minOf(freeFrames().toLong(), blendFrames - iterationPos).toInt()
            ring.copy(postBase + iterationPos, frames, scratchFadeOut, 0)
            ring.copy(activeIn + iterationPos, frames, scratchFadeIn, 0)
            blend.blend(scratchFadeOut, 0, scratchFadeIn, 0, staging, offset, frames, iterationPos.toInt(), blendFrames)
        } else {
            frames = minOf(freeFrames().toLong(), activeLength - iterationPos).toInt()
            ring.copy(activeIn + iterationPos, frames, staging, offset)
        }
        timeline.appendLoop(activeIn, activeLength, iterationPos, frames.toLong())
        staged(frames)
        iterationPos += frames
        return frames
    }

    /** The loop shrinks to [pendingOut]; when the playhead is beyond it, it jumps to In through a blend from there. */
    private fun applyResize() {
        activeOut = pendingOut
        pendingOut = NONE
        activeLength = activeOut - activeIn
        blendFrames = LoopBlend.blendFrames(rate, activeLength)
        if (iterationPos >= activeLength) {
            postBase = activeIn + iterationPos
            iterationPos = 0L
        }
        publish()
    }

    /** Stops wrapping: the output goes on from the source frame the loop was about to play. */
    private fun leaveLoop() {
        val left = LoopFrames(activeIn, activeOut)
        phase = Phase.TAIL
        tailPos = activeIn + iterationPos
        exitRequested = false
        pendingOut = NONE
        seekSignalled = false
        listener?.onEvent(LoopEvent.Released(left))
        publish()
    }

    /** The captured audio between the point the loop was left and the frontier, which the stalled decoder owes us. */
    private fun stepTail(): Int {
        val remaining = ring.endFrame - tailPos
        if (remaining <= 0L) {
            phase = Phase.PASS
            publish()
            return CHANGED
        }
        val frames = minOf(freeFrames().toLong(), remaining).toInt()
        ring.copy(tailPos, frames, staging, stagedFrames * frameBytes)
        timeline.appendLive(tailPos, frames.toLong())
        tailPos += frames
        staged(frames)
        return frames
    }

    companion object {
        const val DEFAULT_MEMORY_CAP_BYTES: Long = 32L * 1024 * 1024
        const val DEFAULT_STAGING_FRAMES: Int = 4096

        /** Staging blocks written per call: a stalled decoder offers no input to end the call, so the loop must. */
        private const val MAX_CHUNKS = 8
        private const val MAX_STEPS = 16
        private const val CHANGED = -1
        private const val NONE = -1L
        private const val MICROS_PER_SECOND = 1_000_000.0
        private val EMPTY: ByteBuffer = ByteBuffer.allocate(0)
    }
}
