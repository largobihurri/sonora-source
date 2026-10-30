package io.sonora.core.designsystem.component.visualcollection

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import io.sonora.core.designsystem.component.Artwork
import io.sonora.core.designsystem.component.ArtworkMosaic
import io.sonora.core.designsystem.component.TranslucentPill
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.component.pillLeadingInset
import io.sonora.core.designsystem.translucent.TranslucentBackdrop
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.translucent.translucentBackdropSource
import io.sonora.core.designsystem.translucent.rememberTranslucentBackdrop
import io.sonora.core.designsystem.motion.prefersReducedMotion
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.layout.LocalWindowClass
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** One cover of a [VisualCollection]: [covers] are drawn as an [ArtworkMosaic] (empty: the placeholder). */
@Immutable
data class VisualCollectionItem<out K>(val key: K, val covers: List<String>, val title: String, val subtitle: String)

/**
 * A round translucent button in a corner of the [VisualCollection]: [icon], or the small rounded [artwork] (an image model, e.g. a
 * URI) when there is one. With a [caption] it grows into a translucent capsule: the artwork (or icon), then the caption.
 */
@Immutable
data class VisualCollectionAction(
    val icon: ImageVector,
    val description: String,
    val onClick: () -> Unit,
    val artwork: String? = null,
    val caption: VisualCollectionCaption? = null,
)

/** The two lines of a capsule [VisualCollectionAction]: a small secondary [label] above the primary [text]. */
@Immutable
data class VisualCollectionCaption(val label: String, val text: String)

/**
 * The original iPhone visual collection, fullscreen: [items] as a strip of covers, the centre one facing the viewer with its
 * title and subtitle below, the others turned toward it and stacked; every cover has a reflection. Dragging moves the
 * strip with the finger, a fling coasts and settles on a cover, both no faster than [VisualCollectionPhysics.MAX_SPEED];
 * tapping a side cover brings it to the centre.
 * Tapping the centre cover (or the info button, [infoDescription]) flips it over to show [back], which gets a way to
 * flip it back (Back does too). The background is the centre cover's colours, blurred and darkened, with a glossy
 * sheen. [leadingAction] is an optional translucent button in the bottom-left corner (e.g. play/pause), [trailingTopAction] one
 * in the top-right corner (e.g. open the player), [closeAction] one in the top-left corner (a large screen's way out). The strip opens on [initialIndex]; past the last cover, it settles on the last one.
 * [onCentreChange] hears the centre cover's index as it changes (and once on opening), so a caller can reopen it there.
 * Under a [LocalVisualCollectionHandoff] it reports its capsule and the shared artwork, and hides them while another screen
 * grows out of the capsule with that artwork (see [VisualCollectionHandoff]).
 */
