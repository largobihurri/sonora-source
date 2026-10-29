package io.sonora.core.djmodel.loop

import kotlin.math.roundToLong

/** A loop on the track's own time line: playback repeats `[inMs, outMs)`. Times are milliseconds of source audio. */
data class Loop(val inMs: Double, val outMs: Double) {
    init {
        require(inMs.isFinite() && inMs >= 0.0) { "inMs must be zero or more: $inMs" }
        require(outMs.isFinite() && outMs > inMs) { "outMs must be after inMs: $inMs, $outMs" }
    }

    val lengthMs: Double get() = outMs - inMs

    /** The loop as whole frames of a stream at [sampleRate] Hz. */
    fun toFrames(sampleRate: Int): LoopFrames =
        LoopFrames(LoopTime.msToFrames(inMs, sampleRate), LoopTime.msToFrames(outMs, sampleRate))

    companion object {
        /** No loop is shorter than this: below it the click-free wrap has no room and the beat grid is no help. */
        const val MIN_LENGTH_MS: Double = 10.0
    }
}

/** A loop in source frames: `[inFrame, outFrame)`, what the audio engine plays; see [Loop.toFrames]. */
data class LoopFrames(val inFrame: Long, val outFrame: Long) {
    init {
        require(inFrame >= 0) { "inFrame must be zero or more: $inFrame" }
        require(outFrame > inFrame) { "outFrame must be after inFrame: $inFrame, $outFrame" }
    }

    val length: Long get() = outFrame - inFrame
}

/** Time and frame conversions shared by the loop maths and the audio engine. */
object LoopTime {
    /** `round(ms * rate / 1000)`, ties toward the later frame. */
    fun msToFrames(ms: Double, sampleRate: Int): Long = (ms * sampleRate / MS_PER_SECOND).roundToLong()

    fun framesToMs(frames: Long, sampleRate: Int): Double = frames * MS_PER_SECOND / sampleRate

    private const val MS_PER_SECOND = 1000.0
}

/** The beat lengths of a loop the player offers: 1/32 of a beat up to 32 beats, each twice the one before. */
object LoopSizes {
    val BEATS: List<Double> = listOf(1.0 / 32, 1.0 / 16, 1.0 / 8, 1.0 / 4, 1.0 / 2, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0)

    val MIN_BEATS: Double = BEATS.first()
    val MAX_BEATS: Double = BEATS.last()

    /** Half of [beats], or [beats] itself at the smallest size. */
    fun halved(beats: Double): Double = (beats / 2).coerceAtLeast(MIN_BEATS)

    /** Twice [beats], or [beats] itself at the largest size. */
    fun doubled(beats: Double): Double = (beats * 2).coerceAtMost(MAX_BEATS)
}
