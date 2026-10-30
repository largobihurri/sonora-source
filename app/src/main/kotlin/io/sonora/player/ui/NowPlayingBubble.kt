package io.sonora.player.ui

/** Which capsule Now Playing grows out of as a translucent bubble, and shrinks back into (T67). */
internal enum class NowPlayingBubble {
    /** None: Now Playing slides up and down as the ordinary sheet. */
    None,

    /** The visual collection's "Now Playing" capsule, with the shared artwork carried inside the bubble (T67.1, T67.4). */
    VisualCollection,

    /** The mini player above the tab bar, its thumbnail carried inside the bubble (T67.2). */
    MiniPlayer,
}

/**
 * Where Now Playing grows from: the visual collection's capsule when it was [openedFromVisualCollection] and that visual collection is still
 * there ([visualCollectionMode]); otherwise, with no visual collection at all, the mini player, except in [gravity] mode, whose sheet
 * slides toward the floor instead; over a visual collection it did not come from, the ordinary sheet.
 */
internal fun nowPlayingBubble(openedFromVisualCollection: Boolean, visualCollectionMode: LibraryVisualCollectionMode, gravity: Boolean): NowPlayingBubble =
    when {
        visualCollectionMode != LibraryVisualCollectionMode.None -> if (openedFromVisualCollection) NowPlayingBubble.VisualCollection else NowPlayingBubble.None
        gravity -> NowPlayingBubble.None
        else -> NowPlayingBubble.MiniPlayer
    }

/**
 * Whether Now Playing takes touches: always, unless it [crossFades] ("Remove animations") on its way out (not
 * [headingOpen]); then it is already closed and only fading, so it must not catch a tap meant for what lies beneath.
 */
internal fun nowPlayingTakesTouch(crossFades: Boolean, headingOpen: Boolean): Boolean = !crossFades || headingOpen
