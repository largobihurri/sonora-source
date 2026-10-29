package io.sonora.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.BottomChrome
import io.sonora.core.designsystem.motion.prefersReducedMotion
import kotlinx.coroutines.flow.collectLatest

/** Net scroll toward the content's end that minimises the chrome, and toward its start that expands it. */
private val CollapseAfter = 24.dp
private val ExpandAfter = 12.dp

/** Space on each side of the expanded tab bar. */
private const val TabBarSideMargin = 24f

/**
 * The app shell's bottom chrome: one [ChromeCollapse] fed by every screen's scrolling through a root nested scroll
 * connection, the morph [progress] (0 expanded, 1 collapsed) springing after it, and the content padding that follows
 * the morph. Set [navigationBar] (dp) and [hasMiniPlayer] from the shell.
 */
@Stable
internal class BottomChromeState(collapseAfterPx: Float, expandAfterPx: Float) : BottomChrome, NestedScrollConnection {
    private val collapse = ChromeCollapse(collapseAfterPx, expandAfterPx)

    /** The target state; [progress] animates toward it. */
    var collapsed by mutableStateOf(false)
        private set

    val progress = Animatable(0f)

    var navigationBar by mutableFloatStateOf(0f)
    var hasMiniPlayer by mutableStateOf(false)

    /** A large screen: only the mini player floats at the bottom of the content, with no tab bar under it. */
    var floating by mutableStateOf(false)

    override val contentPadding: Dp
        // The chrome's height does not depend on the widths, so none are needed here.
        get() = if (floating) {
            floatingContentPadding(hasMiniPlayer, navigationBar).dp
        } else {
            bottomChromeFrame(progress.value, 0f, navigationBar, 0f, hasMiniPlayer).contentPadding.dp
        }

    override fun expand() {
        if (collapse.expand()) collapsed = false
    }

    override fun onFastScroll(deltaPx: Float) {
        // Nested scroll signs a finger moving up (content toward its end) negative.
        if (collapse.onScroll(consumed = -deltaPx, left = 0f)) collapsed = collapse.collapsed
    }

    /** The screen reported last; the same one again (its header scrolled back into its list) is no change. */
    private var shownScreen: Any? = null

    override fun onScreenShown(screen: Any) {
        if (screen == shownScreen) return
        shownScreen = screen
        collapse.onScreenChanged()
    }

    override fun onScreenLeft(screen: Any) {
        if (screen == shownScreen) shownScreen = null
        collapse.onScreenChanged()
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (collapse.onScroll(consumed = consumed.y, left = available.y)) {
            collapsed = collapse.collapsed
        }
        return Offset.Zero
    }
}

/** A [BottomChromeState] whose morph springs to each new state, or switches at once with "Remove animations" on. */
@Composable
internal fun rememberBottomChromeState(): BottomChromeState {
    val density = LocalDensity.current
    val context = LocalContext.current
    val state = remember(density) {
        with(density) { BottomChromeState(CollapseAfter.toPx(), ExpandAfter.toPx()) }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.collapsed }.collectLatest { collapsed ->
            val target = if (collapsed) 1f else 0f
            if (context.prefersReducedMotion()) {
                state.progress.snapTo(target)
            } else {
                state.progress.animateTo(target, spring(dampingRatio = 0.86f, stiffness = 380f))
            }
        }
    }
    return state
}

/**
 * Lays out the [tabBar] and the optional [miniPlayer] at the [state]'s current point of the morph
 * ([bottomChromeFrame]), each at an exact size. The morph is read during layout only, so an animating frame re-lays
 * out the chrome without recomposing it. The tab bar gets the expanded [TabBarGeometry] for [tabCount] tabs.
 */
@Composable
internal fun MorphingChrome(
    state: BottomChromeState,
    tabCount: Int,
    modifier: Modifier = Modifier,
    tabBar: @Composable (TabBarGeometry) -> Unit,
    miniPlayer: (@Composable () -> Unit)?,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val geometry = tabBarGeometry(tabCount, maxWidth.value - 2 * TabBarSideMargin)
        Layout(
            content = {
                tabBar(geometry)
                miniPlayer?.invoke()
            },
        ) { measurables, constraints ->
            val frame = bottomChromeFrame(
                collapse = state.progress.value,
                screenWidth = constraints.maxWidth.toDp().value,
                navigationBar = state.navigationBar,
                tabBarWidth = geometry.barWidth,
                hasMiniPlayer = miniPlayer != null,
            )
            val height = frame.height.dp.roundToPx()
            val placeables = measurables.zip(listOfNotNull(frame.tabBar, frame.miniPlayer)) { measurable, rect ->
                measurable.measure(Constraints.fixed(rect.width.dp.roundToPx(), rect.height.dp.roundToPx())) to rect
            }
            layout(constraints.maxWidth, height) {
                placeables.forEach { (placeable, rect) ->
                    placeable.placeRelative(rect.x.dp.roundToPx(), height - rect.top.dp.roundToPx())
                }
            }
        }
    }
}
