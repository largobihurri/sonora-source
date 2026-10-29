package io.sonora.core.audiodsp.dub

/** The beat divisions the echo locks to, as a fraction of a whole note (four beats). */
enum class EchoDivision(val fraction: Double) {
    SIXTEENTH(1.0 / 16),
    EIGHTH(1.0 / 8),
    DOTTED_EIGHTH(3.0 / 16),
    QUARTER(1.0 / 4),
    DOTTED_QUARTER(3.0 / 8),
    HALF(1.0 / 2),
}

/**
 * Beat-synced echo time, in source frames: `sourceRate * 60 / bpm * division * 4`. It is computed in source time, so
 * the echo stays on the grid when a playback speed is applied later in the chain.
 *
 * A time longer than [MAX_ECHO_SECONDS] falls back to the next shorter division, as many times as needed; when even
 * the shortest division is too long (below 7.5 BPM), the time is clamped to [MAX_ECHO_SECONDS].
 */
object EchoTiming {
    const val MAX_ECHO_SECONDS = 2.0

    /** The division actually used for [division] at [bpm]. */
    fun effectiveDivision(sourceRate: Int, bpm: Double, division: EchoDivision): EchoDivision {
        val max = MAX_ECHO_SECONDS * sourceRate
        var ordinal = division.ordinal
        while (ordinal > 0 && rawFrames(sourceRate, bpm, DIVISIONS[ordinal]) > max) ordinal--
        return DIVISIONS[ordinal]
    }

    /** The echo time in frames for [division] at [bpm], after the fallback and the clamp. */
    fun delayFrames(sourceRate: Int, bpm: Double, division: EchoDivision): Double {
        val frames = rawFrames(sourceRate, bpm, effectiveDivision(sourceRate, bpm, division))
        return minOf(frames, MAX_ECHO_SECONDS * sourceRate)
    }

    private fun rawFrames(sourceRate: Int, bpm: Double, division: EchoDivision): Double =
        sourceRate * 240.0 * division.fraction / bpm

    private val DIVISIONS = EchoDivision.entries.toTypedArray()
}

/**
 * Linear ramp towards a target over a given number of frames; the value lands exactly on the target on the last
 * frame. Used for gates and for the feedback moves of FREEZE and RUNAWAY, whose durations are specified as times.
 * Real-time safe; not thread-safe.
 */
class LinearRamp(initial: Double = 0.0) {
    var value: Double = initial
        private set

    var target: Double = initial
        private set

    private var step = 0.0
    private var remaining = 0

    val isSettled: Boolean get() = remaining == 0

    /** Heads for [target], arriving after [frames] calls to [next]; a non-positive count jumps at once. */
    fun setTarget(target: Double, frames: Int) {
        this.target = target
        if (frames <= 0 || value == target) {
            value = target
            remaining = 0
        } else {
            step = (target - value) / frames
            remaining = frames
        }
    }

    fun snap() {
        value = target
        remaining = 0
    }

    fun next(): Double {
        if (remaining > 0) {
            remaining--
            value = if (remaining == 0) target else value + step
        }
        return value
    }
}
