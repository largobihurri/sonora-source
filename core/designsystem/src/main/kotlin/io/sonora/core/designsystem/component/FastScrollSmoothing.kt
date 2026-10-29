package io.sonora.core.designsystem.component

import kotlin.math.abs
import kotlin.math.exp

/**
 * A critically damped alpha-beta (g-h) tracker: it estimates a signal's [position] and [velocity] (units per second)
 * from measurements, `position' = velocity + 2 w (z - position)` and `velocity' = w^2 (z - position)` with `w = 1 /
 * time constant`. Because it carries the velocity, it has no lag at all on a steady motion (a one-pole filter trails by
 * speed x time constant), and between measurements it keeps predicting at the learnt speed. Each update integrates
 * that system exactly over the elapsed time, holding the measurement, so two half steps equal one full step and the
 * frame rate changes nothing. Like any tracker with a velocity it overshoots a sudden stop (by about speed x constant /
 * e); [FingerSmoother] never lets the list pass the finger, so only its speed estimate is used to the full. Pure, so
 * it is unit-tested.
 */
class AlphaBetaTracker(private val timeConstantNanos: Long) {
    var position = 0f
        private set
    var velocity = 0f
        private set

    /** Starts at [value], at rest. */
    fun reset(value: Float) {
        position = value
        velocity = 0f
    }

    /** Moves the estimate on by [elapsedNanos] with the signal measured at [measurement]; no elapsed time changes nothing. */
    fun update(measurement: Float, elapsedNanos: Long) {
        if (elapsedNanos <= 0L) return
        val w = 1e9 / timeConstantNanos
        val t = elapsedNanos / 1e9
        val decay = exp(-w * t)
        val error = (measurement - position).toDouble()
        val speed = velocity.toDouble()
        // The error (measurement - position) and the velocity after t: the system matrix has the double eigenvalue -w.
        val nextError = decay * ((1 - w * t) * error - t * speed)
        velocity = (decay * (w * w * t * error + (1 + w * t) * speed)).toFloat()
        position = (measurement - nextError).toFloat()
    }
}

/**
 * The dead band in front of the tracker, with take-up. A finger that rests, or jitters by less than [bandPx] either way,
 * moves nothing: the held position follows the finger only once it is more than the band away, and then a band
 * behind it, continuously. A plain band would stay a band behind for the whole drag (at 33 px/s a 7 px band is 210 ms
 * of lag), so once the finger has pushed the held position [engagePx] in one direction the trailing band is taken up:
 * it shrinks by `e^(-takeUp)` per band of further push, and the held position catches up with the finger (at most
 * twice the finger's speed while it does, then exactly with it). Turning back restores the full band on both sides, so
 * jitter around a resting point, even after a drag, cannot pump the held position along. Until the finger first leaves
 * it, the band is [restBandPx] (wider: a finger just put down has not moved yet, and its jitter can exceed [bandPx]);
 * the take-up then starts from that width, so leaving it is continuous. A finger at or beyond an end ([lo], [hi]) is
 * at that end.
 */
private class TakeUpBand(
    private val bandPx: Float,
    private val restBandPx: Float,
    private val engagePx: Float,
    private val lo: Float,
    private val hi: Float,
) {
    var held = 0f
        private set
    private var trailing = restBandPx
    private var base = restBandPx
    private var direction = 0
    private var pushed = 0f

    fun reset(at: Float) {
        held = at
        trailing = restBandPx
        base = restBandPx
        direction = 0
        pushed = 0f
    }

    fun update(finger: Float): Float {
        when {
            finger <= lo -> held = lo
            finger >= hi -> held = hi
            finger - held > lagFor(1) -> push(1, finger)
            held - finger > lagFor(-1) -> push(-1, finger)
        }
        return held
    }

    private fun lagFor(towards: Int) = when (direction) {
        0 -> restBandPx
        towards -> trailing
        else -> bandPx
    }

    private fun push(towards: Int, finger: Float) {
        if (towards != direction) {
            base = lagFor(towards)
            direction = towards
            pushed = 0f
            trailing = base
        }
        pushed += abs(finger - held) - trailing
        trailing = base * exp(-TAKE_UP_PER_BAND * (pushed - engagePx).coerceAtLeast(0f) / bandPx)
        held = finger - towards * trailing
    }

    private companion object {
        /** How fast the band is taken up: by `1 - 1/e` per band of push past the engage distance. */
        const val TAKE_UP_PER_BAND = 1f
    }
}