@Composable
fun <K> VisualCollection(
    items: List<VisualCollectionItem<K>>,
    infoDescription: String,
    modifier: Modifier = Modifier,
    leadingAction: VisualCollectionAction? = null,
    trailingTopAction: VisualCollectionAction? = null,
    closeAction: VisualCollectionAction? = null,
    initialIndex: Int = 0,
    onCentreChange: (Int) -> Unit = {},
    back: @Composable (item: VisualCollectionItem<K>, close: () -> Unit) -> Unit,
) {
    val count = items.size
    var savedIndex by rememberSaveable { mutableIntStateOf(initialIndex.coerceAtLeast(0)) }
    val position = remember { Animatable(savedIndex.toFloat()) }
    val flip = remember { Animatable(0f) }
    // The back's translucent surface fades in once the card has settled flat (see VisualCollectionBackTranslucent.veil).
    val settled = remember { Animatable(0f) }
    // Followed by key: a list change while a cover is turned (an item added before it) keeps the same item's back.
    var flipped by remember { mutableStateOf<FlippedCover?>(null) }
    val scope = rememberCoroutineScope()
    val centre by remember { derivedStateOf { position.value.roundToInt() } }
    val window by remember(count) { derivedStateOf { VisualCollectionGeometry.window(position.value, count) } }

    val latestCentreChange by rememberUpdatedState(onCentreChange)
    LaunchedEffect(centre) {
        savedIndex = centre
        latestCentreChange(centre)
    }
    LaunchedEffect(count) {
        if (count > 0 && position.value > count - 1) position.snapTo((count - 1).toFloat())
    }
    // The turned cover stays the centre one where the list moved it; gone from the list, its back closes at once.
    LaunchedEffect(items) {
        val turned = flipped ?: return@LaunchedEffect
        val index = turned.indexIn(items)
        if (index == null) {
            flipped = null
            flip.snapTo(0f)
            settled.snapTo(0f)
        } else if (index != centre) {
            position.snapTo(index.toFloat())
        }
    }
    val flippedIndex = flipped?.indexIn(items)

    // The background holds while the covers race past and settles on the nearest one once the strip slows (see
    // BackdropHold). The strip's speed is read frame by frame off composition, so moving it never recomposes here.
    val context = LocalContext.current
    val backdropFadeMs = remember(context) { BackdropHold.fadeMs(context.prefersReducedMotion()) }
    val latestItems by rememberUpdatedState(items)
    var backdropArtwork by remember { mutableStateOf(items.getOrNull(centre)?.covers?.firstOrNull()) }
    LaunchedEffect(Unit) {
        var hold = BackdropHold.start(backdropArtwork, withFrameMillis { it })
        var last = position.value
        var lastMs = hold.shownSinceMs
        snapshotFlow { position.value.let { it to latestItems.getOrNull(it.roundToInt())?.covers?.firstOrNull() } }
            .collectLatest { (at, nearest) ->
                var now = withFrameMillis { it }
                hold = hold.next(nearest, BackdropHold.speed(last, at, now - lastMs), now, backdropFadeMs)
                last = at
                lastMs = now
                backdropArtwork = hold.shown
                // A strip that stopped sends no more frames: the nearest cover takes over when its time comes.
                while (true) {
                    val due = hold.settlesAtMs(backdropFadeMs) ?: break
                    delay((due - now).coerceAtLeast(0L))
                    now = withFrameMillis { it }
                    hold = hold.next(nearest, 0f, now, backdropFadeMs)
                    backdropArtwork = hold.shown
                }
            }
    }

    fun openFlip(index: Int) {
        if (flipped != null) return
        flipped = FlippedCover(items[index].key)
        scope.launch {
            flip.animateTo(1f, tween(FLIP_MS, easing = FastOutSlowInEasing))
            // Only a completed turn gets here: a close during it cancels this coroutine.
            settled.animateTo(1f, tween(TRANSLUCENT_FADE_MS))
        }
    }
    fun closeFlip() {
        scope.launch {
            flip.animateTo(0f, tween(FLIP_MS, easing = FastOutSlowInEasing))
            flipped = null
            settled.snapTo(0f)
        }
    }
    BackHandler(enabled = flipped != null) { closeFlip() }

    val translucent = rememberTranslucentBackdrop()
    // What the flipped card's translucent back samples: the background and the covers, recorded only while a card is turned.
    val backTranslucent = rememberTranslucentBackdrop()
    val handoff = LocalVisualCollectionHandoff.current
    val large = LocalWindowClass.current.isLarge
    val origin = remember { OriginHolder() }
    if (handoff != null) {
        DisposableEffect(handoff) {
            // The capsule and its thumbnail take their own frames away as they leave (see handoffCapsule).
            onDispose { handoff.centre = null }
        }
    }
    val placed = if (handoff != null) Modifier.onGloballyPositioned { origin.value = it.positionInRoot() } else Modifier
    BoxWithConstraints(modifier.fillMaxSize().then(placed)) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        // A phone keeps the strip near the top; a large window upright centres bigger covers (see VisualCollectionGeometry.frame).
        val frame = VisualCollectionGeometry.frame(widthPx, heightPx, large, captionReserve = with(density) { TITLE_RESERVE.toPx() })
        val coverPx = frame.coverSize
        val coverTop = frame.coverTop
        val coverLeft = (widthPx - coverPx) / 2f
        // A flipped cover grows into its back, a larger card in the middle of the screen.
        val backPx = maxOf(VisualCollectionGeometry.backSize(widthPx, heightPx), coverPx)
        // The centre cover is the star: the side covers shrink more so they keep their size and spacing.
        val geometry = remember(coverPx) {
            VisualCollectionGeometry(coverPx, sideStep = coverPx * SIDE_STEP_FRACTION, sideScale = SIDE_SCALE)
        }
        val coverDp = with(density) { coverPx.toDp() }
        val camera = CAMERA_DISTANCE * density.density
        val decay = remember { exponentialDecay<Float>(frictionMultiplier = FLING_FRICTION) }
        if (handoff != null) {
            // The centre cover is the shared artwork while it is the playing album's: its pose, live, in root pixels.
            val shared = centre.takeIf { ArtworkFlight.centreIsShared(items.getOrNull(it)?.covers.orEmpty(), trailingTopAction?.artwork) }
            SideEffect {
                handoff.centre = shared?.let { index ->
                    {
                        val slot = Rect(origin.value + Offset(coverLeft, coverTop), Size(coverPx, coverPx))
                        ArtworkPose.cover(slot, geometry.transform(index - position.value))
                    }
                }
            }
        }

        val gestures = if (flipped == null && count > 0) {
            Modifier
                .pointerInput(count, geometry) {
                    val tracker = VelocityTracker()
                    var start = 0f
                    var dragged = 0f
                    // Where the finger has dragged the strip; the strip chases it no faster than its top speed.
                    var finger = 0f
                    var chasing: Job? = null
                    detectHorizontalDragGestures(
                        onDragStart = {
                            tracker.resetTracking()
                            start = position.value
                            dragged = 0f
                            finger = start
                            chasing?.cancel()
                            chasing = scope.launch { position.chase { finger } }
                        },
                        onDragEnd = {
                            chasing?.cancel()
                            val velocity = VisualCollectionPhysics.itemVelocity(tracker.calculateVelocity().x, geometry.dragPxPerItem)
                            scope.launch { position.flingTo(velocity, count, decay) }
                        },
                        onDragCancel = {
                            chasing?.cancel()
                            scope.launch { position.flingTo(0f, count, decay) }
                        },
                    ) { change, dx ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        dragged += dx
                        finger = VisualCollectionPhysics.dragPosition(start, dragged, geometry.dragPxPerItem, count)
                    }
                }
                .pointerInput(count, geometry) {
                    var caught = false
                    detectTapGestures(
                        onPress = {
                            // A finger on a moving strip catches it; lifting it then settles on the nearest cover.
                            caught = position.isRunning
                            if (caught) scope.launch { position.stop() }
                        },
                    ) { tap ->
                        val onCover = tap.y in coverTop..(coverTop + coverPx)
                        val hit = if (onCover) geometry.hitTest(tap.x - widthPx / 2f, position.value, window) else null
                        when {
                            caught -> scope.launch { position.flingTo(0f, count, decay) }
                            hit == null -> Unit
                            hit == centre && abs(position.value - hit) < SETTLED -> openFlip(hit)
                            else -> scope.launch { position.animateTo(hit.toFloat(), ScrollSpring) }
                        }
                    }
                }
        } else {
            Modifier
        }

        Box(Modifier.fillMaxSize().translucentBackdropSource(backTranslucent, enabled = flipped != null)) {
            VisualCollectionBackground(backdropArtwork, backdropFadeMs, Modifier.fillMaxSize().translucentBackdropSource(translucent))
            Box(Modifier.fillMaxSize().then(gestures)) {
                for (index in window) {
                    val item = items[index]
                    key(item.key) {
                        Box(
                            Modifier
                                .zIndex(-abs(index - centre).toFloat())
                                .offset { IntOffset(coverLeft.roundToInt(), coverTop.roundToInt()) }
                                .requiredSize(coverDp)
                                .graphicsLayer {
                                    val t = geometry.transform(index - position.value)
                                    translationX = t.translationX
                                    translationY = t.translationY
                                    rotationY = t.rotationY
                                    scaleX = t.scale
                                    scaleY = t.scale
                                    cameraDistance = camera
                                    // The flipping card stands in for the centre cover, the flying artwork for a shared one.
                                    val handedOff = index == centre && handoff?.hidesCentre() == true
                                    alpha = if (flippedIndex == index || handedOff) 0f else 1f
                                }
                                .reflection(),
                        ) {
                            ArtworkMosaic(item.covers, Modifier.fillMaxSize(), CoverShape)
                        }
                    }
                }
            }
        }

        items.getOrNull(centre)?.let { item ->
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 96.dp)
                    .padding(bottom = 20.dp)
                    .graphicsLayer {
                        val drift = abs(position.value - centre) * 2f
                        alpha = (1f - drift).coerceIn(0f, 1f) * (1f - flip.value)
                    },
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    item.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        val flippedItem = flippedIndex?.let(items::getOrNull)
        if (flippedItem != null) {
            // Dims the strip and keeps touches off it while the card is turned; a tap outside the card turns it back.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = flip.value }
                    .background(Color.Black.copy(alpha = FLIPPED_SCRIM))
                    .pointerInput(Unit) { detectTapGestures { closeFlip() } },
            )
            val backDp = with(density) { backPx.toDp() }
            // From the cover's slot to the middle of the screen.
            val rise = heightPx / 2f - (coverTop + coverPx / 2f)
            val showBack by remember { derivedStateOf { flip.value >= 0.5f } }
            if (!showBack) {
                Box(
                    Modifier
                        .offset { IntOffset(coverLeft.roundToInt(), coverTop.roundToInt()) }
                        .requiredSize(coverDp)
                        .graphicsLayer {
                            val p = flip.value
                            val grow = lerp(1f, backPx / coverPx, p)
                            scaleX = grow
                            scaleY = grow
                            translationY = rise * p
                            rotationY = 180f * p
                            cameraDistance = camera
                        },
                ) {
                    ArtworkMosaic(flippedItem.covers, Modifier.fillMaxSize(), CoverShape)
                }
            } else {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .requiredSize(backDp)
                        .graphicsLayer {
                            val p = flip.value
                            val grow = lerp(coverPx / backPx, 1f, p)
                            scaleX = grow
                            scaleY = grow
                            translationY = -rise * (1f - p)
                            rotationY = 180f * p - 180f
                            cameraDistance = camera
                        }
                        .clip(CoverShape)
                        // A tap on the card's empty space stays on the card instead of reaching the scrim behind it.
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    // The surface samples a flat card only: it shows once the turn has settled, under a veil before.
                    VisualCollectionBackPanel(
                        flippedItem.covers.firstOrNull(),
                        backTranslucent,
                        veil = { VisualCollectionBackTranslucent.veil(settled.value, flip.value) },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        back(flippedItem) { closeFlip() }
                    }
                }
            }
        }

        val buttons = Modifier.windowInsetsPadding(WindowInsets.displayCutout).padding(CORNER_PADDING)
        if (leadingAction != null) {
            CornerButton(translucent, leadingAction, buttons.align(Alignment.BottomStart))
        }
        // A large window has no phone-style turn back to leave the visual collection: a visible close button, top-start.
        if (closeAction != null) {
            CornerButton(translucent, closeAction, buttons.align(Alignment.TopStart))
        }
        if (trailingTopAction != null) {
            val trailing = buttons.align(Alignment.TopEnd)
            if (trailingTopAction.caption != null) {
                // Never wider than the room between the screen edge (the cutout counted on the capsule's own side
                // only) and the card on show, which grows from the centre cover into the wider back as it flips.
                val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                val cutout = WindowInsets.displayCutout
                val cutoutPx = VisualCollectionGeometry.endInset(
                    cutout.getLeft(density, LayoutDirection.Ltr).toFloat(),
                    cutout.getRight(density, LayoutDirection.Ltr).toFloat(),
                    rtl,
                )
                CaptionButton(
                    translucent,
                    trailingTopAction,
                    trailingTopAction.caption,
                    handoff,
                    modifier = trailing.layout { measurable, constraints ->
                        // Read in the layout phase: the width follows the flip frame by frame without recomposing.
                        val card = VisualCollectionGeometry.cardWidth(widthPx, heightPx, flip.value, cover = coverPx)
                        val max = VisualCollectionGeometry.captionMaxWidth(
                            widthPx, card, CORNER_PADDING.toPx(), cutoutPx, CAPTION_GAP.toPx(), CAPTION_FLOOR.toPx(),
                            CAPTION_CAP.toPx(),
                            captionBottom = (CORNER_PADDING + CAPTION_HEIGHT).toPx(),
                            cardTop = VisualCollectionGeometry.cardTop(heightPx, coverTop, coverPx, backPx, flip.value),
                        ).roundToInt().coerceIn(constraints.minWidth, constraints.maxWidth)
                        val placeable = measurable.measure(constraints.copy(maxWidth = max))
                        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                    },
                )
            } else {
                CornerButton(translucent, trailingTopAction, trailing)
            }
        }
        if (count > 0) {
            CornerButton(
                translucent,
                VisualCollectionAction(SonoraIcons.Info, infoDescription, onClick = {
                    if (flipped != null) closeFlip() else if (abs(position.value - centre) < SETTLED) openFlip(centre)
                }),
                modifier = buttons.align(Alignment.BottomEnd),
            )
        }
    }
}

