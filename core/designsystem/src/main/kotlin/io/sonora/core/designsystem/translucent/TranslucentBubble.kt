package io.sonora.core.designsystem.translucent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.TranslucentSurface
import kotlin.math.roundToInt

/**
 * A full-screen surface opened as a translucent bubble out of a capsule (T67: Now Playing out of the Cover Flow's "Now
 * Playing" capsule), driven by the surface's own [progress] (0 the capsule .. 1 the full screen) so a finger dragging it
 * moves the bubble one to one. [capsule] is where it grows from, in the host's coordinates (null: no capsule, no
 * bubble). The bubble takes the [MorphRoute] its last rest calls for ([TranslucentMorph]): it opens straight out of the
 * capsule and is dismissed along the pushed-down arc; caught midway it keeps its route. While [fullScreen] (reduced
 * motion) it does not morph at all: it stays full screen with everything shown, and the host cross-fades it. The translucent surface is drawn by [TranslucentBubbleSurface]; the surface's own content is drawn into the
 * bubble by [translucentBubbleLayer], its backdrop and controls fading in with [backdropAlpha] and [contentAlpha]. While it
 * moves, the screen end has the sheet's rounded corners ([screenRadius], px), so it is a rounded card from the first
 * drag frame; at rest its corners are square. What it shows depends on its size alone ([openness]), so an opening is a
 * dismissal played backwards (T67.7); only the paths differ (straight out, along the arc back).
 */
@Stable
class TranslucentBubble(
    val progress: () -> Float,
    private val capsule: () -> MorphFrame?,
    private val fullScreen: () -> Boolean = { false },
    private val screenRadius: () -> Float = { 0f },
    /**
     * Up to what [openness] the translucency is liquid; a larger card has the cheap flat translucency instead ([flatAlpha]),
     * which the host draws under it. 1 (the default) keeps it liquid at every size. See [TranslucentMorph.liquidShare].
     */
    private val liquidLimit: Float = 1f,
) {
    private var route = MorphRoute.Straight

    /** The bubble in a screen of [size] laid out from the host's origin, or null without a capsule. */
    fun frame(size: Size): MorphFrame? {
        if (fullScreen()) return MorphFrame(Rect(Offset.Zero, size), radius = 0f)
        return frames(size)?.bubble
    }

    /**
     * The bubble with the capsule it grows out of and the screen of [size] it fills, in the host's coordinates, or null
     * without a capsule. The route follows the progress even then: a capsule that leaves while the surface rests open
     * (the mini player under an open Now Playing) still sees it dismissed along the arc when it is back.
     */
    fun frames(size: Size): BubbleFrames? {
        val p = progress()
        route = MorphRoute.follow(route, p)
        val from = capsule() ?: return null
        // Like the plain sheet, a moving bubble has the sheet's rounded corners; at rest it is square.
        val radius = if (p < 1f) screenRadius() else 0f
        val screen = MorphFrame(Rect(Offset.Zero, size), radius)
        return BubbleFrames(TranslucentMorph.frame(route, p, from, screen, size.height * TranslucentMorph.ARC_PUSH_FRACTION), from, screen)
    }

    /**
     * How far the bubble in a host of [size] has grown ([TranslucentMorph.openness]): the progress itself on opening, but on
     * the dismissal's eased arc it falls as fast as the card shrinks (T67.6). The progress while there is no bubble or
     * the host is not laid out yet.
     */
    fun openness(size: Size): Float {
        val p = progress()
        route = MorphRoute.follow(route, p)
        val frames = if (size.width > 0f && size.height > 0f) frames(size) else null
        return frames?.let { TranslucentMorph.openness(it.bubble, it.capsule, it.screen) } ?: p.coerceIn(0f, 1f)
    }

    /**
     * Now Playing's controls in a host of [size], by the bubble's size on either route ([TranslucentMorph.contentAlpha]): only
     * on a large card, so a dismissal fades them from its first shrink and an opening brings them in last.
     */
    fun contentAlpha(size: Size): Float = if (fullScreen()) 1f else TranslucentMorph.contentAlpha(openness(size))

    /**
     * Now Playing's backdrop in a host of [size], by the bubble's size on either route ([TranslucentMorph.backdropAlpha]): a
     * small card is clear translucency with only the carried artwork.
     */
    fun backdropAlpha(size: Size): Float = if (fullScreen()) 1f else TranslucentMorph.backdropAlpha(openness(size))

    /**
     * The bubble's own translucency: it cross-fades with the capsule (which fades by [TranslucentMorph.capsuleAlpha] of the same
     * progress), so landing it hands over to the real capsule, with its own light, shadow and label, instead of covering
     * it; not drawn once fully open, where Now Playing's backdrop covers it.
     */
    fun translucentAlpha(): Float {
        val p = progress()
        return if (p < 1f) 1f - TranslucentMorph.capsuleAlpha(p) else 0f
    }

    /**
     * [translucentAlpha] in a host of [size]: nothing either once the surface's own backdrop is fully opaque over the translucency
     * ([TranslucentMorph.coversTranslucent], by the bubble's size). Hidden, the translucency is not drawn at all; drawn under that backdrop it
     * was a blur and a refraction shader over most of the screen for the rest of the opening, for nothing to see.
     */
    fun translucentAlpha(size: Size): Float =
        if (!fullScreen() && TranslucentMorph.coversTranslucent(openness(size))) 0f else translucentAlpha()

    /** The liquid part of [translucentAlpha] in a host of [size]: all of it for a small card, none for a large one. */
    fun liquidAlpha(size: Size): Float = translucentAlpha(size) * TranslucentMorph.liquidShare(openness(size), liquidLimit)

    /** The flat part of [translucentAlpha] in a host of [size]: what [liquidAlpha] leaves, so the two add up to it. */
    fun flatAlpha(size: Size): Float = translucentAlpha(size) * (1f - TranslucentMorph.liquidShare(openness(size), liquidLimit))

    companion object {
        /**
         * The [liquidLimit] on a large screen, where a liquid translucent card is too heavy to move: liquid only for the first
         * tenth of the way, while the capsule is still showing through, flat translucency from then on (0.3 up to T12, which
         * measured 7 % janky frames on the Pad 6 opening Now Playing; 4.7 % with this limit, 3.8 % with none).
         */
        const val LARGE_LIQUID_LIMIT = 0.1f
    }
}

