package io.sonora.core.designsystem.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp

/**
 * The tallest a sheet may be in a window [containerHeight] px tall whose top [topInset] px (the status bar, a cutout)
 * it must not cover: the sheet's top stays on that line.
 */
internal fun sheetMaxHeight(containerHeight: Int, topInset: Int): Int = (containerHeight - topInset).coerceAtLeast(0)

/**
 * Where the capped sheet box sits in its parent: [y] px from the top, taking [reportedHeight] px of it.
 *
 * Material 3 derives its anchors from the constraints the sheet is measured with (Expanded = height it was given -
 * sheet height, Hidden = the height it was given) and offsets the sheet from where the box is placed. The box is
 * capped at [sheetMaxHeight], so it is placed [topInset] px down: the anchors then count from the status bar's line
 * and every anchor lands where it would in a window that ended at the bottom edge (a sheet that fills the cap ends
 * on the edge, a hidden one is fully off screen). [topInset] is clamped to [containerHeight].
 */
internal data class SheetPlacement(val reportedHeight: Int, val y: Int)

internal fun sheetPlacement(containerHeight: Int, topInset: Int, measuredHeight: Int): SheetPlacement {
    val y = topInset.coerceIn(0, containerHeight.coerceAtLeast(0))
    return SheetPlacement(reportedHeight = y + measuredHeight, y = y)
}

/**
 * Sonora's Material 3 modal bottom sheet: every sheet in the app goes through it.
 *
 * Material 3's default content insets include the top (`safeDrawing`, bottom and top): the sheet pads its content by
 * `statusBar - offset`, so its measured height depends on where it sits, and its expanded anchor (`windowHeight -
 * height`) is set again at every size change. A sheet whose content is between `windowHeight - statusBar` and
 * `windowHeight` high then moves its own anchor ahead of itself while it opens: it springs up to the status bar and
 * crawls the last few pixels. Here the content keeps only the bottom insets (navigation bar, keyboard), so the height
 * no longer depends on the offset, and the sheet is capped at the status bar instead ([sheetMaxHeight]). Content
 * taller than that has to scroll, as it did before. The capped box is placed below the status bar ([sheetPlacement]),
 * because Material 3 computes its anchors inside the box it is given: placed at the window's top, every anchor would
 * sit a status bar too high (a full-height sheet short of the bottom edge, a hidden one still showing).
 *
 * The parameters are Material 3's, with the same defaults.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SonoraModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    sheetGesturesEnabled: Boolean = true,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = BottomSheetDefaults.Elevation,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: (@Composable () -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
    val top = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.capHeightBelow(top),
        sheetState = sheetState,
        sheetGesturesEnabled = sheetGesturesEnabled,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        scrimColor = scrimColor,
        dragHandle = dragHandle,
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom) },
        content = content,
    )
}

/**
 * Measures the sheet with at most the window's height below [top] and places it below [top] (read when measuring, so
 * it follows the insets). The box reports the inset plus the sheet, so Material 3's anchors count from the status
 * bar's line ([sheetPlacement]).
 */
private fun Modifier.capHeightBelow(top: WindowInsets): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val inset = top.getTop(this)
    val max = sheetMaxHeight(constraints.maxHeight, inset)
    val placeable = measurable.measure(
        constraints.copy(minHeight = constraints.minHeight.coerceAtMost(max), maxHeight = max),
    )
    val placement = sheetPlacement(constraints.maxHeight, inset, placeable.height)
    layout(placeable.width, placement.reportedHeight) { placeable.place(0, placement.y) }
}
