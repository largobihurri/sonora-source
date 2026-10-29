package io.sonora.core.decoder

import io.sonora.core.domain.audio.PcmLayout
import io.sonora.core.domain.audio.PcmSamples

/**
 * Decoded samples waiting for the consumer. A platform decoder hands out blocks of its own size; this converts each
 * block to float and serves it back in the consumer's buffer size. Bytes of an incomplete frame at the end of a block
 * wait for the next block.
 *
 * Gapless trimming, in frames whatever the encoding: the first [skipFrames] frames ever appended are dropped (the
 * encoder delay), and the last [cutFrames] frames are held back until [endOfStream], which drops them (the encoder
 * padding).
 */
internal class PendingPcm(
    private val channelCount: Int,
    private val skipFrames: Int = 0,
    cutFrames: Int = 0,
) {
    init {
        require(skipFrames >= 0 && cutFrames >= 0) { "skipFrames $skipFrames and cutFrames $cutFrames must not be negative" }
    }

    private var samples = FloatArray(0)
    private var start = 0
    private var end = 0

    private var samplesToSkip = skipFrames.toLong() * channelCount

    /** Frames dropped so far by the start trim. */
    val skippedFrames: Int get() = skipFrames - (samplesToSkip / channelCount).toInt()
    private val heldSamples = cutFrames * channelCount
    private var ended = false

    private var carry = ByteArray(0)
    private var carryLength = 0
    private var carryLayout: PcmLayout? = null

    /** No frame can be handed out now (frames held back for the end trim may still be stored). */
    val isEmpty: Boolean get() = readableSamples == 0

    /** Nothing is stored at all, not even frames held back. */
    val holdsNothing: Boolean get() = start == end

    private val readableSamples: Int get() = if (ended) end - start else maxOf(end - start - heldSamples, 0)

    /** Converts the whole frames in [length] bytes of [bytes] at [offset], stored as [layout]. */
    fun append(bytes: ByteArray, offset: Int, length: Int, layout: PcmLayout) {
        // Leftover bytes of another encoding cannot complete a frame of this one.
        if (carryLayout != layout) carryLength = 0
        val source: ByteArray
        val sourceOffset: Int
        val available: Int
        if (carryLength == 0) {
            source = bytes
            sourceOffset = offset
            available = length
        } else {
            source = carry.copyOf(carryLength + length)
            bytes.copyInto(source, carryLength, offset, offset + length)
            sourceOffset = 0
            available = carryLength + length
        }
        val bytesPerFrame = channelCount * layout.bytesPerSample
        val frames = available / bytesPerFrame
        val sampleCount = frames * channelCount
        makeRoom(sampleCount)
        PcmSamples.toFloat(source, sourceOffset, layout, samples, end, sampleCount)
        end += sampleCount
        if (samplesToSkip > 0) {
            val dropped = minOf(samplesToSkip, (end - start).toLong()).toInt()
            start += dropped
            samplesToSkip -= dropped
            if (start == end) clear()
        }

        val used = frames * bytesPerFrame
        carryLength = available - used
        carryLayout = layout
        if (carryLength > 0) {
            if (carry.size < carryLength) carry = ByteArray(bytesPerFrame)
            source.copyInto(carry, 0, sourceOffset + used, sourceOffset + available)
        }
    }

    /** The stream has ended: the held-back frames of the end trim are dropped and the rest can be handed out. */
    fun endOfStream() {
        if (ended) return
        ended = true
        end = maxOf(start, end - heldSamples)
        if (start == end) clear()
    }

    /** Moves as many whole frames as fit into [buffer], from index 0, and returns how many it moved. */
    fun drainTo(buffer: FloatArray): Int {
        val frames = minOf(buffer.size / channelCount, readableSamples / channelCount)
        val count = frames * channelCount
        samples.copyInto(buffer, 0, start, start + count)
        start += count
        if (start == end) clear()
        return frames
    }

    private fun clear() {
        start = 0
        end = 0
    }

    private fun makeRoom(sampleCount: Int) {
        if (end + sampleCount <= samples.size) return
        val unread = end - start
        if (unread + sampleCount > samples.size) {
            val grown = FloatArray(maxOf(unread + sampleCount, samples.size * 2))
            samples.copyInto(grown, 0, start, end)
            samples = grown
        } else {
            samples.copyInto(samples, 0, start, end)
        }
        start = 0
        end = unread
    }
}
