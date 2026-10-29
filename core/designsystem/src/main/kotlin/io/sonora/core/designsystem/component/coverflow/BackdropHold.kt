package io.sonora.core.designsystem.component.coverflow

import androidx.compose.runtime.Immutable
import kotlin.math.abs

/**
 * Which artwork the Cover Flow background shows while the strip moves, so a fast fling never flickers it: [shown]
 * holds while the strip passes more than a cover per cross-fade ([HOLD_SPEED]). Once the strip is slower, the
 * [nearest] cover takes over after it has stayed nearest for [SETTLE_MS] (since [nearestSinceMs]), and never before
 * the previous cross-fade, begun at [shownSinceMs], is over. Pure, so it is unit-tested.
 */
@Immutable
data class BackdropHold<T>(val shown: T, val nearest: T, val nearestSinceMs: Long, val shownSinceMs: Long) {

    /**
     * The hold at [nowMs], with the strip moving at [speed] covers per second and [nearest] its nearest cover's
     * artwork; a cross-fade takes [fadeMs] ([fadeMs] of the companion).
     */
    fun next(nearest: T, speed: Float, nowMs: Long, fadeMs: Int = FADE_MS): BackdropHold<T> {
        val slow = abs(speed) <= HOLD_SPEED
        // The dwell only counts while the strip is slow and the same cover stays nearest.
        val since = if (slow && nearest == this.nearest) nearestSinceMs else nowMs
        val held = copy(nearest = nearest, nearestSinceMs = since)
        val due = held.settlesAtMs(fadeMs)
        return if (slow && due != null && nowMs >= due) held.copy(shown = nearest, shownSinceMs = nowMs) else held
    }

    /**
     * When [nearest] takes over if the strip stays slow, or `null` when it is already [shown]; a caller whose strip
     * stopped (no more frames) calls [next] again then.
     */
    fun settlesAtMs(fadeMs: Int = FADE_MS): Long? =
        if (nearest == shown) null else maxOf(nearestSinceMs + SETTLE_MS, shownSinceMs + fadeMs)

    companion object {
        /** A new background cross-fades in over this long. */
        const val FADE_MS = 300

        /** One cover per cross-fade, in covers per second: faster, each fade would be cut short by the next. */
        const val HOLD_SPEED = 1_000f / FADE_MS

        /** How long a cover must stay nearest to a slow strip before it becomes the background. */
        const val SETTLE_MS = 120L

        /** A hold that shows [artwork] from [nowMs]. */
        fun <T> start(artwork: T, nowMs: Long): BackdropHold<T> = BackdropHold(artwork, artwork, nowMs, nowMs)

        /**
         * How fast the strip moved from [from] to [to] in [elapsedMs], in covers per second; with no time between
         * them it cannot tell, and reports a speed that holds the background.
         */
        fun speed(from: Float, to: Float, elapsedMs: Long): Float =
            if (elapsedMs <= 0L) Float.POSITIVE_INFINITY else abs(to - from) * 1_000f / elapsedMs

        /** The cross-fade's length: [FADE_MS], or none under reduced motion ("Remove animations"). */
        fun fadeMs(reducedMotion: Boolean): Int = if (reducedMotion) 0 else FADE_MS
    }
}
