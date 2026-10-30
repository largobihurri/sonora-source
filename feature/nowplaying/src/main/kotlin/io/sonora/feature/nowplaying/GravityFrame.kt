package io.sonora.feature.nowplaying

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize

/*
 * Gravity mode's frame (T63.8): Now Playing's level groups are laid out in the phone's own portrait frame, whichever way
 * the window is turned, and that frame turns against the window, so a window rotation (to show the visual collection beneath a
 * sideways Now Playing, or back) moves nothing on the display. The level is measured in the same frame.
 */

/** The window's safe [insets] at display rotation [displayRotation] (Surface.ROTATION_*) on the phone's portrait edges. */
internal fun naturalInsets(insets: SafeInsets, displayRotation: Int): SafeInsets = when (displayRotation.mod(4)) {
    // ROTATION_90: the phone turned anticlockwise, its top edge on the window's left.
    1 -> SafeInsets(left = insets.bottom, top = insets.left, right = insets.top, bottom = insets.right)
    2 -> SafeInsets(left = insets.right, top = insets.bottom, right = insets.left, bottom = insets.top)
    3 -> SafeInsets(left = insets.top, top = insets.right, right = insets.bottom, bottom = insets.left)
    else -> insets
}

/**
 * The padding of the portrait frame: the window's own insets while it is upright, remembered, so a turned window (whose
 * system bars moved) lays nothing out again; before the first upright visit, the turned window's insets mapped back.
 */
internal class NaturalInsets {
    private var upright: SafeInsets? = null

    fun padding(live: SafeInsets, displayRotation: Int): SafeInsets {
        if (displayRotation.mod(4) == 0) upright = live
        return upright ?: naturalInsets(live, displayRotation)
    }
}

/** The portrait frame's turn in a window at [displayRotation]: against the window, so it stays put on the display. */
internal fun frameRotation(displayRotation: Int): Float = if (displayRotation == 0) 0f else -90f * displayRotation

/** A level angle in the phone's frame ([degrees]) as seen in a window at [displayRotation]. */
internal fun windowAngle(degrees: Float, displayRotation: Int): Float = degrees - 90f * displayRotation

/** The display rotation (Surface.ROTATION_*) as state, read again on every configuration change. */
@Composable
internal fun rememberDisplayRotation(): IntState {
    val view = LocalView.current
    val state = remember(view) { mutableIntStateOf(view.displayRotation()) }
    LocalConfiguration.current // a rotation is a configuration change: look again
    SideEffect { state.intValue = view.displayRotation() }
    return state
}

/**
 * Lays [content] out in the phone's portrait frame (short side by long side, padded by its portrait safe insets,
 * [NaturalInsets]) centred on the window, turned against the window's [displayRotation] about the window's centre.
 */
@Composable
internal fun GravityFrameLayout(
    displayRotation: IntState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val insets = WindowInsets.safeDrawing
    val memory = remember { NaturalInsets() }
    Layout(content, modifier) { measurables, constraints ->
        displayRotation.intValue // observed: a half turn changes no size
        val rotation = view.displayRotation()
        val root = IntSize(constraints.maxWidth, constraints.maxHeight)
        val live = SafeInsets(
            left = insets.getLeft(this, layoutDirection),
            top = insets.getTop(this),
            right = insets.getRight(this, layoutDirection),
            bottom = insets.getBottom(this),
        )
        val padding = memory.padding(live, rotation)
        val frame = arrangementSize(NowPlayingLayout.Portrait, root)
        val inner = IntSize((frame.width - padding.horizontal).coerceAtLeast(0), (frame.height - padding.vertical).coerceAtLeast(0))
        val placeable = measurables.first().measure(Constraints.fixed(inner.width, inner.height))
        layout(root.width, root.height) {
            val left = (root.width - frame.width) / 2 + padding.left
            val top = (root.height - frame.height) / 2 + padding.top
            // The pivot is the window's centre, which is the frame's.
            val pivotX = if (inner.width > 0) (root.width / 2f - left) / inner.width else 0.5f
            val pivotY = if (inner.height > 0) (root.height / 2f - top) / inner.height else 0.5f
            placeable.placeWithLayer(left, top) {
                rotationZ = frameRotation(rotation)
                transformOrigin = TransformOrigin(pivotX, pivotY)
            }
        }
    }
}
