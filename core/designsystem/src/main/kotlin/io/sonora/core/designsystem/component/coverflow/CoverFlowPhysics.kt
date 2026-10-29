package io.sonora.core.designsystem.component.coverflow

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.roundToInt

/** Drag and fling rules of the Cover Flow strip, in cover units (position 3.5 is halfway between covers 3 and 4). */
object CoverFlowPhysics {

    /** The cover a fling settles on: the one nearest to where it would coast ([projected]), within the list. */
    fun snapIndex(projected: Float, count: Int): Int = projected.roundToInt().coerceIn(0, (count - 1).coerceAtLeast(0))

    /** The strip's position after the finger moved [dragPx] since a drag began at [start]; it follows the finger. */
    fun dragPosition(start: Float, dragPx: Float, pxPerItem: Float, count: Int): Float =
        (start - dragPx / pxPerItem).coerceIn(0f, (count - 1).coerceAtLeast(0).toFloat())

    /** A finger's horizontal velocity as the strip's velocity in covers per second (a rightward fling goes back). */
    fun itemVelocity(pxPerSecond: Float, pxPerItem: Float): Float = -pxPerSecond / pxPerItem

    /**
     * Whether a fling glides to [target] ([glide]): only when the target lies ahead in the fling's direction and more
     * than half a cover away; otherwise a spring alone settles it.
     */
    fun coastsFirst(position: Float, target: Int, velocity: Float): Boolean {
        val ahead = target - position
        return ahead * velocity > 0f && abs(ahead) > 0.5f
    }

    /**
     * The strip's top speed, in covers per second: half a cover per frame at 120 Hz, one at 60 Hz. Faster, each frame
     * shows a new set of covers and the eye reads a smear of colours instead of motion (temporal aliasing).
     */
    const val MAX_SPEED = 60f

    /**
     * The steepest a gentle [glide] (start slope below [GLIDE_START_SLOPE]) ever gets, as a multiple of its mean speed
     * (distance over duration); at least that long a glide never outruns its mean speed times this.
     */
    const val GENTLE_PEAK_SLOPE = 1.5f

    /** A fling's [velocity] as the strip leaves the finger: unchanged up to [MAX_SPEED], capped there above it. */
    fun cappedVelocity(velocity: Float): Float = velocity.coerceIn(-MAX_SPEED, MAX_SPEED)

    /**
     * The strip's position [elapsedMs] after [current] while the finger has dragged it to [target]: right there when
     * the finger is slow, otherwise as far toward it as [MAX_SPEED] allows, catching up once the finger rests.
     */
    fun chase(current: Float, target: Float, elapsedMs: Float): Float {
        val reach = MAX_SPEED * elapsedMs.coerceAtLeast(0f) / 1_000f
        return current + (target - current).coerceIn(-reach, reach)
    }

    /**
     * How long a fling's [glide] over [distance] covers takes when it leaves at [velocity], capped ([cappedVelocity]):
     * [glideDurationMs] at that speed, lengthened when needed so no part of the glide outruns [MAX_SPEED]. The
     * distance comes from the uncapped fling, so a strong fling still travels as far, for longer.
     */
    fun flingGlideMs(distance: Float, velocity: Float): Int {
        val glide = glideDurationMs(distance, cappedVelocity(velocity))
        val unhurried = ceil(GENTLE_PEAK_SLOPE * abs(distance) / MAX_SPEED * 1_000f).toInt()
        return maxOf(glide, unhurried)
    }

    /** The glide's slope at its start: [glide] covers that much of its distance per unit of time at first. */
    const val GLIDE_START_SLOPE = 3f
    const val MIN_GLIDE_MS = 280
    const val MAX_GLIDE_MS = 2_400

    /**
     * A fling's whole deceleration as one curve over the fraction [f] of its duration: it leaves at the fling's speed
     * and arrives on its cover with no speed left, so it never stops between covers and then picks one (a ratchet,
     * not a coast plus a correction). Cubic ease-out.
     */
    fun glide(f: Float): Float = glide(f, GLIDE_START_SLOPE)

    /**
     * [glide] with the slope [startSlope] at its start, so a glide whose duration was clamped still leaves at the
     * fling's speed (see [glideStartSlope]); it always ends on its cover with no speed left and never moves back.
     * At [GLIDE_START_SLOPE] it is the cubic ease-out; steeper, an ease-out of that power; gentler, the cubic that
     * starts at that slope and ends flat.
     */
    fun glide(f: Float, startSlope: Float): Float {
        val t = f.coerceIn(0f, 1f)
        val s = startSlope.coerceAtLeast(0f)
        if (s >= GLIDE_START_SLOPE) return 1f - (1f - t).pow(s)
        return t * (s + t * ((3f - 2f * s) + t * (s - 2f)))
    }

    /**
     * The start slope that makes a [glide] of [durationMs] over [distance] covers leave at [velocity] covers per
     * second: [GLIDE_START_SLOPE] unless [glideDurationMs] clamped the duration.
     */
    fun glideStartSlope(distance: Float, velocity: Float, durationMs: Int): Float {
        if (distance == 0f) return GLIDE_START_SLOPE
        return abs(velocity) * durationMs / 1_000f / abs(distance)
    }

    /** How long [glide] takes to travel [distance] covers starting at [velocity] covers per second, within bounds. */
    fun glideDurationMs(distance: Float, velocity: Float): Int {
        if (velocity == 0f) return MAX_GLIDE_MS
        val ms = (GLIDE_START_SLOPE * abs(distance) / abs(velocity) * 1_000f).roundToLong()
        return ms.coerceIn(MIN_GLIDE_MS.toLong(), MAX_GLIDE_MS.toLong()).toInt()
    }
}
