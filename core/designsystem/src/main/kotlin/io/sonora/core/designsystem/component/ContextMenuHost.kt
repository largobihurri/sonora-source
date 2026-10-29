package io.sonora.core.designsystem.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import io.sonora.core.designsystem.translucent.TranslucentBackdrop
import io.sonora.core.designsystem.translucent.LocalTranslucentPolicy
import io.sonora.core.designsystem.translucent.translucentBackdropSource
import io.sonora.core.designsystem.translucent.rememberTranslucentBackdrop
import io.sonora.core.designsystem.layout.LocalWindowClass
import io.sonora.core.designsystem.motion.prefersReducedMotion
import kotlin.math.max
import kotlin.math.min

/** A long-pressed element in window coordinates and where the finger was ([touchX], [touchY]). */
data class MenuAnchor(val bounds: PxRect, val touchX: Float, val touchY: Float)

/** What a context menu shows: the lifted preview [card] and the [menu] under it, which closes through its scope. */
class ContextMenuContent(
    val card: @Composable () -> Unit,
    val menu: @Composable (ContextMenuScope) -> Unit,
)

/** Lets menu items close the menu. */
fun interface ContextMenuScope {
    /** Closes the menu; [then] runs once it has shrunk back into the element (navigation, sheets). */
    fun close(then: (() -> Unit)?)
}

/**
 * The app's one context menu: [show] lifts the element long-pressed last (see [rememberMenuAnchorSource]) into a
 * preview card with a menu under it, over the blurred and dimmed app.
 */
@Stable
class ContextMenuHostState internal constructor() {
    internal var content by mutableStateOf<ContextMenuContent?>(null)
    internal var anchor by mutableStateOf<MenuAnchor?>(null)
    internal var open by mutableStateOf(false)
    internal var afterClose: (() -> Unit)? = null
    internal var lastAnchor: MenuAnchor? = null

    /** The centred panel ([TranslucentPanel]) shown over the same blurred app; independent of the menu. */
    internal val panel = TranslucentPanelSlot()

    /** Whether a menu is on screen (opening, open or closing). */
    val isShowing: Boolean get() = content != null

    fun show(content: ContextMenuContent) {
        // A menu still closing with an action chosen (Go to Album, Remove…) when another opens: the action runs now
        // instead of being dropped with the closing menu.
        afterClose?.also { afterClose = null }?.invoke()
        anchor = lastAnchor
        lastAnchor = null
        this.content = content
        open = true
    }

    fun dismiss(then: (() -> Unit)? = null) {
        if (content == null) return
        if (then != null) afterClose = then
        open = false
    }

    internal fun finished() {
        content = null
        anchor = null
        afterClose?.also { afterClose = null }?.invoke()
    }
}

/** The [ContextMenuHostState] of the enclosing [ContextMenuHost]; `null` outside one. */
val LocalContextMenuHost = staticCompositionLocalOf<ContextMenuHostState?> { null }

/** An element a context menu can grow from: put [modifier] on it and call [capture] when it is long-pressed. */
class MenuAnchorSource internal constructor(private val host: ContextMenuHostState?) {
    private var coordinates: LayoutCoordinates? = null
    private var touch: Offset? = null

    val modifier: Modifier = if (host == null) {
        Modifier
    } else {
        Modifier
            .onGloballyPositioned { coordinates = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Observed on the way down, never consumed: the element's own click handling stays as it is.
                    touch = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position
                }
            }
    }

    /** Remembers this element (and the finger on it) as where the next menu grows from. */
    fun capture() {
        val coordinates = coordinates?.takeIf { it.isAttached } ?: return
        val bounds = coordinates.boundsInWindow()
        val finger = touch?.let(coordinates::localToWindow) ?: bounds.center
        host?.lastAnchor = MenuAnchor(PxRect(bounds.left, bounds.top, bounds.right, bounds.bottom), finger.x, finger.y)
        touch = null
    }
}

@Composable
fun rememberMenuAnchorSource(): MenuAnchorSource {
    val host = LocalContextMenuHost.current
    return remember(host) { MenuAnchorSource(host) }
}

/** Provides a [ContextMenuHost] around [content] unless one already encloses it (the app shell normally does). */
@Composable
fun ProvideContextMenuHost(content: @Composable () -> Unit) {
    if (LocalContextMenuHost.current != null) content() else ContextMenuHost(Modifier.fillMaxSize(), content)
}

private val CardMaxWidth = 420.dp
private val MenuWidth = 236.dp
private val ScreenMargin = 12.dp
private val CardMenuGap = 10.dp

/**
 * Hosts the app's context menu over [content]: while a menu shows, the content is blurred (Android 12+, not on a large window,
 * see [overlayStyle]) and dimmed,
 * the card springs from the long-pressed element to its place and the translucent menu grows under it from the
 * finger's side; tapping outside or Back closes both back into the element. "Remove animations" skips the motion.
 * It also shows any [TranslucentPanel] composed inside it, centred over the same blurred app.
 */
