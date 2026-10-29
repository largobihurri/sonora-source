package io.sonora.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.TranslucentBarCircle
import io.sonora.core.designsystem.translucent.TranslucentBackdrop
import io.sonora.core.designsystem.translucent.LocalTranslucentBackdrop
import io.sonora.core.designsystem.layout.WindowClass
import io.sonora.core.designsystem.motion.prefersReducedMotion
import io.sonora.player.R
import io.sonora.player.navigation.TopLevelRoute
import kotlin.math.roundToInt

/** How long the sidebar takes to slide in or out; the system's animator scale applies. */
private const val REVEAL_MS = 320

/** The tab bar's height, which the top pill keeps. */
internal const val TopPillHeight = 59f

/** The page bars are 52 dp tall: the taller pill rises half the difference so their centres line up. */
internal const val TopChromeRise = -3.5f

/** How far the top pill drops while it fades in or out. */
private const val TopPillTravel = 8f

/**
 * The sidebar's reveal, 0 (gone) to 1 (shown), springing to [target] (see [sidebarRevealTarget]) or switching at once
 * with "Remove animations" on and when the stored choice is first read ([known]): the sidebar then simply starts in
 * its place. It animates under the system's animator scale.
 */
@Composable
internal fun rememberSidebarReveal(target: Float, known: Boolean): Animatable<Float, AnimationVector1D> {
    val context = LocalContext.current
    val reveal = remember { Animatable(target) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(target, known) {
        if (!known) return@LaunchedEffect
        if (!settled || context.prefersReducedMotion()) {
            reveal.snapTo(target)
            settled = true
        } else {
            reveal.animateTo(target, tween(REVEAL_MS, easing = FastOutSlowInEasing))
        }
    }
    return reveal
}

/** Starts the content [inset] px from its start edge (the end edge in right-to-left layouts), at its full size otherwise. */
internal fun Modifier.startInset(inset: Float): Modifier = startInset { inset }

/** As [startInset], with the inset read when the content is laid out, so a value in motion relays it out without recomposing. */
internal fun Modifier.startInset(inset: () -> Float): Modifier = layout { measurable, constraints ->
    val start = inset().roundToInt().coerceIn(0, constraints.maxWidth)
    val width = constraints.maxWidth - start
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(start, 0) }
}

/**
 * The navigation of a large screen, over the content: the translucent sidebar, or, once it is hidden, the top pill with the
 * button that shows it again, and the mini player floating at the bottom of the content area. [navigation] is what
 * settles; [reveal] carries the move between the sidebar and the pill. Shown only while [visible] (Now Playing at rest
 * covers all of it). Its translucency samples [translucent].
 */
