package io.sonora.core.designsystem.translucent

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.util.lerp
import kotlin.math.max

/** Where a morphing translucent surface is: [rect] with corners of [radius] px. */
@Immutable
data class MorphFrame(val rect: Rect, val radius: Float) {
    companion object {
        /** A capsule filling [rect]: its corners as round as its height allows. */
        fun capsule(rect: Rect): MorphFrame = MorphFrame(rect, rect.height / 2f)
    }
}

/** Which path a translucent bubble takes between its capsule (progress 0) and the full screen (progress 1). */
enum class MorphRoute {
    /** Straight from the capsule to the screen, growing as it goes: how the bubble opens. */
    Straight,

    /** Pushed down and shrinking, then round to the right and up into the capsule: how the bubble is dismissed. */
    Arc,
    ;

    companion object {
        /**
         * The route at [progress], coming from [current]: a bubble resting open leaves along the [Arc], one resting
         * closed opens [Straight]. On the way it keeps its route, so a gesture reversing it midway never jumps (both
         * routes meet only at their ends).
         */
        fun follow(current: MorphRoute, progress: Float): MorphRoute = when {
            progress >= 1f -> Arc
            progress <= 0f -> Straight
            else -> current
        }
    }
}

/**
 * How a full-screen layer is drawn into a smaller [MorphFrame]: scaled by [scale] about its centre and moved by
 * [translation], then clipped to [clip] (in the layer's own coordinates) with corners of [radius].
 */
@Immutable
data class MorphLayer(val scale: Float, val translation: Offset, val clip: Rect, val radius: Float)

/**
 * The geometry and timing of a translucent bubble that grows out of a small capsule (progress 0) into a full-screen
 * surface (progress 1) and back: the Cover Flow's "Now Playing" capsule opening Now Playing (T67), shared with the turn
 * into Cover Flow (T66). Progress is the sheet's own, so a finger dragging it moves the bubble one to one. Pure, so it
 * is unit-tested.
 */
object TranslucentMorph {
    /** How far, as a share of the screen's height, a dismissed bubble is pushed down before it curves away. */
    const val ARC_PUSH_FRACTION = 0.3f

    /** The bubble at [progress] along [route], between [capsule] and [screen]; [push] as for [arc]. */
    fun frame(route: MorphRoute, progress: Float, capsule: MorphFrame, screen: MorphFrame, push: Float): MorphFrame =
        when (route) {
            MorphRoute.Straight -> straight(progress, capsule, screen)
            MorphRoute.Arc -> arc(progress, capsule, screen, push)
        }

    /** Opening: from [capsule] straight to [screen], place, size and corners together. */
    fun straight(progress: Float, capsule: MorphFrame, screen: MorphFrame): MorphFrame {
        val t = progress.coerceIn(0f, 1f)
        return rounded(lerpRect(capsule.rect, screen.rect, t), lerp(capsule.radius, screen.radius, t))
    }

    /**
     * Dismissal, as if pushed down: from [screen] (progress 1) the bubble moves down by up to about [push] px while it
     * shrinks with the progress, then curves round to the right and up into [capsule] (progress 0) along [arcCentre].
     */
    fun arc(progress: Float, capsule: MorphFrame, screen: MorphFrame, push: Float): MorphFrame {
        val travel = 1f - progress.coerceIn(0f, 1f)
        // The size follows the progress evenly, as on opening (T67.8): an eased shrink left the end of a drag (and of
        // the spring after it) moving almost nothing.
        val shrink = travel
        val centre = arcCentre(travel, screen.rect.center, capsule.rect.center, push)
        val width = lerp(screen.rect.width, capsule.rect.width, shrink)
        val height = lerp(screen.rect.height, capsule.rect.height, shrink)
        return rounded(rectAround(centre, width, height), lerp(screen.radius, capsule.radius, shrink))
    }

    /**
     * The centre of a dismissed bubble [travel] (0..1) of the way from [start] to [end]: a cubic Bézier that leaves
     * [start] straight down, bottoms out about [push] px below it, sweeps right and rises straight up into [end]. Its
     * x never goes back toward [start].
     */
    fun arcCentre(travel: Float, start: Offset, end: Offset, push: Float): Offset {
        val s = travel.coerceIn(0f, 1f)
        val low = start.y + push
        val c1 = Offset(start.x, low)
        val c2 = Offset(end.x, low)
        val u = 1f - s
        val a = u * u * u
        val b = 3f * u * u * s
        val c = 3f * u * s * s
        val d = s * s * s
        return Offset(
            a * start.x + b * c1.x + c * c2.x + d * end.x,
            a * start.y + b * c1.y + c * c2.y + d * end.y,
        )
    }

    /**
     * How far [frame] has grown from [capsule] (0) to [screen] (1), by its size alone, whatever route and easing took
     * it there: what is carried inside the bubble follows the bubble itself, not the progress driving it.
     */
    fun openness(frame: MorphFrame, capsule: MorphFrame, screen: MorphFrame): Float {
        val span = (screen.rect.width - capsule.rect.width) + (screen.rect.height - capsule.rect.height)
        if (span <= 0f) return 1f
        val grown = (frame.rect.width - capsule.rect.width) + (frame.rect.height - capsule.rect.height)
        return (grown / span).coerceIn(0f, 1f)
    }

