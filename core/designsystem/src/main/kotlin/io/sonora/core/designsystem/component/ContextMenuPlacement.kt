package io.sonora.core.designsystem.component

/** A rectangle in pixels, in the overlay's coordinates. */
data class PxRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/** Where the preview card and the menu go: their top-left corners, and whether the menu was flipped above the card. */
data class ContextMenuPlacement(
    val cardLeft: Float,
    val cardTop: Float,
    val menuLeft: Float,
    val menuTop: Float,
    val menuAbove: Boolean,
)

/**
 * Places an iOS context menu: the preview card lifts where the pressed element is ([anchor]) and the menu sits right
 * under it, horizontally near the finger ([touchX]); with no room below, the menu flips above the card, and with no
 * room either way the card moves up so the menu fits below it. Everything stays inside [area] less [margin]; [gap]
 * separates card and menu. [menuHeight] must already fit the area together with the card (the menu scrolls).
 */
fun placeContextMenu(
    anchor: PxRect,
    touchX: Float,
    cardWidth: Float,
    cardHeight: Float,
    menuWidth: Float,
    menuHeight: Float,
    area: PxRect,
    margin: Float,
    gap: Float,
): ContextMenuPlacement {
    val left = area.left + margin
    val right = area.right - margin
    val top = area.top + margin
    val bottom = area.bottom - margin
    val cardLeft = (anchor.centerX - cardWidth / 2f).clampIn(left, right - cardWidth)
    val menuLeft = (touchX - menuWidth / 2f).clampIn(left, right - menuWidth)
    var cardTop = (anchor.centerY - cardHeight / 2f).clampIn(top, bottom - cardHeight)
    val fitsBelow = cardTop + cardHeight + gap + menuHeight <= bottom
    val fitsAbove = cardTop - gap - menuHeight >= top
    if (!fitsBelow && fitsAbove) {
        return ContextMenuPlacement(cardLeft, cardTop, menuLeft, cardTop - gap - menuHeight, menuAbove = true)
    }
    if (!fitsBelow) cardTop = (bottom - menuHeight - gap - cardHeight).coerceAtLeast(top)
    return ContextMenuPlacement(cardLeft, cardTop, menuLeft, cardTop + cardHeight + gap, menuAbove = false)
}

/**
 * The most the menu may be measured at so that it and a [cardHeight] card fit together in [area] less [margin]; a
 * taller menu is cut to this and scrolls, from its top.
 */
fun contextMenuMaxHeight(area: PxRect, cardHeight: Float, margin: Float, gap: Float): Float =
    (area.height - 2 * margin - gap - cardHeight).coerceAtLeast(0f)

/** Within [min]..[max]; [min] wins when the range is empty (wider than the area). */
private fun Float.clampIn(min: Float, max: Float): Float = coerceAtMost(max).coerceAtLeast(min)
