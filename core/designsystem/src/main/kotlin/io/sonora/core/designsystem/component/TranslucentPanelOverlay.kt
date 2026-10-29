package io.sonora.core.designsystem.component

import android.os.Build
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import io.sonora.core.designsystem.translucent.TranslucentBackdrop
import io.sonora.core.designsystem.translucent.TranslucentMaterial
import io.sonora.core.designsystem.translucent.TranslucentPolicy
import io.sonora.core.designsystem.translucent.TranslucentTier
import kotlin.math.max
import kotlin.math.min

/*
 * The treatment the app's floating translucent overlays share (the long-press context menu and centred panels such as File
 * Info): the app behind is blurred (Android 12+) and dimmed, a translucent panel springs in over it, and tapping
 * outside or Back closes it.
 */

private val OverlayBlurRadius = 28.dp

/**
 * Whether the app under an overlay is blurred: behind liquid and frosted translucency only. Flat translucency (the Translucency
 * switch off, Android 11 and older, high-contrast text, or a GPU that could not keep up) only dims it, more strongly:
 * the blur is redrawn over the whole app on every frame of the overlay's motion, the cost the flat tier is there to save.
 */
internal fun overlayBlurs(policy: TranslucentPolicy): Boolean = policy.tier != TranslucentTier.FLAT

/** What an overlay does to the app under it: [blurs] it (and dims it by [dimAlpha]), or only dims it. */
internal data class OverlayStyle(val blurs: Boolean, val dimAlpha: Float)

/**
 * The treatment under [policy] for a window that is [large] or not: flat translucency only dims the app ([overlayBlurs]),
 * more strongly ([overlayDimAlpha]). A large window never blurs it either (T13): blurring a whole 2880 x 1800 px app means
 * rendering it into a layer of its own and blurring that again on every frame of the overlay's opening and closing, about
 * 30 % janky frames on the Pad 6 against 4 % without the blur; the app is dimmed a little more instead, and the panel's own
 * translucency still blurs what is behind it.
 */
internal fun overlayStyle(policy: TranslucentPolicy, large: Boolean): OverlayStyle = when {
    !overlayBlurs(policy) -> OverlayStyle(blurs = false, dimAlpha = overlayDimAlpha(blurs = false))
    large -> OverlayStyle(blurs = false, dimAlpha = LARGE_DIM_ALPHA)
    else -> OverlayStyle(blurs = true, dimAlpha = overlayDimAlpha(blurs = true))
}

private const val LARGE_DIM_ALPHA = 0.4f

/**
 * What an overlay's panel stands on under [policy] in a [dark] theme: nothing over the blur; over flat translucency an opaque
 * backing in the panel's own tint, as iOS does with Reduce Transparency, because nothing blurs the app under the panel
 * and its text would read through the translucent flat fill (even at the high-contrast 0.92).
 */
internal fun overlayPanelBacking(policy: TranslucentPolicy, dark: Boolean): Color? =
    if (overlayBlurs(policy)) null else Color(TranslucentMaterial.of(dark, TranslucentTier.FLAT, policy.highContrast).tint)

/** The dim over the app under an overlay: light over a blur, stronger when the dim alone sets the panel apart. */
internal fun overlayDimAlpha(blurs: Boolean): Float = if (blurs) 0.22f else 0.5f

/** Where the panel grows from as it opens; it fades in over the first half of the motion. */
internal const val OVERLAY_START_SCALE = 0.35f
internal const val OVERLAY_FADE_END = 0.5f

/** How far the light swings per unit of opening left: it reaches its full swing as the panel becomes visible. */
private const val OVERLAY_LIGHT_SWEEP = 1f / (1f - OVERLAY_FADE_END)

/**
 * The specular light shift (-1..1, see [io.sonora.core.designsystem.translucent.TranslucentPolicy.specularAngle]) of an overlay's
 * panel at open [progress]: fully swung while it fades in, sweeping back to rest as it opens, a little past rest with
 * the spring's overshoot, so the material catches the light as it lands. Reduced motion holds the light still.
 */
internal fun overlayLightShift(progress: Float): Float = ((1f - progress) * OVERLAY_LIGHT_SWEEP).coerceIn(-1f, 1f)

/** Opens ([open]) or closes the overlay [progress] (0 hidden, 1 shown) with a springy entry and a calm exit. */
internal suspend fun Animatable<Float, AnimationVector1D>.animateTranslucentOverlay(open: Boolean, reducedMotion: Boolean) {
    val target = if (open) 1f else 0f
    when {
        reducedMotion -> snapTo(target)
        open -> animateTo(target, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow))
        else -> animateTo(target, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium))
    }
}