/**
 * What the fast scroller's list follows while the thumb is held: the finger's position as a fraction of the track,
 * smoothed, in three stages. [TakeUpBand] (a dead band of [bandPx], [restBandPx] until the finger first moves, taken
 * up once it travels) keeps a resting or jittering finger from moving anything. An [AlphaBetaTracker] on the held position learns the finger's speed. The
 * output then advances every frame at that speed, or by the share `1 - e^(-elapsed / 25 ms)` of the way to the held
 * position when that is more, but never past it: the touch screen reports the finger in whole pixels (~30 px of list
 * each) and at 120 Hz while frames come at 60 or 120 Hz, so the held position moves in steps, and the speed spreads
 * them evenly over the frames in between. The list stays about 25 ms behind the finger at any speed, never overshoots
 * it, stops within a frame or two of the finger stopping and never moves back. [travelPx] is the track length the
 * fraction is measured in (the thumb's travel at the grab). Pure, so it is unit-tested.
 */
class FingerSmoother(private val travelPx: Float, bandPx: Float, restBandPx: Float = bandPx) {
    private val band = TakeUpBand(bandPx, maxOf(restBandPx, bandPx), bandPx * ENGAGE_BANDS, 0f, travelPx)
    private val tracker = AlphaBetaTracker(TRACKER_TIME_CONSTANT_NANOS)
    private var output = 0f

    /** Starts at [fraction] (where the list is when the thumb is grabbed), so the grab itself moves nothing. */
    fun reset(fraction: Float) {
        val px = fraction * travelPx
        band.reset(px)
        tracker.reset(px)
        output = px
    }

    /** The smoothed fraction after [elapsedNanos] more with the finger at [fraction]. */
    fun update(fraction: Float, elapsedNanos: Long): Float {
        if (travelPx <= 0f) return fraction
        val held = band.update(fraction * travelPx)
        if (elapsedNanos > 0L) {
            tracker.update(held, elapsedNanos)
            output = follow(output, held, tracker.velocity, elapsedNanos)
        }
        // On the finger itself (rest after letting go, or an end) the result is its fraction exactly.
        return if (output == fraction * travelPx) fraction else (output / travelPx).coerceIn(0f, 1f)
    }

    /**
     * Lets go: from now on the list aims at the finger's [fraction] itself, without the band, and keeps moving at the
     * finger's speed until it lands on it (a frame or two), so it neither stops short nor leaps. Returns the smoothed
     * fraction now.
     */
    fun settle(fraction: Float): Float {
        band.reset(fraction * travelPx)
        return (output / travelPx).coerceIn(0f, 1f)
    }

    private companion object {
        /**
         * The tracker's time constant. Chosen with a frame-by-frame simulation of the chain (touches every 1/120 s in
         * whole pixels, frames at 60 and 120 Hz, 33 to 1,200 px/s, 30 px of list per finger pixel): 40 to 60 ms give
         * the same lag; shorter makes the speed follow each pixel step (uneven frames at 33 px/s), longer makes it
         * slow to learn a new speed.
         */
        const val TRACKER_TIME_CONSTANT_NANOS = 50_000_000L

        /**
         * How fast the output closes the gap to the held position when the speed alone would not: 25 ms, which is
         * also the lag the list keeps behind the finger (median 15-20 ms in the simulation at 33, 120 and 400 px/s,
         * steps within 1.6:1 of each other at 33 px/s). Shorter copies the pixel steps, longer trails further.
         */
        const val FOLLOW_TIME_CONSTANT_NANOS = 25_000_000L

        /** The band is taken up once the finger has pushed it a third of a band (a jitter's creep stays a creep). */
        const val ENGAGE_BANDS = 1f / 3f

        /** Within this much of a pixel of the target the output lands on it (~0.03 px of list). */
        const val SNAP_PX = 1e-3f

        /**
         * One frame of the output towards [target]: at [velocity] when it moves that way, or the exponential share of
         * the gap when that is more, landing on [target] instead of passing it.
         */
        fun follow(current: Float, target: Float, velocity: Float, elapsedNanos: Long): Float {
            val gap = target - current
            if (abs(gap) <= SNAP_PX) return target
            val catchUp = gap * (1.0 - exp(-elapsedNanos.toDouble() / FOLLOW_TIME_CONSTANT_NANOS)).toFloat()
            val coast = velocity * elapsedNanos / 1e9f
            val step = if (gap > 0f) maxOf(catchUp, coast) else minOf(catchUp, coast)
            return if (abs(step) >= abs(gap) - SNAP_PX) target else current + step
        }
    }
}