/**
 * Draws this full-screen layer into [bubble]'s frame: scaled to cover it, centred on it and clipped to its rounded
 * shape ([TranslucentMorph.layer]); [otherwise] draws it while there is no frame.
 */
fun GraphicsLayerScope.translucentBubbleLayer(bubble: TranslucentBubble, otherwise: GraphicsLayerScope.() -> Unit) {
    val frame = bubble.frame(size)
    if (frame == null) {
        otherwise()
        return
    }
    val layer = TranslucentMorph.layer(frame, size)
    scaleX = layer.scale
    scaleY = layer.scale
    translationX = layer.translation.x
    translationY = layer.translation.y
    // A new shape each frame: a layer only outlines its shape again when the shape changes.
    shape = BubbleClip(layer.clip, layer.radius)
    clip = true
}

/** [rect] with corners of [radius], within a layer of any size. */
private data class BubbleClip(val rect: Rect, val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

/**
 * The translucent surface of [bubble] over [backdrop] (the content it grows over): the capsule's own translucency ([darkTheme] and
 * [tinted] as the capsule draws it: the Cover Flow's clear dark translucency by default, the mini player's tinted one),
 * refraction and blur included, laid out at the bubble's frame each frame, so it grows and moves with it. Fill the host
 * with [modifier]; it fades with [TranslucentBubble.translucentAlpha], handing over to the capsule near it and not drawn once the
 * bubble is fully open (the surface's own backdrop covers it then), and takes no touch. With [flat] it is the tint of the
 * flat translucency alone (no backdrop is sampled), the part of the translucency a large card has ([TranslucentBubble.flatAlpha]).
 */
@Composable
fun TranslucentBubbleSurface(
    bubble: TranslucentBubble,
    backdrop: TranslucentBackdrop?,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = true,
    tinted: Boolean = false,
    flat: Boolean = false,
) {
    val corner = remember(bubble) { BubbleCorner() }
    val shape = remember(corner) { RoundedCornerShape(corner) }
    val framed = modifier
        .fillMaxSize()
        .layout { measurable, constraints ->
            val frame = bubble.frame(Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()))
            val rect = frame?.rect ?: Rect.Zero
            corner.px = frame?.radius ?: 0f
            val width = rect.width.roundToInt().coerceAtLeast(0)
            val height = rect.height.roundToInt().coerceAtLeast(0)
            val placeable = measurable.measure(Constraints.fixed(width, height))
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place(rect.left.roundToInt(), rect.top.roundToInt())
            }
        }
        .graphicsLayer { alpha = if (flat) bubble.flatAlpha(size) else bubble.liquidAlpha(size) }
    if (flat) {
        // Only the tint of the flat translucency, in one fill: a large card is more than a screen's worth of pixels, and its
        // gloss (a sheen, a band and a caustic over most of its width) is the thin edge light a flat translucency has no use for there.
        val fill = remember(darkTheme, tinted) {
            TranslucentMaterial.of(darkTheme, TranslucentTier.FLAT, highContrast = false, tinted = tinted).let { Color(it.tint).copy(alpha = it.tintAlpha) }
        }
        Box(framed.clip(shape).background(fill))
    } else {
        TranslucentSurface(framed, shape = shape, elevation = 0.dp, darkTheme = darkTheme, backdrop = backdrop, tinted = tinted) {}
    }
}

/**
 * The bubble's corner, read whenever its outline is made again: the surface outlines its shape again as its size changes,
 * which it does with every step of the bubble.
 */
private class BubbleCorner : CornerSize {
    var px = 0f

    override fun toPx(shapeSize: Size, density: Density): Float = px.coerceIn(0f, shapeSize.minDimension / 2f)
}
