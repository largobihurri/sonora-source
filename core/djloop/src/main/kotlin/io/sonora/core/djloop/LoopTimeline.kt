package io.sonora.core.djloop

/**
 * Maps the output timeline (frames handed to the audio sink, linear from the first one after a flush) back to the
 * source timeline (frames of the track), across a loop. The engine appends what it writes; the position clock asks.
 *
 * While looping, `source = in + ((phase) mod length)`; after an exit the source continues linearly from where the
 * loop left it, so the output runs ahead of the source by the time spent looping. A wrap is every jump of the
 * source: the jump into the loop, each repeat, a halve that jumps back, a re-anchor after a seek. Stretches that
 * continue each other are merged, so a long loop uses one entry; the oldest entries are dropped when [capacity] is
 * reached. Methods are synchronised: the playback thread appends and the position clock reads.
 */
class LoopTimeline(private val capacity: Int = DEFAULT_CAPACITY) {
    init {
        require(capacity >= 2) { "capacity must be 2 or more: $capacity" }
    }

    // One stretch per slot; a loop stretch has loopLength > 0 and then `source` is its in frame, else the first
    // source frame. All slots are preallocated: appending never allocates.
    private val outStart = LongArray(capacity)
    private val source = LongArray(capacity)
    private val loopLength = LongArray(capacity)
    private val phase = LongArray(capacity)
    private val length = LongArray(capacity)
    private val wrapsBefore = LongArray(capacity)
    private var head = 0
    private var count = 0

    /** Forgets everything: the next frame appended is output frame 0. */
    @Synchronized
    fun reset() {
        head = 0
        count = 0
    }

    /** Output frames appended so far. */
    val writtenFrames: Long
        @Synchronized get() = if (count == 0) 0L else outStart[newest()] + length[newest()]

    /** [frames] output frames that play source frames from [sourceStart] on. */
    @Synchronized
    fun appendLive(sourceStart: Long, frames: Long) {
        if (frames <= 0) return
        if (count > 0) {
            val last = newest()
            if (loopLength[last] == 0L && source[last] + length[last] == sourceStart) {
                length[last] += frames
                return
            }
        }
        push(sourceStart, 0L, 0L, frames)
    }

    /**
     * [frames] output frames of the loop `[inFrame, inFrame + loopLength)` starting at phase [startPhase] (0 until
     * [loopLength]) of an iteration.
     */
    @Synchronized
    fun appendLoop(inFrame: Long, loopLength: Long, startPhase: Long, frames: Long) {
        require(loopLength > 0 && startPhase in 0 until loopLength) { "bad loop: $loopLength, $startPhase" }
        if (frames <= 0) return
        if (count > 0) {
            val last = newest()
            if (this.loopLength[last] == loopLength && source[last] == inFrame &&
                (phase[last] + length[last]) % loopLength == startPhase
            ) {
                length[last] += frames
                return
            }
        }
        push(inFrame, loopLength, startPhase, frames)
    }

    /** The source frame heard at [outputFrame]; frames outside what is kept extrapolate from the nearest stretch. */
    @Synchronized
    fun sourceFrameAt(outputFrame: Long): Long {
        if (count == 0) return outputFrame
        val i = find(outputFrame)
        val t = outputFrame - outStart[i]
        return if (loopLength[i] == 0L) source[i] + t else source[i] + Math.floorMod(phase[i] + t, loopLength[i])
    }

    /** The jumps of the source up to and including [outputFrame]; never decreases. */
    @Synchronized
    fun wrapsAt(outputFrame: Long): Long {
        if (count == 0) return 0L
        val i = find(outputFrame)
        val t = (outputFrame - outStart[i]).coerceAtLeast(0L)
        return wrapsBefore[i] + if (loopLength[i] == 0L) 0L else (phase[i] + t) / loopLength[i]
    }

    /** Whether [outputFrame] plays a loop. */
    @Synchronized
    fun isLoopingAt(outputFrame: Long): Boolean = count > 0 && loopLength[find(outputFrame)] != 0L

    private fun newest(): Int = (head + count - 1) % capacity

    /** The stretch holding [outputFrame]: the newest one starting at or before it, else the oldest kept. */
    private fun find(outputFrame: Long): Int {
        var n = count - 1
        while (n > 0) {
            val i = (head + n) % capacity
            if (outStart[i] <= outputFrame) return i
            n--
        }
        return head
    }

    private fun push(sourceOrIn: Long, loopLen: Long, startPhase: Long, frames: Long) {
        var wraps = 0L
        var start = 0L
        if (count > 0) {
            val last = newest()
            start = outStart[last] + length[last]
            val internal = if (loopLength[last] == 0L) 0L else (phase[last] + length[last] - 1) / loopLength[last]
            val endSource = if (loopLength[last] == 0L) {
                source[last] + length[last]
            } else {
                val end = (phase[last] + length[last]) % loopLength[last]
                source[last] + if (end == 0L) loopLength[last] else end
            }
            val newSource = if (loopLen == 0L) sourceOrIn else sourceOrIn + startPhase
            wraps = wrapsBefore[last] + internal + if (newSource != endSource) 1 else 0
        }
        val slot: Int
        if (count == capacity) {
            slot = head
            head = (head + 1) % capacity
        } else {
            slot = (head + count) % capacity
            count++
        }
        outStart[slot] = start
        source[slot] = sourceOrIn
        loopLength[slot] = loopLen
        phase[slot] = startPhase
        length[slot] = frames
        wrapsBefore[slot] = wraps
    }

    companion object {
        const val DEFAULT_CAPACITY = 256
    }
}
