package io.sonora.core.designsystem.component.coverflow

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.util.lerp
import io.sonora.core.designsystem.translucent.BubbleFrames
import io.sonora.core.designsystem.translucent.TranslucentMorph
import kotlin.math.min

/**
 * A square artwork on screen: centred at [centre], [side] px wide, turned [rotationY] degrees about its vertical axis
 * (as a Cover Flow cover is), with corners of [radius] px.
 */
@Immutable
data class ArtworkPose(val centre: Offset, val side: Float, val rotationY: Float = 0f, val radius: Float = 0f) {
    companion object {
        /** A flat artwork laid out in [rect]: the largest square centred in it. */
        fun flat(rect: Rect, radius: Float = 0f): ArtworkPose =
            ArtworkPose(rect.center, min(rect.width, rect.height), 0f, radius)

        /**
         * A Cover Flow cover laid out in its [slot] and drawn through [transform] (moved, then scaled and turned about
         * its centre, as [CoverFlow] draws it).
         */
        fun cover(slot: Rect, transform: CoverTransform, radius: Float = 0f): ArtworkPose = ArtworkPose(
            centre = slot.center + Offset(transform.translationX, transform.translationY),
            side = slot.width * transform.scale,
            rotationY = transform.rotationY,
            radius = radius,
        )
    }
}

/**
 * One artwork shared between the Cover Flow and another screen (T67 Now Playing, T66 a page): it flies from where it
 * is in one to where it is in the other, live, as their transition's progress goes 0 to 1. Pure, so it is unit-tested.
 */
object ArtworkFlight {
    /**
     * The artwork [progress] of the way from [from] to [to]: straight, growing (or shrinking) as it goes, its corners
     * easing between the two; a turned cover flattens out of its 3D pose in the first half of the way (and turns back
     * into it in that half on the way back).
     */
    fun pose(progress: Float, from: ArtworkPose, to: ArtworkPose): ArtworkPose {
        val t = progress.coerceIn(0f, 1f)
        if (t == 0f) return from
        if (t == 1f) return to
        val flatten = (t / FLATTENED_AT).coerceAtMost(1f)
        val turn = 1f - (1f - flatten) * (1f - flatten)
        return ArtworkPose(
            centre = lerp(from.centre, to.centre, t),
            side = lerp(from.side, to.side, t),
            rotationY = lerp(from.rotationY, to.rotationY, turn),
            radius = lerp(from.radius, to.radius, t),
        )
    }

    /**
     * The artwork carried inside a translucent bubble (T67.4), so the bubble and the artwork move as one: it is worked out in
     * the bubble's own frame from the bubble's size alone ([TranslucentMorph.openness]), so an opening shows it exactly as a
     * dismissal does, played backwards (T67.7), whichever path the bubble takes. Closed, it is [thumbnail] where it sits
     * in the capsule; open, [slot] (Now Playing's artwork). In between its share of the card's height goes from the
     * thumbnail's share of the capsule's to Now Playing's share of the screen's, its gap to the thumbnail's end of the
     * card (the left, or the right right to left) from the thumbnail's to Now Playing's (as a share of the width), and its height in the card from the thumbnail's (vertically centred)
     * to Now Playing's. It keeps a gap from the card's edges going from the thumbnail's nearest gap in the capsule to
     * Now Playing's artwork's nearest gap on the screen (T67.6, T67.8, T67.9), so both ends are exact. Not from Now
     * Playing drawn to cover the card: on a low, wide card that overflows it. All in the coordinates of [frames].
     */
    fun carried(frames: BubbleFrames, thumbnail: ArtworkPose, slot: ArtworkPose): ArtworkPose {
        val open = TranslucentMorph.openness(frames.bubble, frames.capsule, frames.screen)
        val box = frames.bubble.rect
        val capsule = frames.capsule.rect
        val screen = frames.screen.rect
        // The thumbnail sits at one end of the capsule: the left one, or the right one right to left (S3). The artwork
        // is placed from that end throughout.
        val thumbLeft = thumbnail.centre.x - thumbnail.side / 2f - capsule.left
        val thumbRight = capsule.right - thumbnail.centre.x - thumbnail.side / 2f
        val fromRight = thumbRight < thumbLeft
        // The gap kept from the card's edges goes from the thumbnail's nearest gap in the capsule (its leading one may
        // be narrower than its top and bottom ones, T67.8) to Now Playing's artwork's nearest gap on the screen, if
        // narrower (T67.9), so the artwork is exactly where each is laid out at both ends.
        val thumbInset = minOf(
            min(thumbLeft, thumbRight),
            thumbnail.centre.y - thumbnail.side / 2f - capsule.top,
            capsule.bottom - thumbnail.centre.y - thumbnail.side / 2f,
        ).coerceAtLeast(0f)
        val slotInset = minOf(
            slot.centre.x - slot.side / 2f - screen.left,
            slot.centre.y - slot.side / 2f - screen.top,
            screen.right - slot.centre.x - slot.side / 2f,
            screen.bottom - slot.centre.y - slot.side / 2f,
        ).coerceAtLeast(0f)
        val inset = lerp(thumbInset, min(thumbInset, slotInset), open)
        val room = (box.minDimension - 2f * inset).coerceAtLeast(0f)
        val thumbShare = if (capsule.height > 0f) thumbnail.side / capsule.height else 0f
        val slotShare = if (screen.height > 0f) slot.side / screen.height else thumbShare
        val side = (lerp(thumbShare, slotShare, open) * box.height).coerceIn(0f, room)
        // The gap to that end: the thumbnail's in px, Now Playing's artwork's as a share of the width.
        val thumbLead = if (fromRight) thumbRight else thumbLeft
        val slotGap = if (fromRight) screen.right - slot.centre.x - slot.side / 2f else slot.centre.x - slot.side / 2f - screen.left
        val slotLead = if (screen.width > 0f) slotGap / screen.width * box.width else thumbLead
        val thumbV = if (capsule.height > 0f) (thumbnail.centre.y - capsule.top) / capsule.height else 0.5f
        val slotV = if (screen.height > 0f) (slot.centre.y - screen.top) / screen.height else thumbV
        val lead = lerp(thumbLead, slotLead, open) + side / 2f
        val x = if (fromRight) box.right - lead else box.left + lead
        val y = box.top + lerp(thumbV, slotV, open) * box.height
        return ArtworkPose(
            centre = Offset(within(x, box.left + inset, box.right - inset, side), within(y, box.top + inset, box.bottom - inset, side)),
            side = side,
            rotationY = lerp(thumbnail.rotationY, slot.rotationY, open),
            radius = lerp(thumbnail.radius, slot.radius, open).coerceIn(0f, side / 2f),
        )
    }

    /** [centre] moved, if need be, so a square of [side] around it stays between [from] and [to]. */
    private fun within(centre: Float, from: Float, to: Float, side: Float): Float {
        val low = from + side / 2f
        val high = to - side / 2f
        return if (low >= high) (from + to) / 2f else centre.coerceIn(low, high)
    }

    /**
     * Whether the Cover Flow's centre cover, drawn from [centreCovers], is the [playing] artwork itself (the playing
     * album's cover), so it is the artwork that flies; a mosaic (an artist, a playlist) never is.
     */
    fun centreIsShared(centreCovers: List<String>, playing: String?): Boolean =
        playing != null && centreCovers.singleOrNull() == playing

    /** How far through the flight a turned cover has fully flattened. */
    private const val FLATTENED_AT = 0.5f
}
