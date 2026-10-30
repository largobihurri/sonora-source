package io.sonora.player.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.translucent.TranslucentPolicy
import io.sonora.core.designsystem.translucent.TranslucentTier
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.translucent.LocalTranslucentPolicy
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.player.R
import io.sonora.player.navigation.TopLevelRoute
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * iOS 26 style floating capsule tab bar. Tapping a tab selects it; pressing and sliding across the bar moves the tab
 * [strip] with the finger (the screens slide along, selecting each tab the strip reaches with a light tick) and the
 * release settles it on a tab, keeping the finger's speed. The selection pill rides the same strip position.
 *
 * It fills the size its parent gives it, which morphs (see [MorphingChrome]) between the full bar ([geometry]) and a
 * compact round pill showing only the selected tab's icon as [collapse] goes from 0 to 1. While [collapsed], the tabs
 * are inert and a tap anywhere on the pill calls [onExpand] (it does not switch tabs).
 */
@Composable
fun TranslucentTabBar(
    tabs: List<TopLevelRoute>,
    selected: TopLevelRoute,
    onSelect: (TopLevelRoute) -> Unit,
    strip: TabStripState,
    geometry: TabBarGeometry,
    collapse: () -> Float,
    collapsed: Boolean,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val log = remember(context) { GestureLog.of(context) }
    // Each tab's horizontal extent inside the row's content, as laid out (so right-to-left bars hit-test correctly).
    // The pill (drawBehind) and the scrub (pointerInput) sit after the row's padding, so they use the same origin.
    val bounds = remember(tabs.size) { mutableStateListOf(*Array(tabs.size) { 0f..0f }) }
    val latestTabs by rememberUpdatedState(tabs)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestSelected by rememberUpdatedState(selected)
    // The pill rides the tab strip's position (the same float that moves the screens), between measured cells.
    // The specular light follows it across the bar (-1 at the start edge, 1 at the end).
    val pillShift = {
        // The bar's content width: the outer cell's end, whichever side the first tab is laid out on.
        val width = if (bounds.isEmpty()) 0f else maxOf(bounds.first().endInclusive, bounds.last().endInclusive)
        val position = strip.position
        if (width <= 0f) 0f else (stripPillStart(position, bounds) + stripPillEnd(position, bounds)) / width - 1f
    }

    // Cells share the bar equally (weight) for any tab count; [geometry] sizes the bar and its labels.
    val wash by chromeWash()
    TranslucentSurface(modifier = modifier, shape = CapsuleShape, lightShift = pillShift, wash = { wash }, overContent = true) {
        val translucent = LocalTranslucentContentColors.current
        val policy = LocalTranslucentPolicy.current
        val pillColor = translucent.label.copy(alpha = if (translucent.tier == TranslucentTier.FLAT) 0.08f else 0.12f)
        Row(
            modifier = Modifier
                // The full bar keeps its own size while the translucency around it shrinks, and fades out early.
                .requiredSize(geometry.barWidth.dp, TabCellHeight + (2 * TabBarGeometry.INSET).dp)
                .graphicsLayer { alpha = (1f - 2f * collapse()).coerceIn(0f, 1f) }
                .padding(TabBarGeometry.INSET.dp)
                .then(if (collapsed) Modifier.clearAndSetSemantics {} else Modifier)
                .drawBehind {
                    val position = strip.position
                    val start = stripPillStart(position, bounds)
                    val end = stripPillEnd(position, bounds)
                    if (end > start) {
                        val topLeft = Offset(start, 0f)
                        val pillSize = Size(end - start, size.height)
                        val radius = CornerRadius(size.height / 2f)
                        drawRoundRect(color = pillColor, topLeft = topLeft, size = pillSize, cornerRadius = radius)
                        if (translucent.tier != TranslucentTier.FLAT) {
                            // A translucent pill: a thin rim lit from the same side as the bar's specular highlight.
                            val angle = TranslucentPolicy.specularAngle(pillShift(), policy.specularMotion)
                            drawRoundRect(
                                brush = pillRim(angle, topLeft, pillSize),
                                topLeft = topLeft,
                                size = pillSize,
                                cornerRadius = radius,
                                style = Stroke(width = 1.dp.toPx()),
                            )
                        }
                    }
                }
                // Keyed on the bounds list, which is replaced when the tab count changes, so the scrub never reads a
                // stale list. Collapsed, there is nothing to scrub.
                .pointerInput(bounds, collapsed, strip) {
                    if (collapsed) return@pointerInput
                    val tracker = VelocityTracker()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        tracker.resetTracking()
                        tracker.addPointerInputChange(down)
                        val scrub = TabScrub(startIndex = latestTabs.indexOf(latestSelected))
                        var scrubbing = false
                        var lifted = false
                        // Where the finger and the strip were when the scrub took over: the strip moves with the
                        // finger from there instead of jumping to the finger's own tab.
                        var startX = 0f
                        var startPosition = 0f
                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    // The lift can land further along than the last move: the strip ends under it.
                                    if (scrubbing) strip.drag(scrubPosition(change.position.x, startX, startPosition, bounds))
                                    lifted = true
                                    break
                                }
                                tracker.addPointerInputChange(change)
                                // A tap stays a tap (the tab's own click) until the finger slides past the touch slop.
                                if (!scrubbing && abs(change.position.x - down.position.x) < viewConfiguration.touchSlop) {
                                    continue
                                }
                                if (!scrubbing) {
                                    scrubbing = true
                                    startX = change.position.x
                                    startPosition = strip.position
                                }
                                change.consume() // cancels the pressed tab's click: the strip picks the tab
                                strip.drag(scrubPosition(change.position.x, startX, startPosition, bounds))
                                val reached = scrub.moveTo(stripSettleTarget(strip.position, 0f, strip.count))
                                latestTabs.getOrNull(reached ?: continue)?.takeIf { it != latestSelected }?.let { tab ->
                                    log?.invoke("tab scrub at x=${change.position.x.toInt()} -> $tab")
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                    latestOnSelect(tab)
                                }
                            }
                        } finally {
                            // Lifted: settle with the finger's speed. Cancelled (e.g. the bar collapsed): settle in place.
                            if (scrubbing) {
                                val velocity = if (lifted) stripVelocity(tracker.calculateVelocity().x, bounds) else 0f
                                val target = stripSettleTarget(strip.position, velocity, strip.count)
                                strip.release(velocity)
                                log?.invoke("tab scrub release at ${strip.position} v=$velocity -> $target")
                                latestTabs.getOrNull(target)?.takeIf { it != latestSelected }?.let { tab ->
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                    latestOnSelect(tab)
                                }
                            }
                        }
                    }
                }
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(TabBarGeometry.GAP.dp),
        ) {
            tabs.forEachIndexed { index, tab ->
                TabItem(
                    tab = tab,
                    isSelected = tab == selected,
                    compactLabel = geometry.compactLabels,
                    onClick = { onSelect(tab) },
                    // Equal cells; the measured cell drives the pill and the scrub hit-testing.
                    modifier = Modifier.weight(1f).fillMaxHeight().onPlaced { coordinates ->
                        val x = coordinates.positionInParent().x
                        val range = x..(x + coordinates.size.width)
                        if (index < bounds.size && bounds[index] != range) bounds[index] = range
                    },
                )
            }
        }
        CompactTab(selected, collapse, collapsed, onExpand, Modifier.matchParentSize())
    }
}