/**
 * Settles a fling on the cover it would have coasted nearest to: a far one in a single [VisualCollectionPhysics.glide] that
 * leaves at the fling's speed (capped at [VisualCollectionPhysics.MAX_SPEED]) and ends exactly on the cover, a near one with
 * a spring. The cover comes from the uncapped fling, so a strong fling travels as far, for longer.
 */
private suspend fun Animatable<Float, AnimationVector1D>.flingTo(velocity: Float, count: Int, decay: DecayAnimationSpec<Float>) {
    if (count <= 0) return
    val target = VisualCollectionPhysics.snapIndex(decay.calculateTargetValue(value, velocity), count)
    val leaving = VisualCollectionPhysics.cappedVelocity(velocity)
    if (VisualCollectionPhysics.coastsFirst(value, target, leaving)) {
        val ms = VisualCollectionPhysics.flingGlideMs(target - value, leaving)
        // A clamped or lengthened duration keeps the fling's speed at the start by bending the curve instead.
        val slope = VisualCollectionPhysics.glideStartSlope(target - value, leaving, ms)
        animateTo(target.toFloat(), tween(ms, easing = Easing { VisualCollectionPhysics.glide(it, slope) }))
    } else {
        animateTo(target.toFloat(), SettleSpring, initialVelocity = leaving)
    }
}

