package io.sonora.core.designsystem.component.visualcollection

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import io.sonora.core.designsystem.component.Artwork
import io.sonora.core.designsystem.translucent.BubbleFrames
import io.sonora.core.designsystem.translucent.TranslucentMorph
import io.sonora.core.designsystem.translucent.MorphFrame
import kotlin.math.min

/**
 * The hand-off between a capsule and a screen that opens over it with the capsule's artwork as its own (T67: Now
 * Playing from the visual collection's "Now Playing" capsule, or from the mini player; T66 can hand a page's artwork over the
 * same way). The host marks it [active] while a hand-off runs, and says how far the other screen is open ([progress],
 * 0 the capsule .. 1 the other screen) and where that screen's artwork is ([target]). The capsule reports itself
 * ([handoffCapsule]) and its thumbnail ([handoffThumbnail]); a [VisualCollection] also reports its centre cover while it is
 * the playing album's. Without a [bubble] the shared artwork ([source]: that centre cover, else the thumbnail) flies
 * on its own; with one, the thumbnail is carried inside the bubble (T67.4). While the artwork is [flying], its source
 * and its target hide their own and [SharedArtworkFlight] draws the one artwork between them, live. Everything is in
 * root pixels; read at draw time.
 */
@Stable
class VisualCollectionHandoff {
    /** A hand-off runs: until then the capsule hides nothing and nothing flies. */
    var active by mutableStateOf(false)

    /** How far the other screen is open: 0 (the capsule's screen) .. 1 (the other screen). */
    var progress: () -> Float = { 0f }

    /** The other screen's artwork, or null while it shows none (then the capsule's stays where it is). */
    var target: () -> ArtworkPose? = { null }

    /**
     * The translucent bubble the other screen grows in, with its capsule and screen, now (root px); null while it opens some
     * other way. While set, the shared artwork is the capsule's thumbnail, carried inside the bubble.
     */
    var bubble: (() -> BubbleFrames?)? = null

    /** The capsule, while it is placed. */
    var capsule: MorphFrame? = null
        private set

    /** The capsule's thumbnail, while it is placed. */
    internal var thumbnail: ArtworkPose? = null
        private set

    /** The centre cover's pose now, while it is the shared artwork (see [ArtworkFlight.centreIsShared]). */
    internal var centre: (() -> ArtworkPose?)? = null

    // Who placed the capsule and the thumbnail: only they take them away again (a capsule leaving after another took
    // its place leaves the new one reported).
    private var capsuleOwner: Any? = null
    private var thumbnailOwner: Any? = null

    internal fun placeCapsule(owner: Any, frame: MorphFrame) {
        capsuleOwner = owner
        capsule = frame
    }

    internal fun removeCapsule(owner: Any) {
        if (capsuleOwner !== owner) return
        capsuleOwner = null
        capsule = null
    }

    internal fun placeThumbnail(owner: Any, pose: ArtworkPose) {
        thumbnailOwner = owner
        thumbnail = pose
    }

    internal fun removeThumbnail(owner: Any) {
        if (thumbnailOwner !== owner) return
        thumbnailOwner = null
        thumbnail = null
    }

    /** Where the shared artwork leaves from: the thumbnail in a bubble, else the centre cover when shared, else the thumbnail. */
    fun source(): ArtworkPose? = if (bubble != null) thumbnail else centre?.invoke() ?: thumbnail

    /** The shared artwork is between the two screens: drawn by [SharedArtworkFlight], hidden in both. */
    fun flying(): Boolean {
        if (!active) return false
        val p = progress()
        if (p <= 0f || p >= 1f || source() == null || target() == null) return false
        return bubble.let { it == null || it() != null }
    }

    /** Where the flying artwork is now, or null while it is not [flying]. */
    fun flight(): ArtworkPose? {
        if (!flying()) return null
        val from = source() ?: return null
        val to = target() ?: return null
        val carrier = bubble ?: return ArtworkFlight.pose(progress(), from, to)
        return ArtworkFlight.carried(carrier() ?: return null, from, to)
    }

