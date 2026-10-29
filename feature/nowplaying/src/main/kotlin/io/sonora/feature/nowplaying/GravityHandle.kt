package io.sonora.feature.nowplaying

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import io.sonora.core.designsystem.motion.SheetPresentation
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Gravity mode's drag handle and dismissal (T63.6): the handle does not turn with the artwork. One handle per quarter
 * turn, each drawn upright for it, cross-fades with the level angle: the one at the top of the phone (upright) and one
 * just beyond the artwork's gravity-up edge for each other quarter turn. Now Playing is dismissed toward the floor.
 */

/** How far from its own quarter turn a handle has faded out completely. */
internal const val HANDLE_FADE_DEGREES = 30f

/** A handle's layer: its centre (in the artwork group's area), its turn and its opacity. */
internal data class HandleLayer(val center: Offset, val rotation: Float, val alpha: Float)

/** How much of the handle for [quarter] (0 upright .. 3) shows with the groups hanging at [degrees]. */
internal fun handleAlpha(quarter: Int, degrees: Float): Float =
    (1f - abs(wrapDegrees(degrees - quarter * 90f)) / HANDLE_FADE_DEGREES).coerceIn(0f, 1f)

/**
 * The handle for [quarter] (1 .. 3) with the groups at [degrees]: centred [offset] beyond the gravity-up edge of the
 * [artwork] (laid out in [area]) as it hangs at that quarter turn, turned and fitted there ([levelFitScale]), and drawn
 * upright for it. Its place depends only on its quarter turn, so as the phone tilts it fades ([handleAlpha]) without
 * moving.
 */
internal fun handleLayer(quarter: Int, artwork: Rect, area: Rect, degrees: Float, offset: Float): HandleLayer {
    val turn = quarter * 90f
    val reach = artwork.height / 2f * levelFitScale(artwork, area, turn) + offset
    // Straight up from the artwork's centre, turned clockwise by the quarter turn (y grows downwards).
    val up = when (quarter.mod(4)) {
        0 -> Offset(0f, -reach)
        1 -> Offset(reach, 0f)
        2 -> Offset(0f, reach)
        else -> Offset(-reach, 0f)
    }
    return HandleLayer(artwork.center + up, turn, handleAlpha(quarter, degrees))
}

/** The quarter turn (0 .. 3) closest to the groups hanging at [degrees]: Now Playing is dismissed toward its floor. */
internal fun dismissQuarter(degrees: Float): Int = (degrees / 90f).roundToInt().mod(4)

/** The quarter turn of the window's floor for groups hanging [levelQuarter] in the phone's frame, the window at [displayRotation]. */
internal fun windowQuarter(levelQuarter: Int, displayRotation: Int): Int = (levelQuarter - displayRotation).mod(4)

/**
 * The quarter turn the sheet slides toward, from [current]: the floor's ([quarter]) while it rests open ([covers]),
 * back to the bottom once [closed] (the mini player raises it from there), unchanged in between: never a turn under a
 * finger, and a sheet opened again before it was gone comes back the way it left until it rests.
 */
internal fun slideQuarterAfter(current: Int, quarter: Int, covers: Boolean, closed: Boolean): Int = when {
    covers -> quarter
    closed -> 0
    else -> current
}

/**
 * Gravity mode's hold on the [sheet] (T63.6, T63.8): the quarter turn it slides away and is dragged toward (the
 * window's floor, [slideQuarterAfter]), and each detent caught while it heads open, handed to [onDetent] (again on each
 * opening). Outside gravity mode ([gravity] false) it slides down as always and reports nothing. Reads the level's
 * quarter turn and detent, never its angle: a turn recomposes nothing and re-runs nothing per frame (T63.11).
 */
@Composable
internal fun rememberGravitySheetSide(
    level: GravityLevelState,
    gravity: Boolean,
    sheet: SheetPresentation,
    displayRotation: IntState,
    onDetent: (Int) -> Unit,
): IntState {
    val slideQuarter = remember { mutableIntStateOf(0) }
    LaunchedEffect(level, gravity) {
        snapshotFlow {
            val quarter = if (gravity) windowQuarter(level.quarter, displayRotation.intValue) else 0
            Triple(quarter, sheet.covers, sheet.progress.value <= 0f)
        }.collect { (quarter, covers, closed) ->
            slideQuarter.intValue = slideQuarterAfter(slideQuarter.intValue, quarter, covers, closed)
        }
    }
    val latestDetent by rememberUpdatedState(onDetent)
    LaunchedEffect(level, gravity) {
        if (gravity) snapshotFlow { level.detent.takeIf { sheet.targetOpen } }.collect { detent -> detent?.let { latestDetent(it) } }
    }
    return slideQuarter
}

/** The finger's axis that dismisses toward the floor of [quarter]. */
internal fun dismissOrientation(quarter: Int): Orientation =
    if (quarter.mod(2) == 0) Orientation.Vertical else Orientation.Horizontal

/** Where the sheet sits, [fraction] of its travel toward the floor of [quarter], in a window of [size]. */
internal fun sheetSlide(quarter: Int, fraction: Float, size: Size): Offset = when (quarter.mod(4)) {
    0 -> Offset(0f, fraction * size.height)
    1 -> Offset(-fraction * size.width, 0f)
    2 -> Offset(0f, -fraction * size.height)
    else -> Offset(fraction * size.width, 0f)
}

/**
 * A finger's [delta] along [dismissOrientation] of [quarter] as the sheet's own downward drag (counted in its height),
 * so it follows the finger one to one whichever way it slides in a window of [size].
 */
internal fun dismissDrag(quarter: Int, delta: Float, size: Size): Float {
    val sideways = if (size.width > 0f) size.height / size.width else 0f
    return when (quarter.mod(4)) {
        0 -> delta
        1 -> -delta * sideways
        2 -> -delta
        else -> delta * sideways
    }
}