/**
 * Blurs the app under an overlay as it opens ([progress]) when the overlay [blurs] (see [overlayStyle]); nothing while no
 * overlay is [active]. The overlay's panel samples this blurred app (the backdrop is recorded around this layer), so a
 * panel shows what is behind it as soft colour, never as readable text.
 */
internal fun Modifier.translucentOverlayBlur(active: Boolean, blurs: Boolean, progress: () -> Float): Modifier = graphicsLayer {
    val p = progress().coerceIn(0f, 1f)
    val radius = OverlayBlurRadius.toPx() * p
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    renderEffect = if (supported && blurs && active && p > 0.01f) BlurEffect(radius, radius, OverlayBlurEdges) else null
}

/**
 * How the overlay blur treats the app's edges: it repeats them, as the queue blur does, so a panel near the host's edge
 * samples the app there, not a blur fading to transparent (S1).
 */
internal val OverlayBlurEdges = TileMode.Clamp

/**
 * The dim of [dimAlpha] (see [overlayStyle]) over the blurred app; tapping it calls [onTapOutside], except while the overlay is [closing]. It keeps
 * taking touches during the exit motion, so nothing under it is hit before the overlay is gone.
 */
@Composable
internal fun TranslucentOverlayScrim(
    dimAlpha: Float,
    progress: () -> Float,
    onTapOutside: () -> Unit,
    closing: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val latestTap by rememberUpdatedState(onTapOutside)
    val latestClosing by rememberUpdatedState(closing)
    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { if (!latestClosing()) latestTap() } }
            .drawBehind { drawRect(Color.Black.copy(alpha = progress().coerceIn(0f, 1f) * dimAlpha)) },
    )
}

/**
 * While [closing], swallows every touch before the content under this modifier sees it, so a closing overlay's
 * buttons and rows never fire again during the exit motion (a press already under way is cancelled).
 */
internal fun Modifier.inertWhile(closing: () -> Boolean): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (closing()) event.changes.forEach { it.consume() }
        }
    }
}

/**
 * What a panel shows, who asked for it ([owner]) and what closing it asks for ([onDismiss]); with an [anchor] (window
 * coordinates) it is a pull-down menu hanging from it rather than a centred panel. A [maxWidth] narrows a centred
 * panel (an alert); a [height] of [TranslucentPanelHeight.Tall] makes it fill most of the screen.
 */
internal class TranslucentPanelEntry(
    val owner: Any,
    val title: () -> String,
    val anchor: () -> PxRect?,
    val content: @Composable () -> Unit,
    val onDismiss: () -> Unit,
    val maxWidth: Dp? = null,
    val height: TranslucentPanelHeight = TranslucentPanelHeight.Wrap,
)

/**
 * The centred panels an overlay host shows; the menu of a [ContextMenuHostState] is separate. Panels stack: one shown
 * while another is up (an alert asked for by a panel) covers it, and the covered one waits, fully shown, until the
 * top one has closed. Only the top one ([entry]) opens, closes and is dismissed.
 */
@Stable
internal class TranslucentPanelSlot {
    private val stack = mutableStateListOf<TranslucentPanelEntry>()
    private val revealed = mutableStateListOf<TranslucentPanelEntry>()

    /** The top panel, the one that takes touches and Back. */
    val entry: TranslucentPanelEntry? get() = stack.lastOrNull()

    /** Whether the top panel is open (false while it closes). */
    var open by mutableStateOf(false)
        private set

    /** The panels under the top one, bottom first. */
    val covered: List<TranslucentPanelEntry> get() = if (stack.size > 1) stack.subList(0, stack.size - 1).toList() else emptyList()

    /** Whether [entry] is back on top after a panel over it closed: it stays as it was, with no second entry motion. */
    fun isRevealed(entry: TranslucentPanelEntry): Boolean = entry in revealed

    fun show(entry: TranslucentPanelEntry) {
        // The top panel still closing gives way at once.
        if (!open) stack.removeLastOrNull()?.let(revealed::remove)
        stack += entry
        open = true
    }

    /** Starts closing the panel of [owner] when it is on top; a covered one goes at once, one already gone stays so. */
    fun hide(owner: Any) {
        val top = stack.lastOrNull() ?: return
        if (top.owner === owner) {
            open = false
        } else {
            stack.filter { it.owner === owner }.forEach {
                stack.remove(it)
                revealed.remove(it)
            }
        }
    }

    fun requestDismiss() {
        if (open) entry?.onDismiss?.invoke()
    }

