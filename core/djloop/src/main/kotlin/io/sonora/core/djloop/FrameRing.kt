package io.sonora.core.djloop

/**
 * The most recent source audio, [capacityFrames] frames of [frameBytes] each, addressed by source frame: it holds
 * `[startFrame, endFrame)`, and [append] always adds at [endFrame]. Not thread safe; the engine owns it.
 */
internal class FrameRing(private val frameBytes: Int, initialFrames: Int) {
    private var data = ByteArray(initialFrames * frameBytes)
    private var writeIndex = 0
    private var filled = 0

    var capacityFrames: Int = initialFrames
        private set

    /** Source frame that the next appended frame gets. */
    var endFrame: Long = 0L
        private set

    val startFrame: Long get() = endFrame - filled

    /** Empties the ring; the next frame appended is source frame [anchor]. */
    fun clear(anchor: Long) {
        writeIndex = 0
        filled = 0
        endFrame = anchor
    }

    fun append(src: ByteArray, offset: Int, frames: Int) {
        var from = offset
        var count = frames
        if (count >= capacityFrames) {
            from += (count - capacityFrames) * frameBytes
            count = capacityFrames
            filled = 0
            writeIndex = 0
            endFrame += frames - count
        }
        val firstPart = minOf(count, capacityFrames - writeIndex)
        System.arraycopy(src, from, data, writeIndex * frameBytes, firstPart * frameBytes)
        if (count > firstPart) System.arraycopy(src, from + firstPart * frameBytes, data, 0, (count - firstPart) * frameBytes)
        writeIndex = (writeIndex + count) % capacityFrames
        filled = minOf(capacityFrames, filled + count)
        endFrame += count
    }

    /** Copies [frames] frames starting at source frame [sourceStart] (inside `[startFrame, endFrame)`) to [dst]. */
    fun copy(sourceStart: Long, frames: Int, dst: ByteArray, dstOffset: Int) {
        require(sourceStart >= startFrame && sourceStart + frames <= endFrame) {
            "frames $sourceStart+$frames are not in the ring [$startFrame, $endFrame)"
        }
        val back = (endFrame - sourceStart).toInt()
        val readIndex = Math.floorMod(writeIndex - back, capacityFrames)
        val firstPart = minOf(frames, capacityFrames - readIndex)
        System.arraycopy(data, readIndex * frameBytes, dst, dstOffset, firstPart * frameBytes)
        if (frames > firstPart) System.arraycopy(data, 0, dst, dstOffset + firstPart * frameBytes, (frames - firstPart) * frameBytes)
    }

    /** Grows the ring to at least [frames], keeping what it holds. Allocates; the engine only calls it for a command. */
    fun ensureCapacity(frames: Int) {
        if (frames <= capacityFrames) return
        val bigger = ByteArray(frames * frameBytes)
        copy(startFrame, filled, bigger, 0)
        data = bigger
        writeIndex = filled % frames
        capacityFrames = frames
    }
}
