package io.sonora.player.ui

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/*
 * Motion math for the tab strip: the four tab screens sit side by side on one horizontal strip whose position is a
 * fractional tab index (0 = first tab). The tab bar drives that one float, the screens and the selection pill both
 * derive from it. Tab [bounds] are each cell's horizontal extent in the bar, in tab order, as laid out (so in a
 * right-to-left bar the first tab has the largest x). Everything here is allocation-free: it runs every frame.
 */

/** How far ahead (seconds of the release speed) a released scrub looks to pick its tab. */
private const val SETTLE_PROJECTION_SECONDS = 0.1f

private fun centre(bounds: List<ClosedFloatingPointRange<Float>>, index: Int): Float =
    (bounds[index].start + bounds[index].endInclusive) / 2f

/**
 * The strip position for a finger at [x] on the tab bar: a tab's centre maps to that tab and the strip moves linearly
 * between neighbouring centres, so the screens follow the finger. Past the outer centres it stops at the first or last
 * tab (no overshoot). 0 with fewer than two measured tabs.
 */
fun stripPositionAt(x: Float, bounds: List<ClosedFloatingPointRange<Float>>): Float {
    val count = bounds.size
    if (count < 2) return 0f
    // Flip right-to-left bars so the centres always grow with the tab index.
    val direction = if (centre(bounds, count - 1) >= centre(bounds, 0)) 1f else -1f
    val t = x * direction
    if (t <= centre(bounds, 0) * direction) return 0f
    for (index in 0 until count - 1) {
        val from = centre(bounds, index) * direction
        val to = centre(bounds, index + 1) * direction
        if (t <= to) return index + if (to > from) (t - from) / (to - from) else 0f
    }
    return (count - 1).toFloat()
}

/** A finger speed along the bar ([velocityX], px/s) as strip speed in tabs per second (sign-aware for RTL bars). */
fun stripVelocity(velocityX: Float, bounds: List<ClosedFloatingPointRange<Float>>): Float {
    val count = bounds.size
    if (count < 2) return 0f
    val pitch = (centre(bounds, count - 1) - centre(bounds, 0)) / (count - 1)
    return if (pitch == 0f) 0f else velocityX / pitch
}

/**
 * The tab a released scrub settles on: the nearest one, nudged by the release [velocity] (tabs/s) towards the tab the
 * strip is heading to, but never further than the tab next to the strip's position and never past the ends.
 */
fun stripSettleTarget(position: Float, velocity: Float, count: Int): Int {
    if (count <= 0) return 0
    val p = position.coerceIn(0f, (count - 1).toFloat())
    val projected = p + velocity * SETTLE_PROJECTION_SECONDS
    return floor(projected + 0.5f).toInt().coerceIn(floor(p).toInt(), ceil(p).toInt())
}

/**
 * Which tabs to compose, as a bit mask (bit i = tab i), while the strip sits at [position] heading to tab [heading]:
 * the one or two tabs in the viewport, plus, while moving, the next tab ahead (never beyond [heading]) so it is ready
 * before it slides in. At rest (heading = position) that is just the shown tab.
 */
fun stripPageMask(position: Float, heading: Int, count: Int): Int {
    if (count <= 0) return 0
    val last = count - 1
    val p = position.coerceIn(0f, last.toFloat())
    val towards = heading.coerceIn(0, last)
    val low = floor(p).toInt()
    val high = ceil(p).toInt()
    var mask = (1 shl low) or (1 shl high)
    if (towards > p && high + 1 <= towards) mask = mask or (1 shl (high + 1))
    if (towards < p && low - 1 >= towards) mask = mask or (1 shl (low - 1))
    return mask
}

/** The selection pill's start edge with the strip at [position]: it slides between neighbouring cells. */
fun stripPillStart(position: Float, bounds: List<ClosedFloatingPointRange<Float>>): Float {
    if (bounds.isEmpty()) return 0f
    val p = position.coerceIn(0f, (bounds.size - 1).toFloat())
    val low = floor(p).toInt()
    val high = ceil(p).toInt()
    return lerp(bounds[low].start, bounds[high].start, p - low)
}

/** The selection pill's end edge with the strip at [position]. */
fun stripPillEnd(position: Float, bounds: List<ClosedFloatingPointRange<Float>>): Float {
    if (bounds.isEmpty()) return 0f
    val p = position.coerceIn(0f, (bounds.size - 1).toFloat())
    val low = floor(p).toInt()
    val high = ceil(p).toInt()
    return lerp(bounds[low].endInclusive, bounds[high].endInclusive, p - low)
}

private fun lerp(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

/**
 * The start speed (tabs/s) for the no-bounce spring that settles a released strip from [position] on [target]: the
 * release [velocity], capped so the spring never carries the strip past the tab. A critically damped spring with
 * stiffness k (unit mass) overshoots only when its speed towards the target exceeds sqrt(k) times the distance.
 */
fun stripReleaseVelocity(position: Float, target: Int, velocity: Float, stiffness: Float): Float {
    val distance = target - position
    if (distance == 0f) return 0f
    if (velocity * distance <= 0f) return velocity // moving away: the spring turns it around without overshoot
    val cap = sqrt(stiffness) * abs(distance)
    return velocity.coerceIn(-cap, cap)
}
