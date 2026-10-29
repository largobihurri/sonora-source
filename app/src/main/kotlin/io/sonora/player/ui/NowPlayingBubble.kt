package io.sonora.player.ui

/** Which capsule Now Playing grows out of as a translucent bubble, and shrinks back into (T67). */
internal enum class NowPlayingBubble {
    /** None: Now Playing slides up and down as the ordinary sheet. */
    None,

    /** The Cover Flow's "Now Playing" capsule, with the shared artwork carried inside the bubble (T67.1, T67.4). */
    CoverFlow,

    /** The mini player above the tab bar, its thumbnail carried inside the bubble (T67.2). */
    MiniPlayer,
}

/**
 * Where Now Playing grows from: the Cover Flow's capsule when it was [openedFromCoverFlow] and that Cover Flow is still
 * there ([coverFlowMode]); otherwise, with no Cover Flow at all, the mini player, except in [gravity] mode, whose sheet
 * slides toward the floor instead; over a Cover Flow it did not come from, the ordinary sheet.
 */
internal fun nowPlayingBubble(openedFromCoverFlow: Boolean, coverFlowMode: LibraryCoverFlowMode, gravity: Boolean): NowPlayingBubble =
    when {
        coverFlowMode != LibraryCoverFlowMode.None -> if (openedFromCoverFlow) NowPlayingBubble.CoverFlow else NowPlayingBubble.None
        gravity -> NowPlayingBubble.None
        else -> NowPlayingBubble.MiniPlayer
    }

/**
 * Whether Now Playing takes touches: always, unless it [crossFades] ("Remove animations") on its way out (not
 * [headingOpen]); then it is already closed and only fading, so it must not catch a tap meant for what lies beneath.
 */
internal fun nowPlayingTakesTouch(crossFades: Boolean, headingOpen: Boolean): Boolean = !crossFades || headingOpen
