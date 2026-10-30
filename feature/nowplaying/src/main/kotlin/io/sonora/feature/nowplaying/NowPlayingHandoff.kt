package io.sonora.feature.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.visualcollection.ArtworkPose
import io.sonora.core.designsystem.component.visualcollection.VisualCollectionHandoff

/** The corners of Now Playing's artwork. */
internal val ArtworkCorner = 12.dp

/**
 * Now Playing's side of a [VisualCollectionHandoff] (T67): its artwork is the flight's target, and hides while the shared
 * artwork flies (the visual collection's cover becoming it, or turning back into the cover). [origin] is where Now Playing is
 * laid out in the root (outside the bubble's layer); the artwork reports its pause shrink as [scale], so the flight
 * lands exactly on it.
 */
@Stable
internal class ArtworkShare(val handoff: VisualCollectionHandoff) {
    var origin = Offset.Zero
    var scale = 1f

    /** The artwork flies elsewhere: Now Playing's own is not drawn. */
    fun hidden(): Boolean = handoff.flying()
}

/** The artwork share of the Now Playing around; none outside a hand-off. */
internal val LocalArtworkShare = staticCompositionLocalOf<ArtworkShare?> { null }

@Composable
internal fun rememberArtworkShare(handoff: VisualCollectionHandoff?): ArtworkShare? =
    if (handoff == null) null else remember(handoff) { ArtworkShare(handoff) }

/**
 * Gives [share]'s hand-off the shown arrangement's artwork block ([stage]) as the flight's target while
 * [showsArtwork] (not the lyrics, nor gravity mode, whose artwork hangs level), until it leaves.
 */
@Composable
internal fun ArtworkShareTarget(share: ArtworkShare, stage: TurnStage, showsArtwork: () -> Boolean) {
    val radius = with(LocalDensity.current) { ArtworkCorner.toPx() }
    val shows by rememberUpdatedState(showsArtwork)
    DisposableEffect(share, stage, radius) {
        share.handoff.target = {
            stage.shownPlace(NowPlayingBlock.Artwork)?.takeIf { shows() }?.let { place ->
                sharedArtworkPose(place, share.origin, share.scale, radius)
            }
        }
        onDispose { share.handoff.target = { null } }
    }
}

/**
 * Now Playing's artwork laid out at [place] (in the stage's coordinates) with corners of [radius], seen in the root
 * with the stage at [origin]: shrunk by [scale] about its centre (paused), its corners with it.
 */
internal fun sharedArtworkPose(place: Rect, origin: Offset, scale: Float, radius: Float): ArtworkPose {
    val flat = ArtworkPose.flat(place.translate(origin), radius)
    return flat.copy(side = flat.side * scale, radius = radius * scale)
}