@Composable
internal fun TabletNavigation(
    navigation: NavigationChrome,
    visible: Boolean,
    reveal: Animatable<Float, AnimationVector1D>,
    windowClass: WindowClass,
    selected: SidebarDestination,
    selectedTab: TopLevelRoute,
    onSelect: (SidebarDestination) -> Unit,
    onSelectTab: (TopLevelRoute) -> Unit,
    strip: TabStripState,
    onSidebarChange: (Boolean) -> Unit,
    chrome: BottomChromeState,
    miniPlayer: (@Composable () -> Unit)?,
    translucent: TranslucentBackdrop,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    // The chrome over the content (the top pill and the floating mini player) takes the settled inset at once, as the pages do (T13):
    // following the slide meant laying it out, and redrawing its liquid translucency, on every frame of the 320 ms.
    val inset = settledContentInsetPx(navigation, windowClass, density)
    // Composed only while any of it shows, so a settled layout recomposes nothing per frame.
    val sidebarComposed by remember { derivedStateOf { reveal.value > 0f } }
    val pillComposed by remember { derivedStateOf { reveal.value < 1f } }
    val sidebarLiquid by remember { derivedStateOf { sidebarTranslucentLiquid(reveal.value) } }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    if (!visible) return
    CompositionLocalProvider(LocalTranslucentBackdrop provides translucent) {
        Box(modifier.fillMaxSize()) {
            // Over the content area, which the sidebar's reveal moves.
            Box(Modifier.fillMaxSize().startInset(inset)) {
                if (navigation == NavigationChrome.TopPill && pillComposed) {
                    TopNavigation(
                        selected = selectedTab,
                        onSelect = onSelectTab,
                        strip = strip,
                        onShowSidebar = { onSidebarChange(true) },
                        reveal = { reveal.value },
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }
                if (miniPlayer != null) {
                    FloatingChromeLayout(chrome) { miniPlayer() }
                }
            }
            if (sidebarComposed) {
                val width = sidebarWidth(windowClass)
                TranslucentSidebar(
                    selected = selected,
                    onSelect = onSelect,
                    onHide = { onSidebarChange(false) },
                    liquid = sidebarLiquid,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(start = SidebarMargin.dp, top = SidebarMargin.dp, bottom = SidebarMargin.dp)
                        .width(width.dp)
                        .graphicsLayer {
                            // Slides out toward its own edge while it fades.
                            val travel = (width + SidebarMargin) * density
                            translationX = (if (rtl) 1f else -1f) * (1f - reveal.value) * travel
                            alpha = reveal.value
                        },
                )
            }
        }
    }
}

/**
 * The main sections in a translucent pill centred at the top of the content (the phone's tab bar, narrower), with the button
 * that shows the sidebar at its leading side. It fades in as the sidebar leaves ([reveal] 0) and out as it comes back.
 */
@Composable
private fun TopNavigation(
    selected: TopLevelRoute,
    onSelect: (TopLevelRoute) -> Unit,
    strip: TabStripState,
    onShowSidebar: () -> Unit,
    reveal: () -> Float,
    modifier: Modifier = Modifier,
) {
    val tabs = TopLevelRoute.entries
    // Sized for the window's full width, where the pill ends up; the content only grows toward it as the sidebar leaves.
    val geometry = topPillGeometry(tabs.size, LocalConfiguration.current.screenWidthDp.toFloat())
    val density = LocalDensity.current.density
    Layout(
        content = {
            TranslucentBarCircle(SidebarIcon, stringResource(R.string.sidebar_show), onShowSidebar)
            TranslucentTabBar(
                tabs = tabs,
                selected = selected,
                onSelect = onSelect,
                strip = strip,
                geometry = geometry,
                collapse = { 0f },
                collapsed = false,
                onExpand = {},
                modifier = Modifier.size(geometry.barWidth.dp, TopPillHeight.dp),
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .offset(y = TopChromeRise.dp)
            .graphicsLayer {
                alpha = 1f - reveal()
                translationY = -TopPillTravel * density * reveal()
            },
    ) { (toggle, pill), constraints ->
        val layout = topChromeLayout(constraints.maxWidth.toDp().value, geometry.barWidth)
        val loose = Constraints()
        val pillPlaceable = pill.measure(loose)
        val togglePlaceable = toggle.measure(loose)
        layout(constraints.maxWidth, pillPlaceable.height) {
            pillPlaceable.placeRelative(layout.pillX.dp.roundToPx(), 0)
            togglePlaceable.placeRelative(layout.toggleX.dp.roundToPx(), (pillPlaceable.height - togglePlaceable.height) / 2)
        }
    }
}

/**
 * Lays the one child out as the floating mini player ([floatingMiniPlayerFrame]) at the bottom of its parent, centred
 * and capped in width. The frame is read during layout, so a content area moving with the sidebar relays it out without
 * recomposing.
 */
@Composable
private fun FloatingChromeLayout(state: BottomChromeState, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val frame = floatingMiniPlayerFrame(constraints.maxWidth.toDp().value, state.navigationBar)
        val placeable = measurables.firstOrNull()?.measure(
            Constraints.fixed(frame.width.dp.roundToPx(), frame.height.dp.roundToPx()),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable?.placeRelative(frame.x.dp.roundToPx(), constraints.maxHeight - frame.top.dp.roundToPx())
        }
    }
}