/**
 * Moves the strip toward where the finger has dragged it ([finger]) every frame, as far as [VisualCollectionPhysics.chase]
 * allows, until cancelled when the finger lifts.
 */
private suspend fun Animatable<Float, AnimationVector1D>.chase(finger: () -> Float) {
    var last = withFrameMillis { it }
    while (true) {
        val now = withFrameMillis { it }
        snapTo(VisualCollectionPhysics.chase(value, finger(), (now - last).toFloat()))
        last = now
    }
}

/** The cover turned over to show its back, followed by its [key] (see [indexIn]). */
internal class FlippedCover(val key: Any?) {
    /** Where the turned cover is in [items] now, or `null` once it left the list. */
    fun indexIn(items: List<VisualCollectionItem<*>>): Int? = items.indexOfFirst { it.key == key }.takeIf { it >= 0 }
}

/** The cover mirrored below itself, fading from [REFLECTION_ALPHA] to nothing within [REFLECTION_FRACTION]. */
private fun Modifier.reflection(): Modifier = drawWithContent {
    drawContent()
    val height = size.height
    val depth = height * REFLECTION_FRACTION
    // The layer's bounds are only a hint on some renderers: the clip keeps the mirrored cover to the reflection.
    clipRect(0f, height, size.width, height + depth) {
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(0f, height, size.width, height + depth), Paint())
            withTransform({
                translate(top = 2f * height)
                scale(1f, -1f, pivot = Offset.Zero)
            }) {
                this@drawWithContent.drawContent()
            }
            drawRect(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = REFLECTION_ALPHA), 1f to Color.Transparent, startY = height, endY = height + depth),
                topLeft = Offset(0f, height),
                size = Size(size.width, depth),
                blendMode = BlendMode.DstIn,
            )
            canvas.restore()
        }
    }
}