@Composable
fun ContextMenuHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = remember { ContextMenuHostState() }
    val progress = remember { Animatable(0f) }
    val context = LocalContext.current
    val shown = state.content
    LaunchedEffect(shown, state.open) {
        if (shown == null) return@LaunchedEffect
        progress.animateTranslucentOverlay(state.open, context.prefersReducedMotion())
        if (!state.open) state.finished()
    }
    val panelProgress = remember { Animatable(0f) }
    val panel = state.panel.entry
    LaunchedEffect(panel, state.panel.open) {
        if (panel == null) return@LaunchedEffect
        panelProgress.animateTranslucentOverlay(state.panel.open, context.prefersReducedMotion())
        if (!state.panel.open) state.panel.finished()
    }
    val translucent = rememberTranslucentBackdrop()
    val overlaid = shown != null || panel != null
    val style = overlayStyle(LocalTranslucentPolicy.current, LocalWindowClass.current.isLarge)
    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                // Recorded around the blur, so the translucency samples the blurred app: what is behind a panel shows
                // through it as soft colour, never as readable text (U10).
                .translucentBackdropSource(translucent, enabled = overlaid)
                .translucentOverlayBlur(overlaid, style.blurs) { max(progress.value, panelProgress.value) },
        ) {
            CompositionLocalProvider(LocalContextMenuHost provides state) { content() }
        }
        if (shown != null) {
            BackHandler(enabled = state.open) { state.dismiss() }
            ContextMenuLayer(state, shown, progress, translucent, style.dimAlpha)
        }
        if (panel != null) {
            BackHandler(enabled = state.panel.open) { state.panel.requestDismiss() }
            TranslucentPanelLayer(state.panel, panel, panelProgress, translucent, style.dimAlpha)
        }
    }
}

@Composable
private fun ContextMenuLayer(state: ContextMenuHostState, shown: ContextMenuContent, progress: Animatable<Float, *>, translucent: TranslucentBackdrop, dimAlpha: Float) {
    val insets = WindowInsets.safeDrawing
    val view = LocalView.current
    val scope = remember(state) { ContextMenuScope { then -> state.dismiss(then) } }
    var hostWindowOffset by remember { mutableStateOf(Offset.Zero) }
    val closing = { !state.open }
    val lightShift = { overlayLightShift(progress.value) }
    TranslucentOverlayScrim(
        dimAlpha = dimAlpha,
        progress = { progress.value },
        onTapOutside = { state.dismiss() },
        closing = closing,
        modifier = Modifier.onGloballyPositioned { hostWindowOffset = it.localToWindow(Offset.Zero) },
    )
    // Closing, the card and the menu take no more touches: a second tap on an item must not pick another action.
    Layout(
        contents = listOf(
            { ContextMenuTranslucent(translucent, lightShift) { Box(Modifier.inertWhile(closing)) { shown.card() } } },
            { ContextMenuTranslucent(translucent, lightShift) { Box(Modifier.inertWhile(closing)) { shown.menu(scope) } } },
        ),
    ) { (cardMeasurables, menuMeasurables), constraints ->
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val margin = ScreenMargin.toPx()
        val gap = CardMenuGap.toPx()
        val area = hostOverlayArea(insets, hostWindowOffset, constraints, view)
        val cardWidth = min(CardMaxWidth.toPx(), area.width - 2 * margin).toInt()
        val card = cardMeasurables.first().measure(Constraints.fixedWidth(cardWidth))
        val menuWidth = min(MenuWidth.toPx(), area.width - 2 * margin).toInt()
        val menuMax = contextMenuMaxHeight(area, card.height.toFloat(), margin, gap).toInt()
        val menu = menuMeasurables.first().measure(Constraints(minWidth = menuWidth, maxWidth = menuWidth, maxHeight = menuMax))
        // The anchor is in window coordinates; this layer may sit lower (a host inside a tab).
        val anchor = state.anchor?.let {
            MenuAnchor(
                PxRect(it.bounds.left - hostWindowOffset.x, it.bounds.top - hostWindowOffset.y, it.bounds.right - hostWindowOffset.x, it.bounds.bottom - hostWindowOffset.y),
                it.touchX - hostWindowOffset.x,
                it.touchY - hostWindowOffset.y,
            )
        } ?: MenuAnchor(PxRect(width / 2f, height / 2f, width / 2f, height / 2f), width / 2f, height / 2f)
        val placed = placeContextMenu(
            anchor.bounds, anchor.touchX, card.width.toFloat(), card.height.toFloat(), menu.width.toFloat(),
            menu.height.toFloat(), area, margin, gap,
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            card.placeWithLayer(placed.cardLeft.toInt(), placed.cardTop.toInt()) {
                val p = progress.value
                // Uniform scale from the element's size, centred on it, so text never squashes.
                val startScale = (anchor.bounds.width / card.width).coerceIn(0.2f, 1.2f)
                val scale = lerp(startScale, 1f, p)
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 0.5f)
                translationX = lerp(anchor.bounds.centerX - (placed.cardLeft + card.width / 2f), 0f, p)
                translationY = lerp(anchor.bounds.centerY - (placed.cardTop + card.height / 2f), 0f, p)
                alpha = (p / 0.35f).coerceIn(0f, 1f)
            }
            menu.placeWithLayer(placed.menuLeft.toInt(), placed.menuTop.toInt()) {
                val p = progress.value
                val scale = lerp(OVERLAY_START_SCALE, 1f, p)
                scaleX = scale
                scaleY = scale
                val pivotX = ((anchor.touchX - placed.menuLeft) / menu.width).coerceIn(0.1f, 0.9f)
                transformOrigin = TransformOrigin(pivotX, if (placed.menuAbove) 1f else 0f)
                alpha = (p / OVERLAY_FADE_END).coerceIn(0f, 1f)
            }
        }
    }
}
