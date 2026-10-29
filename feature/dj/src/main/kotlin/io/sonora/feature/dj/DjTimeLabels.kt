package io.sonora.feature.dj

import io.sonora.core.designsystem.text.LocaleFormats
import kotlin.math.floor

/**
 * The time labels under the DJ overview: [elapsed] ("1:23") and [remaining] ("-1:57"), both in whole seconds of the
 * same shown length, so they always add up to it (the elapsed seconds are floored, the length too).
 */
data class DjTimeLabels(val elapsed: String, val remaining: String) {
    companion object {

        /** The labels at [positionMs] in a track of [durationMs] (0 or less: unknown, nothing remains). */
        fun of(positionMs: Double, durationMs: Long): DjTimeLabels = ofSecond(secondOf(positionMs, durationMs), durationMs)

        /** The labels at the whole [second] (as [secondOf] gives it) of a track of [durationMs]. */
        fun ofSecond(second: Long, durationMs: Long): DjTimeLabels {
            val total = if (durationMs > 0) durationMs / MS_PER_SECOND else second
            val remaining = (total - second).coerceAtLeast(0)
            return DjTimeLabels(
                LocaleFormats.duration(second * MS_PER_SECOND),
                "-" + LocaleFormats.duration(remaining * MS_PER_SECOND),
            )
        }

        /**
         * The whole second shown for [positionMs]: floored, never negative (a NaN reads as the start) and never past the
         * end of a track of a known [durationMs]. Labels only change when this does, so a reader can follow a per-frame
         * position and still recompose once a second.
         */
        fun secondOf(positionMs: Double, durationMs: Long): Long {
            if (positionMs.isNaN() || positionMs <= 0.0) return 0
            val second = floor(positionMs / MS_PER_SECOND).toLong()
            return if (durationMs > 0) second.coerceAtMost(durationMs / MS_PER_SECOND) else second
        }

        private const val MS_PER_SECOND = 1_000L
    }
}