/**
 * The collapsed bar: the selected tab's icon, fading in over the second half of the morph. Drawn over the full bar,
 * so while [collapsed] its tap (expand) is the only one the bar takes.
 */
@Composable
private fun CompactTab(
    selected: TopLevelRoute,
    collapse: () -> Float,
    collapsed: Boolean,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expandLabel = stringResource(R.string.tab_bar_expand)
    val selectedLabel = stringResource(selected.label)
    Box(
        modifier = modifier
            .graphicsLayer { alpha = (2f * collapse() - 1f).coerceIn(0f, 1f) }
            .then(
                if (collapsed) {
                    Modifier
                        .clickable(onClickLabel = expandLabel, role = Role.Button, onClick = onExpand)
                        .semantics { contentDescription = selectedLabel }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = selected.icon,
            contentDescription = null,
            tint = LocalTranslucentContentColors.current.accent,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * The strip position for a finger at [x] in a scrub that took the strip over at [startPosition] with the finger at
 * [startX]. The strip starts where it was (no jump when the scrub begins: [x] == [startX] gives [startPosition]) and
 * moves in proportion to the finger's travel towards each end: the finger on the first tab's centre puts the strip on
 * the first tab, on the last tab's centre on the last, whatever the slop the scrub started off. A plain "strip moves
 * by the finger's travel" would stop short of the far end by the start offset, and the page would stay cut at the
 * screen's edge while the finger is held. Beyond the outer centres the strip stays on the end tab. Monotonic in [x].
 * A start at or past an end centre has no travel left that way, so the strip holds at [startPosition] in that
 * direction. [TabStripState.drag] keeps it within the strip.
 */
internal fun scrubPosition(x: Float, startX: Float, startPosition: Float, bounds: List<ClosedFloatingPointRange<Float>>): Float {
    val last = bounds.size - 1
    if (last < 1) return startPosition
    val first = (bounds[0].start + bounds[0].endInclusive) / 2f
    val end = (bounds[last].start + bounds[last].endInclusive) / 2f
    if (first == end) return startPosition
    // Flip right-to-left bars so the centres always grow with the tab index.
    val direction = if (end > first) 1f else -1f
    val from = startPosition.coerceIn(0f, last.toFloat())
    val finger = x * direction
    val start = startX * direction
    // The finger's share of the way from where it started to the end centre, and the strip's of the way to its end tab.
    return if (finger >= start) {
        val reach = end * direction - start
        if (reach <= 0f) from else from + ((finger - start) / reach).coerceAtMost(1f) * (last - from)
    } else {
        val reach = start - first * direction
        if (reach <= 0f) from else from - ((start - finger) / reach).coerceAtMost(1f) * from
    }
}

/** A white rim brightest where light at [angleDegrees] hits the pill, fading across it. */
private fun pillRim(angleDegrees: Float, topLeft: Offset, size: Size): Brush {
    val radians = Math.toRadians(angleDegrees.toDouble())
    val center = topLeft + Offset(size.width / 2f, size.height / 2f)
    val reach = Offset((cos(radians) * size.width / 2).toFloat(), (-sin(radians) * size.height / 2).toFloat())
    return Brush.linearGradient(
        listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.3f)),
        start = center - reach,
        end = center + reach,
    )
}

/** Fixed bar content height, whatever the tab count: icon and one label line. */
private val TabCellHeight = 51.dp

/**
 * One tab. Taps go through its own click (with tab semantics for accessibility); slides across tabs are handled by
 * the bar. A finger that leaves this tab cancels its tap, so a scrub ends on the tab the bar selected.
 */
@Composable
private fun TabItem(
    tab: TopLevelRoute,
    isSelected: Boolean,
    compactLabel: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTranslucentContentColors.current
    val tint by animateColorAsState(if (isSelected) colors.accent else colors.label, label = "tabTint")
    Column(
        modifier = modifier
            .clip(CapsuleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .semantics {
                role = Role.Tab
                this.selected = isSelected
            }
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val label = MaterialTheme.typography.labelSmall
        Icon(imageVector = tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(tab.label),
            style = if (compactLabel) label.copy(fontSize = 10.sp, lineHeight = 12.sp) else label,
            color = tint,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
