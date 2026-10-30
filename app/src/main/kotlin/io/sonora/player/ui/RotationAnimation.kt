package io.sonora.player.ui

import android.view.WindowManager.LayoutParams
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import io.sonora.core.domain.model.NowPlayingRotation

/**
 * Whether turning the phone turns Now Playing's artwork and controls as two blocks (see NowPlayingScreen's turnsBlocks):
 * while it is open ([nowPlayingOpen], where the sheet is headed) and a rotation shows its other layout, not the albums
 * Visual collection of the [NowPlayingRotation.ALBUM_VISUAL_COLLECTION] choice (unless that visual collection's button asked for the landscape
 * Now Playing, [albumVisualCollectionDismissed]). Never in gravity mode ([gravity]): the window stays portrait and the groups
 * hang level themselves.
 */
internal fun nowPlayingTurnsBlocks(
    nowPlayingOpen: Boolean,
    rotation: NowPlayingRotation,
    albumVisualCollectionDismissed: Boolean,
    gravity: Boolean = false,
): Boolean = nowPlayingOpen && !gravity && (rotation == NowPlayingRotation.NOW_PLAYING_LANDSCAPE || albumVisualCollectionDismissed)

/**
 * Whether the window turns seamlessly: while the blocks turn themselves ([nowPlayingTurnsBlocks]), and while gravity
 * mode's Now Playing is open ([gravity]), whose frame turns against the window so nothing moves on the
 * display.
 */
internal fun nowPlayingRotatesSeamlessly(
    nowPlayingOpen: Boolean,
    rotation: NowPlayingRotation,
    albumVisualCollectionDismissed: Boolean,
    gravity: Boolean,
): Boolean = nowPlayingTurnsBlocks(nowPlayingOpen, rotation, albumVisualCollectionDismissed, gravity) || (nowPlayingOpen && gravity)

/**
 * The window's rotation animation: seamless while the blocks turn themselves (the system shows the new layout straight
 * away instead of rotating a snapshot; it falls back when a seamless turn is not possible), the system's own rotation
 * everywhere else (Visual collection, the upright hold).
 */
internal fun windowRotationAnimation(blocksTurn: Boolean): Int =
    if (blocksTurn) LayoutParams.ROTATION_ANIMATION_SEAMLESS else LayoutParams.ROTATION_ANIMATION_ROTATE

/** Gives the activity's window [animation] for its next rotations. The system reads it when a rotation starts. */
@Composable
internal fun WindowRotationAnimation(animation: Int) {
    val window = LocalActivity.current?.window ?: return
    SideEffect {
        if (window.attributes.rotationAnimation != animation) {
            window.attributes = window.attributes.also { it.rotationAnimation = animation }
        }
    }
}