    /** Called once the top panel's exit motion ends: the panel under it, if any, is on top again. */
    fun finished() {
        if (open) return
        stack.removeLastOrNull()?.let(revealed::remove)
        stack.lastOrNull()?.let {
            revealed += it
            open = true
        }
    }
}

private val PanelMaxWidth = 420.dp
private val PanelLandscapeMaxWidth = 560.dp
private val PanelMargin = 12.dp
private val PullDownWidth = 260.dp
private val PullDownGap = 6.dp

/**
 * A floating translucent panel centred over the blurred, dimmed app, in the same material and motion as the
 * long-press context menu. It shows while this is composed: leaving composition closes it with the exit motion, so
 * the caller decides when it is open. Tapping outside and Back call [onDismiss]. [title] names it for accessibility.
 *
 * [content] is laid out at the panel's width and at most its maximum height, so it must scroll itself when long. It
 * is composed by the enclosing [ContextMenuHost] (the app shell's), above everything else in it. With [height] set to
 * [TranslucentPanelHeight.Tall] (not for an anchored menu) the panel is always about 94% of the screen's height and [content]
 * fills it: give it `Modifier.fillMaxHeight()` and a scrolling child `weight(1f)`. [TranslucentPanelHeight.Expanded] lets it
 * grow that tall but wraps [content], for a control surface that sizes its parts to the room it gets.
 *
 * With an [anchor] (a button's bounds in window coordinates) it is instead an iOS pull-down menu: the context menu's
 * width, hanging under the button with the right edges lined up, growing out of it.
 */
@Composable
fun TranslucentPanel(
    title: String,
    onDismiss: () -> Unit,
    anchor: PxRect? = null,
    height: TranslucentPanelHeight = TranslucentPanelHeight.Wrap,
    content: @Composable () -> Unit,
) {
    ShowTranslucentPanel(title, onDismiss, anchor, maxWidth = null, height = height, content = content)
}

/** [TranslucentPanel], centred and at most [maxWidth] wide when given (an alert is narrower than a panel). */
@Composable
internal fun ShowTranslucentPanel(
    title: String,
    onDismiss: () -> Unit,
    anchor: PxRect?,
    maxWidth: Dp?,
    height: TranslucentPanelHeight = TranslucentPanelHeight.Wrap,
    content: @Composable () -> Unit,
) {
    val host = LocalContextMenuHost.current
    if (host == null) {
        // Outside the shell (previews, tests): a host of its own, which blurs nothing but still dims and floats it.
        ContextMenuHost(Modifier.fillMaxSize()) { ShowTranslucentPanel(title, onDismiss, anchor, maxWidth, height, content) }
        return
    }
    val owner = remember { Any() }
    val latestTitle by rememberUpdatedState(title)
    val latestAnchor by rememberUpdatedState(anchor)
    val latestContent by rememberUpdatedState(content)
    val latestDismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(host, owner) {
        host.panel.show(TranslucentPanelEntry(owner, { latestTitle }, { latestAnchor }, { latestContent() }, { latestDismiss() }, maxWidth, height))
        onDispose { host.panel.hide(owner) }
    }
}

/**
 * The dim and the panels of [slot]: the top one ([entry]) as [progress] runs, centred and springing from the middle
 * of the screen, or, with an anchor, a pull-down growing from its button. Covered panels stay fully shown under a
 * second dim that takes the taps outside the top one.
 */
@Composable
internal fun TranslucentPanelLayer(slot: TranslucentPanelSlot, entry: TranslucentPanelEntry, progress: Animatable<Float, *>, translucent: TranslucentBackdrop, dimAlpha: Float) {
    // Anchors are in window coordinates; this layer may sit lower (a host inside a tab).
    var hostWindowOffset by remember { mutableStateOf(Offset.Zero) }
    val closing = { !slot.open }
    val revealed = slot.isRevealed(entry)
    // A panel back on top after an alert stays where it was until it closes.
    val topProgress = { if (revealed && slot.open) 1f else progress.value }
    val covered = slot.covered
    TranslucentOverlayScrim(
        dimAlpha = dimAlpha,
        progress = if (covered.isEmpty()) topProgress else FullyShown,
        onTapOutside = slot::requestDismiss,
        closing = closing,
        modifier = Modifier.onGloballyPositioned { hostWindowOffset = it.localToWindow(Offset.Zero) },
    )
    for (panel in covered + entry) {
        // Keyed by owner: a panel keeps its state (its scroll) while covered and once on top again.
        key(panel.owner) {
            if (panel === entry) {
                if (covered.isNotEmpty()) TranslucentOverlayScrim(dimAlpha, topProgress, onTapOutside = slot::requestDismiss, closing = closing)
                PanelTranslucent(panel, topProgress, closing, translucent) { hostWindowOffset }
            } else {
                PanelTranslucent(panel, FullyShown, closing = { true }, translucent) { hostWindowOffset }
            }
        }
    }
}

