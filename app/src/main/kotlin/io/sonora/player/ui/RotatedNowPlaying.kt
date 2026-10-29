package io.sonora.player.ui

import io.sonora.core.domain.model.NowPlayingRotation

/**
 * Whether the shell covers an open Now Playing with the albums Cover Flow: only with the [NowPlayingRotation.ALBUM_COVER_FLOW]
 * choice, while Now Playing is settled open ([nowPlayingOpen]) and the screen is [landscape], and not after the Cover
 * Flow's own Now Playing button asked for the landscape Now Playing ([dismissed], until the phone is upright again or
 * Now Playing closes). Never in [gravity] mode: its level Now Playing is never covered (Settings says the rotation choice
 * does not apply there; a sideways dismissal follows the landscape flow instead, T63.8).
 */
internal fun showsAlbumCoverFlow(
    rotation: NowPlayingRotation,
    landscape: Boolean,
    nowPlayingOpen: Boolean,
    dismissed: Boolean,
    gravity: Boolean = false,
): Boolean = !gravity && rotation == NowPlayingRotation.ALBUM_COVER_FLOW && landscape && nowPlayingOpen && !dismissed
