package io.sonora.core.designsystem.component

import kotlin.math.max
import kotlin.math.min

/**
 * How big a centred translucent panel may be: its [width], the most it may grow ([maxHeight]) before it scrolls, and the
 * least it is laid out at ([minHeight]; zero for a panel that wraps its content).
 */
data class TranslucentPanelSize(val width: Float, val maxHeight: Float, val minHeight: Float = 0f)

/**
 * How tall a centred panel is: as its content needs ([Wrap]); as its content needs but allowed to grow as tall as a
 * [Tall] panel ([Expanded], a control surface such as the equalizer); or filling most of the screen ([Tall], a queue).
 */
enum class TranslucentPanelHeight { Wrap, Expanded, Tall }

private const val WIDTH_FRACTION = 0.9f
private const val MAX_HEIGHT_FRACTION = 0.8f
private const val LANDSCAPE_MAX_HEIGHT_FRACTION = 0.9f
private const val TALL_HEIGHT_FRACTION = 0.94f

/**
 * The size of a panel centred in [area] (the screen inside the system bars): nine tenths of its width up to
 * [maxWidth] ([landscapeMaxWidth] when the area is wider than tall), and up to four fifths of its height (nine tenths
 * in landscape, where height is scarce). It never comes closer than [margin] to the area's edges.
 *
 * A [TranslucentPanelHeight.Tall] panel is instead 94% of the height in either orientation (still [margin] clear of the
 * edges), and that is also its minimum, so it fills the room whatever its content needs. A
 * [TranslucentPanelHeight.Expanded] panel may grow as tall, but wraps its content.
 */
internal fun translucentPanelSize(
    area: PxRect,
    margin: Float,
    maxWidth: Float,
    landscapeMaxWidth: Float,
    height: TranslucentPanelHeight = TranslucentPanelHeight.Wrap,
): TranslucentPanelSize {
    val landscape = area.width > area.height
    val width = min(area.width * WIDTH_FRACTION, if (landscape) landscapeMaxWidth else maxWidth)
    val fraction = when {
        height != TranslucentPanelHeight.Wrap -> TALL_HEIGHT_FRACTION
        landscape -> LANDSCAPE_MAX_HEIGHT_FRACTION
        else -> MAX_HEIGHT_FRACTION
    }
    val maxHeight = max(0f, min(area.height * fraction, area.height - 2 * margin))
    return TranslucentPanelSize(
        width = max(0f, min(width, area.width - 2 * margin)),
        maxHeight = maxHeight,
        minHeight = if (height == TranslucentPanelHeight.Tall) maxHeight else 0f,
    )
}

/** Top-left corner of a [width] × [height] panel centred in [area]. */
internal fun translucentPanelOrigin(area: PxRect, width: Float, height: Float): Pair<Float, Float> =
    area.left + (area.width - width) / 2f to area.top + (area.height - height) / 2f

/** Where a pull-down menu goes: its top-left corner, its [height] (cut to the room, then it scrolls), and its side. */
internal data class PullDownPlacement(val left: Float, val top: Float, val height: Float, val below: Boolean)

private fun roomBelow(anchor: PxRect, area: PxRect, margin: Float, gap: Float) = area.bottom - margin - (anchor.bottom + gap)

private fun roomAbove(anchor: PxRect, area: PxRect, margin: Float, gap: Float) = anchor.top - gap - (area.top + margin)

/** The most a pull-down under (or over) [anchor] may be measured at: the larger room, above or below it. */
internal fun pullDownMaxHeight(anchor: PxRect, area: PxRect, margin: Float, gap: Float): Float =
    max(0f, max(roomBelow(anchor, area, margin, gap), roomAbove(anchor, area, margin, gap)))

/**
 * Places an iOS pull-down menu of [width] × [height] for its button ([anchor]): [gap] under it with the right edges
 * lined up, kept [margin] inside [area]. It opens upwards only when it does not fit below and there is more room
 * above; either way it is cut to the room it has (and scrolls).
 */
internal fun placePullDown(anchor: PxRect, width: Float, height: Float, area: PxRect, margin: Float, gap: Float): PullDownPlacement {
    val minLeft = area.left + margin
    val left = (anchor.right - width).coerceIn(minLeft, max(minLeft, area.right - margin - width))
    val below = roomBelow(anchor, area, margin, gap)
    val above = roomAbove(anchor, area, margin, gap)
    val goesBelow = height <= below || below >= above
    val shown = max(0f, min(height, if (goesBelow) below else above))
    val top = if (goesBelow) anchor.bottom + gap else anchor.top - gap - shown
    return PullDownPlacement(left, top, shown, goesBelow)
}

/** How far the system bars, cutouts and keyboard reach in from each edge of a window, in pixels. */
internal data class EdgeInsets(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * The part of an overlay host that [insets] leave free, in the host's own coordinates. The host's bounds are [host],
 * in the coordinates of a [windowWidth] × [windowHeight] window. Each inset is measured from the window's edge, so it
 * only takes from the host where it reaches into it.
 *
 * A host filling the window loses all of every inset. A host already below the status bar and above the keyboard,
 * such as the tag editor's host inside its sheet, loses nothing to them.
 */
internal fun overlayArea(host: PxRect, windowWidth: Float, windowHeight: Float, insets: EdgeInsets): PxRect {
    val left = max(0f, insets.left - host.left)
    val top = max(0f, insets.top - host.top)
    val right = max(left, host.width - max(0f, insets.right - (windowWidth - host.right)))
    val bottom = max(top, host.height - max(0f, insets.bottom - (windowHeight - host.bottom)))
    return PxRect(left, top, right, bottom)
}