@Composable
private fun CornerButton(translucent: TranslucentBackdrop, action: VisualCollectionAction, modifier: Modifier) {
    TranslucentSurface(modifier.size(CORNER_BUTTON_SIZE), shape = CircleShape, elevation = 0.dp, darkTheme = true, backdrop = translucent, tinted = false) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = action.description, onClick = action.onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (action.artwork != null) {
                Artwork(
                    action.artwork,
                    Modifier.size(CORNER_ARTWORK_SIZE),
                    RoundedCornerShape(CORNER_ARTWORK_RADIUS),
                    contentDescription = action.description,
                )
            } else {
                Icon(action.icon, contentDescription = action.description, tint = LocalTranslucentContentColors.current.label)
            }
        }
    }
}

/**
 * A translucent capsule in a corner, the same translucency as [CornerButton]: the action's artwork (the placeholder
 * without), then its [caption] on two lines. A line too long for the capsule scrolls sideways, now and then, instead
 * of being cut.
 */
@Composable
private fun CaptionButton(
    translucent: TranslucentBackdrop,
    action: VisualCollectionAction,
    caption: VisualCollectionCaption,
    handoff: VisualCollectionHandoff?,
    modifier: Modifier,
) {
    val spoken = "${caption.label}, ${caption.text}"
    val thumbnailRadius = with(LocalDensity.current) { CAPTION_ARTWORK_RADIUS.toPx() }
    // Another screen grows out of the capsule: it reports where it is (until it leaves) and fades as that screen leaves it.
    val artwork = CAPTION_HEIGHT - CAPTION_ARTWORK_INSET * 2
    TranslucentPill(
        onClick = action.onClick,
        modifier = modifier.handoffCapsule(handoff),
        height = CAPTION_HEIGHT,
        onClickLabel = action.description,
        // The capsule grows to the left so the artwork's corners clear its round end as far as its top and bottom.
        startPadding = pillLeadingInset(CAPTION_HEIGHT, artwork, CAPTION_ARTWORK_RADIUS),
        endPadding = 18.dp,
        darkTheme = true,
        backdrop = translucent,
        tinted = false,
        contentModifier = Modifier.semantics { contentDescription = spoken },
    ) {
        val label = LocalTranslucentContentColors.current.label
        Artwork(action.artwork, Modifier.size(artwork).handoffThumbnail(handoff, thumbnailRadius), RoundedCornerShape(CAPTION_ARTWORK_RADIUS))
        Spacer(Modifier.width(10.dp))
        // The container reads the caption once; the scrolling lines stay out of semantics. The surface reflects them.
        Column(Modifier.clearAndSetSemantics { }.reflected()) {
            Text(
                caption.label,
                style = MaterialTheme.typography.labelSmall,
                color = label.copy(alpha = CAPTION_LABEL_ALPHA),
                maxLines = 1,
                modifier = Modifier.captionMarquee(),
            )
            Text(
                caption.text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = label,
                maxLines = 1,
                modifier = Modifier.captionMarquee(),
            )
        }
    }
}

