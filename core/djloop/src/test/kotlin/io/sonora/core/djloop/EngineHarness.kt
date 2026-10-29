package io.sonora.core.djloop

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Drives a [LoopEngine] like the player does: offers a source in buffers, retries a buffer the engine did not take,
 * and gives the output a budget so a test can stop at an exact output frame.
 *
 * The default source is a counter in 32-bit PCM: channel 0 of frame i holds i and channel 1 holds -i, so every output
 * frame says which source frame it is (a blended frame holds a mix, which tests recompute with [blendOf]).
 */
class RecordingOutput(private val frameBytes: Int) : LoopOutput {
    private var data = ByteArray(1 shl 16)
    var totalFrames = 0L
        private set

    /** Frames the output still accepts. */
    var budget = Int.MAX_VALUE
    val offeredBuffers = ArrayList<ByteBuffer>()
    val offeredFirstFrames = ArrayList<Long>()

    /** For every offer: the output frame of the buffer's next unconsumed frame, which must equal what was accepted so far. */
    val offeredNextFrames = ArrayList<Long>()
    val acceptedBeforeOffer = ArrayList<Long>()

    override fun write(buffer: ByteBuffer, firstOutputFrame: Long): Boolean {
        offeredBuffers += buffer
        offeredFirstFrames += firstOutputFrame
        offeredNextFrames += firstOutputFrame + buffer.position() / frameBytes
        acceptedBeforeOffer += totalFrames
        val frames = minOf(buffer.remaining() / frameBytes, budget)
        val bytes = frames * frameBytes
        if ((totalFrames * frameBytes + bytes) > data.size) data = data.copyOf(maxOf(data.size * 2, (totalFrames * frameBytes + bytes).toInt()))
        buffer.get(data, (totalFrames * frameBytes).toInt(), bytes)
        totalFrames += frames
        budget -= frames
        return !buffer.hasRemaining()
    }

    fun intAt(frame: Long, channel: Int = 0): Int {
        val at = (frame * frameBytes + channel * 4).toInt()
        return ByteBuffer.wrap(data, at, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    fun bytes(fromFrame: Long, frames: Int): ByteArray =
        data.copyOfRange((fromFrame * frameBytes).toInt(), ((fromFrame + frames) * frameBytes).toInt())
}

class EngineHarness(
    val totalFrames: Int,
    val format: PcmFormat = PcmFormat(48_000, 2, PcmEncoding.PCM_32),
    memoryCapBytes: Long = LoopEngine.DEFAULT_MEMORY_CAP_BYTES,
    stagingFrames: Int = 1024,
    val chunkFrames: Int = 480,
    startFrame: Int = 0,
    source: ByteArray? = null,
) {
    val output = RecordingOutput(format.frameBytes)
    val engine = LoopEngine(format, output, memoryCapBytes, stagingFrames)
    val events = ArrayList<LoopEvent>()
    val source: ByteArray = source ?: counterSource(totalFrames)

    /** The driver offers no frame at or after this one. */
    var limit = totalFrames

    /** The next source frame the driver offers. */
    var nextFrame = startFrame
    var consumedFrames = 0L
        private set
    private var current: ByteBuffer? = null
    private var currentStart = 0

    init {
        engine.listener = LoopListener { events += it }
    }

    /** Offers input until the output holds [target] frames (or nothing moves any more). */
    fun runTo(target: Long) {
        var idle = 0
        while (output.totalFrames < target && idle < 4) {
            output.budget = (target - output.totalFrames).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val before = output.totalFrames
            val consumedBefore = consumedFrames
            val buffer = current ?: nextBuffer()
            if (buffer == null) {
                engine.pump()
            } else {
                val remaining = buffer.remaining()
                val done = engine.write(buffer, presentationUs(currentStart))
                consumedFrames += (remaining - buffer.remaining()) / format.frameBytes
                if (done) current = null
            }
            idle = if (output.totalFrames == before && consumedFrames == consumedBefore) idle + 1 else 0
        }
    }

    /** Runs until the whole source has been offered and taken, the output taking everything it is given. */
    fun runToEnd() {
        output.budget = Int.MAX_VALUE
        var guard = 0
        while (guard++ < 100_000) {
            val buffer = current ?: nextBuffer() ?: break
            val remaining = buffer.remaining()
            val done = engine.write(buffer, presentationUs(currentStart))
            consumedFrames += (remaining - buffer.remaining()) / format.frameBytes
            if (done) current = null else break
        }
    }

    private fun nextBuffer(): ByteBuffer? {
        if (nextFrame >= limit) return null
        val frames = minOf(chunkFrames, limit - nextFrame)
        currentStart = nextFrame
        val buffer = ByteBuffer.wrap(source, nextFrame * format.frameBytes, frames * format.frameBytes)
        nextFrame += frames
        current = buffer
        return buffer
    }

    /** Skips to [frame] as a seek does: flush the engine, drop the buffer in hand, offer from [frame]. */
    fun seekTo(frame: Int) {
        engine.flush()
        output.budget = Int.MAX_VALUE
        current = null
        nextFrame = frame
    }

    /** Continues from [frame] without a flush: the next buffer's presentation time jumps. */
    fun jumpTo(frame: Int, newLimit: Int) {
        current = null
        nextFrame = frame
        limit = newLimit
    }

    private fun presentationUs(frame: Int): Long = Math.round(frame * 1_000_000.0 / format.sampleRate)

    /** Source frame that output frame [i] holds (valid for frames that are not blended). */
    fun sourceAt(i: Long): Int = output.intAt(i)

    /** The mix the blend makes of source frames [fadingOut] and [fadingIn] at blend position [index] of [length]. */
    fun blendOf(fadingOut: Int, fadingIn: Int, index: Int, length: Int): Int {
        val a = counterSource(1, fadingOut)
        val b = counterSource(1, fadingIn)
        val out = ByteArray(format.frameBytes)
        LoopBlend(format).blend(a, 0, b, 0, out, 0, 1, index, length)
        return ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).int
    }

    companion object {
        /** Frame i: channel 0 = i + [first], channel 1 = -(i + [first]); 32-bit PCM, little endian. */
        fun counterSource(frames: Int, first: Int = 0): ByteArray {
            val buffer = ByteBuffer.allocate(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until frames) {
                buffer.putInt(i + first)
                buffer.putInt(-(i + first))
            }
            return buffer.array()
        }
    }
}
