package io.sonora.player.ui

/** What the app shell shows: the Now Playing sheet, the chrome (mini player + tab bar), or both while the sheet moves. */
internal data class ShellVisibility(val nowPlaying: Boolean, val chrome: Boolean)

/**
 * Now Playing is shown only while a track exists, so a restored or stale request can never hide the chrome without the
 * sheet replacing it. On top-level routes the chrome stays under a sheet that is moving (dragged, opening or closing),
 * so a drag started on the mini player keeps its touch target; only a sheet resting fully open ([sheetCovers]) hides it.
 */
internal fun shellVisibility(sheetShown: Boolean, sheetCovers: Boolean, hasTrack: Boolean, atTopLevel: Boolean): ShellVisibility {
    val nowPlaying = sheetShown && hasTrack
    return ShellVisibility(nowPlaying = nowPlaying, chrome = atTopLevel && !(nowPlaying && sheetCovers))
}

/**
 * Whether the tab screens (the library, search, converter and export, and the translucency they are sampled for) are drawn: not
 * while Now Playing rests fully open ([sheetCovers]) and fully opaque ([nowPlayingOpaque]: not while it is still
 * cross-fading in with "Remove animations"), when nothing of them can show. The screens stay composed and are drawn again
 * from the first frame of a drag; only what the GPU paints every frame is saved, a screen's worth of images, text and
 * translucency under every redraw of Now Playing (its drift, its progress bar).
 */
internal fun tabContentDrawn(nowPlayingShown: Boolean, sheetCovers: Boolean, nowPlayingOpaque: Boolean): Boolean =
    !(nowPlayingShown && sheetCovers && nowPlayingOpaque)
