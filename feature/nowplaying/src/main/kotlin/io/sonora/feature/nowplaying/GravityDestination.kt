package io.sonora.feature.nowplaying

/**
 * Where dismissing Now Playing leads in gravity mode (T63.8), latched by the level's magnetic detents
 * ([GravityLevel.detent]): a sideways detent (either way) makes it [Sideways], the landscape flow's visual collection; the
 * upright detent, and upside down too, makes it [Upright], the portrait screen Now Playing was opened over. Tilting
 * between detents changes nothing. The shell turns the window to match (beneath the open Now Playing, so a drag reveals
 * the destination) and closes into it.
 */
enum class GravityDestination {
    Upright,
    Sideways,
    ;

    /** The destination once the level is held by [detent] (a quarter turn 0..3 of the phone's frame, null while free). */
    fun after(detent: Int?): GravityDestination = when (detent?.mod(4)) {
        null -> this
        1, 3 -> Sideways
        else -> Upright
    }

    companion object {
        /** Before any detent: where the window already is when Now Playing opens ([landscape]: over a visual collection). */
        fun opening(landscape: Boolean): GravityDestination = if (landscape) Sideways else Upright

        /**
         * The latch after [detent] caught the groups: from the [latched] destination, or [opening] before any, while Now
         * Playing heads [open]; unchanged otherwise (R3-detent-callback-ungated: a detent caught while the sheet slides
         * away must not leave a latch behind for the next opening, which starts from the window's side).
         */
        fun onDetent(latched: GravityDestination?, opening: GravityDestination, detent: Int, open: Boolean): GravityDestination? =
            if (open) (latched ?: opening).after(detent) else latched
    }
}