    /**
     * Now Playing's controls at the bubble's [openness] (by its size, never the raw progress; internal so only
     * [TranslucentBubble] reads it) (T67.7: one mapping for both routes, so an opening is a dismissal played backwards): they are there only on a large card, fading out from the first shrink of a dismissal and
     * coming in last on opening, never on an opaque card riding down nor on a small one growing.
     */
    internal fun contentAlpha(openness: Float): Float = smoothstep(CONTENT_FROM, CONTENT_TO, openness)

    /**
     * Now Playing's artwork backdrop over the translucency at the bubble's [openness] (by its size, never the raw
     * progress; internal so only [TranslucentBubble] reads it): gone before the card is about square,
     * so a small card reads as clear translucency with only the carried artwork. Its window lies before the controls',
     * so they never show on bare translucency.
     */
    internal fun backdropAlpha(openness: Float): Float = smoothstep(BACKDROP_FROM, BACKDROP_TO, openness)

    /**
     * Whether the surface's backdrop ([backdropAlpha]) is fully opaque at the bubble's [openness], so the translucency
     * beneath it shows nowhere and need not be drawn.
     */
    internal fun coversTranslucent(openness: Float): Boolean = backdropAlpha(openness) >= 1f

    /**
     * How much of the bubble's translucency is liquid (1) rather than the cheap flat translucency (0) at the bubble's
     * [openness]: all of it up to a tenth of the way below [limit], none from [limit] on, handing over smoothly between, so
     * the card keeps one opacity throughout ([TranslucentBubble.liquidAlpha] and [TranslucentBubble.flatAlpha] add up to its
     * translucency). A [limit] of the whole card (1) keeps it liquid at every size.
     *
     * Liquid translucency costs about 5 ms of GPU a megapixel on the Pad 6 (a blur, a refraction and a light pass, in a layer
     * of its own that a moving surface redraws every frame): at most 6 ms while the card is small, but 28 ms a frame over the
     * whole screen, which made opening and dismissing Now Playing by drag run at 20 frames a second.
     */
    internal fun liquidShare(openness: Float, limit: Float): Float =
        if (limit >= 1f) 1f else 1f - smoothstep(limit - LIQUID_HAND_OVER, limit, openness)

    /**
     * The capsule itself at [progress]: it hands over to the bubble's translucency
     * ([TranslucentBubble.translucentAlpha]) while the bubble is still about its size.
     */
    fun capsuleAlpha(progress: Float): Float = 1f - smoothstep(HANDOVER_FROM, HANDOVER_TO, progress)

    /**
     * How a layer laid out at [size] (the full screen) is drawn into [frame]: scaled uniformly to cover the frame
     * (never letterboxed), centred on it and clipped to it.
     */
    fun layer(frame: MorphFrame, size: Size): MorphLayer {
        if (size.width <= 0f || size.height <= 0f) return MorphLayer(1f, Offset.Zero, frame.rect, frame.radius)
        val scale = max(frame.rect.width / size.width, frame.rect.height / size.height).coerceAtLeast(MIN_SCALE)
        val centre = Offset(size.width / 2f, size.height / 2f)
        val clip = rectAround(centre, frame.rect.width / scale, frame.rect.height / scale)
        return MorphLayer(scale, frame.rect.center - centre, clip, frame.radius / scale)
    }

    private fun rounded(rect: Rect, radius: Float) = MorphFrame(rect, radius.coerceIn(0f, rect.minDimension / 2f))

    private fun lerpRect(from: Rect, to: Rect, t: Float) = Rect(
        lerp(from.left, to.left, t),
        lerp(from.top, to.top, t),
        lerp(from.right, to.right, t),
        lerp(from.bottom, to.bottom, t),
    )

    private const val CONTENT_FROM = 0.6f
    private const val CONTENT_TO = 1f
    private const val BACKDROP_FROM = 0.35f
    private const val BACKDROP_TO = 0.85f
    private const val LIQUID_HAND_OVER = 0.1f
    private const val HANDOVER_FROM = 0.03f
    private const val HANDOVER_TO = 0.15f

    /** A degenerate (empty) frame still draws a layer that can be inverted. */
    private const val MIN_SCALE = 0.001f
}

/** A translucent bubble now ([bubble]) with the [capsule] it grows out of and the [screen] it fills, in one coordinate space. */
@Immutable
data class BubbleFrames(val bubble: MorphFrame, val capsule: MorphFrame, val screen: MorphFrame) {
    /** The same frames, all moved by [offset] (e.g. from a host's coordinates into the root's). */
    fun translate(offset: Offset): BubbleFrames = BubbleFrames(bubble.moved(offset), capsule.moved(offset), screen.moved(offset))

    private fun MorphFrame.moved(offset: Offset) = MorphFrame(rect.translate(offset), radius)
}

/** The [width] x [height] rectangle centred on [centre]. */
internal fun rectAround(centre: Offset, width: Float, height: Float): Rect =
    Rect(centre.x - width / 2f, centre.y - height / 2f, centre.x + width / 2f, centre.y + height / 2f)

/** 0 up to [from], 1 from [to], smoothly in between. */
internal fun smoothstep(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