    /** The visual collection hides its centre cover: it is the artwork flying (never in a bubble, which carries the thumbnail). */
    internal fun hidesCentre(): Boolean = bubble == null && centre != null && flying()

    /**
     * The capsule thumbnail's own opacity, within the capsule's: none while it is the artwork flying (carried in a
     * bubble, or flying on its own), so only the flying one shows and it hands over at once at each end (T67.7) instead
     * of fading with the capsule beside it.
     */
    internal fun thumbnailAlpha(): Float = if (flying() && (bubble != null || centre?.invoke() == null)) 0f else 1f

    /** The capsule's opacity: it fades as the other screen grows out of it. */
    internal fun capsuleAlpha(): Float = if (active) TranslucentMorph.capsuleAlpha(progress()) else 1f
}

/**
 * Reports this capsule to [handoff] while it is placed, and fades it as another screen grows out of it; it is taken
 * away again as soon as the capsule leaves (T67.3), so a bubble never shrinks into a capsule that is gone. Null: none.
 */
fun Modifier.handoffCapsule(handoff: VisualCollectionHandoff?): Modifier =
    if (handoff == null) this else this then CapsuleReportElement(handoff).graphicsLayer { alpha = handoff.capsuleAlpha() }

/**
 * Reports this artwork, with corners of [radius] px, as [handoff]'s capsule thumbnail while it is placed (see
 * [handoffCapsule]), and hides it while it is the artwork flying ([VisualCollectionHandoff.thumbnailAlpha]).
 */
fun Modifier.handoffThumbnail(handoff: VisualCollectionHandoff?, radius: Float): Modifier =
    if (handoff == null) this else this then ThumbnailReportElement(handoff, radius).graphicsLayer { alpha = handoff.thumbnailAlpha() }

private data class CapsuleReportElement(val handoff: VisualCollectionHandoff) : ModifierNodeElement<CapsuleReportNode>() {
    override fun create() = CapsuleReportNode(handoff)

    override fun update(node: CapsuleReportNode) {
        if (node.handoff !== handoff) node.handoff.removeCapsule(node)
        node.handoff = handoff
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "handoffCapsule"
        properties["handoff"] = handoff
    }
}

private class CapsuleReportNode(var handoff: VisualCollectionHandoff) : Modifier.Node(), GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        handoff.placeCapsule(this, MorphFrame.capsule(Rect(coordinates.positionInRoot(), coordinates.size.toSize())))
    }

    override fun onDetach() = handoff.removeCapsule(this)
}

private data class ThumbnailReportElement(val handoff: VisualCollectionHandoff, val radius: Float) : ModifierNodeElement<ThumbnailReportNode>() {
    override fun create() = ThumbnailReportNode(handoff, radius)

    override fun update(node: ThumbnailReportNode) {
        if (node.handoff !== handoff) node.handoff.removeThumbnail(node)
        node.handoff = handoff
        node.radius = radius
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "handoffThumbnail"
        properties["handoff"] = handoff
        properties["radius"] = radius
    }
}

private class ThumbnailReportNode(var handoff: VisualCollectionHandoff, var radius: Float) : Modifier.Node(), GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        handoff.placeThumbnail(this, ArtworkPose.flat(Rect(coordinates.positionInRoot(), coordinates.size.toSize()), radius))
    }

    override fun onDetach() = handoff.removeThumbnail(this)
}

/** The hand-off a [VisualCollection] reports to and follows; none by default. */
val LocalVisualCollectionHandoff = staticCompositionLocalOf<VisualCollectionHandoff?> { null }

/**
 * Draws [handoff]'s flying artwork, [artwork] (an image model, the same picture both screens show), above everything
 * beneath it in this box, while it flies: the live image, moved, scaled, turned and rounded frame by frame (see
 * [ArtworkFlight]), with the visual collection's perspective. Draws nothing otherwise and takes no touch.
 */