/** Scrolls a line that does not fit, gently and after a pause, as Apple Music does; one that fits stays still. */
private fun Modifier.captionMarquee(): Modifier = basicMarquee(
    iterations = Int.MAX_VALUE,
    initialDelayMillis = CAPTION_MARQUEE_DELAY_MS,
    repeatDelayMillis = CAPTION_MARQUEE_DELAY_MS,
    spacing = MarqueeSpacing(CAPTION_MARQUEE_SPACING),
    velocity = CAPTION_MARQUEE_VELOCITY,
)

private val CoverShape = RectangleShape
private val CORNER_PADDING = 20.dp

/** The room the centre cover's title and subtitle take at the bottom of a large upright window (their 20 dp margin included). */
private val TITLE_RESERVE = 72.dp
private val CORNER_BUTTON_SIZE = 44.dp
private val CORNER_ARTWORK_SIZE = 28.dp
private val CORNER_ARTWORK_RADIUS = 6.dp
private val CAPTION_HEIGHT = 48.dp
private val CAPTION_ARTWORK_RADIUS = 8.dp

/** The gap above and below the caption's artwork. */
private val CAPTION_ARTWORK_INSET = 6.dp

/** Space kept between a caption and the card on show. */
private val CAPTION_GAP = 8.dp

/** A long caption line waits this long before it scrolls, and again after each pass. */
private const val CAPTION_MARQUEE_DELAY_MS = 1500
private val CAPTION_MARQUEE_SPACING = 32.dp
private val CAPTION_MARQUEE_VELOCITY = 30.dp

/** A caption never shrinks below its artwork... */
private val CAPTION_FLOOR = 48.dp

/** ...nor grows past this, however wide the screen. */
private val CAPTION_CAP = 280.dp
private const val CAPTION_LABEL_ALPHA = 0.7f
private val SettleSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 300f)
private val ScrollSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)

private const val SIDE_STEP_FRACTION = 0.15f
private const val SIDE_SCALE = 0.72f
private const val REFLECTION_FRACTION = VisualCollectionGeometry.REFLECTION_FRACTION
private const val REFLECTION_ALPHA = 0.3f
private const val FLIPPED_SCRIM = 0.5f
/** The covers' camera distance, in dp; a [SharedArtworkFlight] keeps their perspective. */
internal const val CAMERA_DISTANCE = 16f
private const val FLING_FRICTION = 0.45f
private const val FLIP_MS = 400

/** How long the back's translucent surface takes to show through the opaque veil once the card has settled. */
private const val TRANSLUCENT_FADE_MS = 250

/** How close to a cover the strip must be for a tap to count as on the settled centre cover. */
private const val SETTLED = 0.05f