private val FullyShown: () -> Float = { 1f }

/** One panel on its translucent surface, placed and scaled as [progress] runs; it takes no touches while [closing]. */
@Composable
private fun PanelTranslucent(
    entry: TranslucentPanelEntry,
    progress: () -> Float,
    closing: () -> Boolean,
    translucent: TranslucentBackdrop,
    hostWindowOffset: () -> Offset,
) {
    val insets = WindowInsets.safeDrawing
    val view = LocalView.current
    Layout(
        content = {
            ContextMenuTranslucent(translucent, lightShift = { overlayLightShift(progress()) }) {
                // Closing, the panel takes no more touches: an action tapped twice runs once.
                Box(Modifier.semantics { paneTitle = entry.title() }.inertWhile(closing)) { entry.content() }
            }
        },
    ) { measurables, constraints ->
        val offset = hostWindowOffset()
        // The insets are the window's; a host inside a sheet already ends above the keyboard (G5, the tag editor).
        val area = hostOverlayArea(insets, offset, constraints, view)
        val margin = PanelMargin.toPx()
        val anchor = entry.anchor()?.let {
            PxRect(it.left - offset.x, it.top - offset.y, it.right - offset.x, it.bottom - offset.y)
        }
        val panel: Placeable
        val left: Float
        val top: Float
        val origin: TransformOrigin
        if (anchor == null) {
            val size = translucentPanelSize(area, margin, PanelMaxWidth.toPx(), PanelLandscapeMaxWidth.toPx(), entry.height)
            val width = min(size.width, entry.maxWidth?.toPx() ?: size.width).toInt()
            panel = measurables.first().measure(
                Constraints(minWidth = width, maxWidth = width, minHeight = size.minHeight.toInt(), maxHeight = size.maxHeight.toInt()),
            )
            val corner = translucentPanelOrigin(area, panel.width.toFloat(), panel.height.toFloat())
            left = corner.first
            top = corner.second
            origin = TransformOrigin.Center
        } else {
            val gap = PullDownGap.toPx()
            val width = max(0f, min(PullDownWidth.toPx(), area.width - 2 * margin)).toInt()
            val maxHeight = pullDownMaxHeight(anchor, area, margin, gap).toInt()
            panel = measurables.first().measure(Constraints(minWidth = width, maxWidth = width, maxHeight = maxHeight))
            val placed = placePullDown(anchor, panel.width.toFloat(), panel.height.toFloat(), area, margin, gap)
            left = placed.left
            top = placed.top
            // Grows out of the button, as the context menu grows from the finger's side.
            origin = TransformOrigin(((anchor.centerX - left) / panel.width).coerceIn(0.1f, 0.9f), if (placed.below) 0f else 1f)
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            panel.placeWithLayer(left.toInt(), top.toInt()) {
                val p = progress()
                val scale = lerp(OVERLAY_START_SCALE, 1f, p)
                scaleX = scale
                scaleY = scale
                transformOrigin = origin
                alpha = (p / OVERLAY_FADE_END).coerceIn(0f, 1f)
            }
        }
    }
}

/**
 * The part of an overlay host that the window's [insets] leave free ([overlayArea]), in the host's coordinates: the
 * host fills [constraints] at [hostOffset] in [view]'s window. Insets are the window's, so a host that does not fill it
 * (one inside a sheet, under its header and above the keyboard) loses only what reaches into it.
 */
internal fun MeasureScope.hostOverlayArea(insets: WindowInsets, hostOffset: Offset, constraints: Constraints, view: View): PxRect {
    val host = PxRect(hostOffset.x, hostOffset.y, hostOffset.x + constraints.maxWidth, hostOffset.y + constraints.maxHeight)
    val window = view.rootView
    // Not laid out yet (or no window): treat the host as filling it, as before.
    val windowWidth = if (window.width > 0) window.width.toFloat() else host.right
    val windowHeight = if (window.height > 0) window.height.toFloat() else host.bottom
    val edges = EdgeInsets(
        insets.getLeft(this, layoutDirection).toFloat(),
        insets.getTop(this).toFloat(),
        insets.getRight(this, layoutDirection).toFloat(),
        insets.getBottom(this).toFloat(),
    )
    return overlayArea(host, windowWidth, windowHeight, edges)
}