@Composable
fun SharedArtworkFlight(handoff: VisualCollectionHandoff, artwork: String?, modifier: Modifier = Modifier) {
    if (!handoff.active) return
    val origin = remember { OriginHolder() }
    val density = LocalDensity.current
    val camera = CAMERA_DISTANCE * density.density
    val shadow = with(density) { FLIGHT_SHADOW.toPx() }
    val request = sharedArtworkRequest(artwork)
    Box(modifier.fillMaxSize().onGloballyPositioned { origin.value = it.positionInRoot() }) {
        FlightImage(
            request,
            Modifier
                // Laid out once at the screen's shorter side, so the image is not decoded again while it flies.
                .layout { measurable, constraints ->
                    val side = min(constraints.maxWidth, constraints.maxHeight)
                    val placeable = measurable.measure(constraints.copy(minWidth = side, maxWidth = side, minHeight = side, maxHeight = side))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .graphicsLayer {
                    val pose = handoff.flight()
                    if (pose == null || size.width <= 0f) {
                        alpha = 0f
                        return@graphicsLayer
                    }
                    val scale = pose.side / size.width
                    alpha = 1f
                    scaleX = scale
                    scaleY = scale
                    translationX = pose.centre.x - origin.value.x - size.width / 2f
                    translationY = pose.centre.y - origin.value.y - size.height / 2f
                    rotationY = pose.rotationY
                    cameraDistance = camera
                    shape = RoundedCornerShape(pose.radius / scale)
                    clip = true
                    shadowElevation = shadow * handoff.progress().coerceIn(0f, 1f)
                },
        )
    }
}

/**
 * Loads [artwork] at the size [SharedArtworkFlight] draws it, ahead of any hand-off, so the flight's first frame is
 * served from the memory cache (T67). Call it wherever a capsule may hand its artwork over; a new [refresh] asks again
 * (a memory hit while it is still there), in case the cache let it go meanwhile.
 */
@Composable
fun PrefetchSharedArtwork(artwork: String?, refresh: Any? = null) {
    val context = LocalContext.current
    val request = sharedArtworkRequest(artwork) ?: return
    LaunchedEffect(request, refresh) { SingletonImageLoader.get(context).enqueue(request) }
}

/**
 * The flying artwork: [request]'s image, or the placeholder both screens show without one (no request) or when it
 * fails. While it loads it draws nothing rather than the placeholder (the capsule's and Now Playing's are hidden).
 */
@Composable
private fun FlightImage(request: ImageRequest?, modifier: Modifier) {
    if (request == null) {
        Artwork(null, modifier, shape = RectangleShape)
        return
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        error = { Artwork(null, Modifier.fillMaxSize(), shape = RectangleShape) },
    )
}

/**
 * [artwork]'s request for the flight, null without artwork. Its size is fixed up front (the window's shorter side, as
 * the flight lays it out), so Coil reads its memory cache while composing instead of after layout: a size known only
 * from layout costs the first frame (the placeholder). It is cached under its own key, so the same artwork loaded
 * smaller (the capsule's thumbnail, the covers) never takes its place, and inexact, so any copy that large serves it.
 */
@Composable
private fun sharedArtworkRequest(artwork: String?): ImageRequest? {
    if (artwork == null) return null
    val context = LocalContext.current
    val side = sharedArtworkSide(LocalWindowInfo.current.containerSize)
    return remember(context, artwork, side) { sharedArtworkRequest(context, artwork, side) }
}

private fun sharedArtworkRequest(context: PlatformContext, artwork: String, side: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(artwork)
        .size(side)
        .precision(Precision.INEXACT)
        .memoryCacheKeyExtra(SHARED_ARTWORK_CACHE_EXTRA, side.toString())
        .build()

/** The memory cache key extra that keeps the shared artwork apart from the same artwork loaded at other sizes. */
private const val SHARED_ARTWORK_CACHE_EXTRA = "sonora#sharedArtwork"

/** The side the shared artwork is decoded at in a window of [window] px: its shorter side, at least 1 px. */
internal fun sharedArtworkSide(window: IntSize): Int = min(window.width, window.height).coerceAtLeast(1)

/** Where a box is in the root, read at draw time without recomposing. */
internal class OriginHolder {
    var value = Offset.Zero
}

/** The flying artwork's shadow on arrival, as Now Playing's artwork casts one. */
private val FLIGHT_SHADOW = 20.dp
