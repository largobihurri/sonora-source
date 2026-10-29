package io.sonora.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.Artwork
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.component.pillLeadingInset
import io.sonora.core.designsystem.component.coverflow.CoverFlowHandoff
import io.sonora.core.designsystem.component.coverflow.handoffCapsule
import io.sonora.core.designsystem.component.coverflow.handoffThumbnail
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.motion.SheetPresentation
import io.sonora.core.designsystem.motion.SwipeCommit
import io.sonora.core.designsystem.motion.SwipeMotion
import io.sonora.core.designsystem.motion.prefersReducedMotion
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.core.domain.model.Track
import io.sonora.player.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Speed (per second) that commits a short track swipe. */
private val SwipeFlingVelocity = 600.dp

/** The corners of the mini player's artwork. */
private val ArtworkRadius = 8.dp
private val ArtworkSize = 40.dp
private val ArtworkTitleGap = 8.dp

/**
 * Floating translucent capsule above the tab bar while something is loaded. Tapping the track opens Now Playing; an
 * upward drag pulls the [sheet] open with the finger; a horizontal swipe changes track (left = next, right = previous).
 * Gestures start only on the artwork and title, so the play and next buttons always stay plain taps. [itemKey]
 * identifies the playing queue entry, so a committed swipe can wait for the next one before sliding it in.
 *
 * It fills the size its parent gives it, which morphs (see [MorphingChrome]) into a compact capsule beside the tab pill
 * as [collapse] goes from 0 to 1: the next button folds away (and is inert while [collapsed]); every gesture still works.
 *
 * Now Playing grows out of it as a translucent bubble (T67.2): it reports itself and its artwork to [handoff], and
 * fades as the bubble leaves it.
 */
@Composable
fun MiniPlayer(
    track: Track,
    itemKey: Long?,
    isPlaying: Boolean,
    skips: MiniPlayerSkips,
    sheet: SheetPresentation,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    collapse: () -> Float,
    collapsed: Boolean,
    modifier: Modifier = Modifier,
    handoff: CoverFlowHandoff? = null,
) {
    val wash by chromeWash()
    TranslucentSurface(modifier = modifier.handoffCapsule(handoff), shape = CapsuleShape, wash = { wash }) {
        Row(
            modifier = Modifier.fillMaxSize().padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SwipeableTrack(track, itemKey, skips, sheet, onNext, onPrevious, handoff, Modifier.weight(1f))
            IconButton(onClick = onTogglePlay) {
                Icon(
                    if (isPlaying) SonoraIcons.Pause else SonoraIcons.Play,
                    contentDescription = stringResource(if (isPlaying) R.string.mini_player_pause else R.string.mini_player_play),
                    tint = LocalTranslucentContentColors.current.label,
                )
            }
            IconButton(
                onClick = onNext,
                enabled = !collapsed,
                modifier = Modifier
                    .foldAway(collapse)
                    // Collapsed, TalkBack still reaches the next track through the custom action.
                    .then(if (collapsed) Modifier.clearAndSetSemantics {} else Modifier),
            ) {
                Icon(SonoraIcons.Next, contentDescription = stringResource(R.string.mini_player_next), tint = LocalTranslucentContentColors.current.label)
            }
        }
    }
}

/** Narrows (from the end) and fades as [collapse] goes from 0 to 1; read at layout and draw time only. */
private fun Modifier.foldAway(collapse: () -> Float): Modifier = this
    .clipToBounds()
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val width = (placeable.width * (1f - collapse().coerceIn(0f, 1f))).roundToInt()
        layout(width, placeable.height) { placeable.placeRelative(0, 0) }
    }
    .graphicsLayer { alpha = 1f - collapse().coerceIn(0f, 1f) }

/** Artwork and title: the gesture area, whose content slides with a horizontal swipe. */
@Composable
private fun SwipeableTrack(
    track: Track,
    itemKey: Long?,
    skips: MiniPlayerSkips,
    sheet: SheetPresentation,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    handoff: CoverFlowHandoff?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val log = remember(context) { GestureLog.of(context) }
    val flingPx = with(LocalDensity.current) { SwipeFlingVelocity.toPx() }
    val artworkRadiusPx = with(LocalDensity.current) { ArtworkRadius.toPx() }
    val offset = remember { Animatable(0f) }
    val swipe = remember(scope) { SwipeCommit(scope) }
    val latestSkips by rememberUpdatedState(skips)
    val latestKey by rememberUpdatedState(itemKey)
    val itemKeys = remember { snapshotFlow { latestKey } }
    val latestNext by rememberUpdatedState(onNext)
    val latestPrevious by rememberUpdatedState(onPrevious)

    fun swipeTo(rawPx: Float, widthPx: Float) {
        val skipsNow = latestSkips
        val target = swipeOffset(rawPx, widthPx, skipsNow.canNext, skipsNow.canPrevious)
        scope.launch { offset.snapTo(target) }
    }

    fun commit(outcome: SwipeOutcome, widthPx: Float, velocityPx: Float) {
        if (outcome == SwipeOutcome.SpringBack) {
            scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f), velocityPx) }
            return
        }
        val action = if (outcome == SwipeOutcome.Next) latestNext else latestPrevious
        val out = if (outcome == SwipeOutcome.Next) -widthPx else widthPx
        val motion = object : SwipeMotion {
            override suspend fun slideOut() {
                offset.animateTo(out, tween(160), velocityPx)
            }

            override suspend fun slideIn() {
                offset.snapTo(-out) // the new track enters from the opposite side
                offset.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 400f))
            }

            override suspend fun reset() = offset.snapTo(0f)
        }
        if (swipe.commit(action, itemKeys, motion, reducedMotion = context.prefersReducedMotion()) != null) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }

    val nextTrack = stringResource(R.string.mini_player_next_track)
    val previousTrack = stringResource(R.string.mini_player_previous_track)
    val openNowPlaying = stringResource(R.string.mini_player_open)
    Box(
        modifier
            .fillMaxHeight()
            .clipToBounds()
            .semantics {
                customActions = buildList {
                    if (skips.canNext) add(CustomAccessibilityAction(nextTrack) { onNext(); true })
                    if (skips.canPrevious) add(CustomAccessibilityAction(previousTrack) { onPrevious(); true })
                }
            }
            .pointerInput(sheet) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val lock = AxisLock(viewConfiguration.touchSlop)
                    val velocity = VelocityTracker().apply { addPointerInputChange(down) }
                    val widthPx = size.width.toFloat()
                    var axis: DragAxis? = null
                    var rawX = 0f
                    var released = false // the pointer went up (lifted, or cancelled by the system)
                    var cancelled = false
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                // A cancelled touch arrives as an already consumed release: settle it by distance only.
                                released = true
                                cancelled = change.isConsumed
                                if (!cancelled) velocity.addPointerInputChange(change)
                                break
                            }
                            velocity.addPointerInputChange(change)
                            val delta = change.positionChange()
                            if (axis == null) {
                                axis = lock.add(delta.x, delta.y) ?: continue
                                change.consume() // the tap is over: the clickable below cancels
                                log?.invoke("mini player lock $axis after (${lock.totalX.toInt()}, ${lock.totalY.toInt()}) px")
                                // The movement inside the slop counts, so the content starts right under the finger.
                                if (axis == DragAxis.Vertical) {
                                    sheet.dragStarted()
                                    sheet.dragBy(lock.totalY)
                                } else if (swipe.running) {
                                    break // a swipe is ignored while the previous one is still changing track
                                } else {
                                    rawX = lock.totalX
                                    swipeTo(rawX, widthPx)
                                }
                                continue
                            }
                            change.consume()
                            if (axis == DragAxis.Vertical) {
                                sheet.dragBy(delta.y)
                            } else {
                                rawX += delta.x
                                swipeTo(rawX, widthPx)
                            }
                        }
                        val fling = if (released && !cancelled) velocity.calculateVelocity() else Velocity.Zero
                        when (axis) {
                            DragAxis.Vertical -> if (released && !cancelled) sheet.dragStopped(fling.y) else sheet.dragCancelled()
                            DragAxis.Horizontal -> if (!swipe.running) {
                                val skipsNow = latestSkips
                                val vx = fling.x
                                val outcome = if (released) {
                                    swipeOutcome(rawX, vx, widthPx, flingPx, skipsNow.canNext, skipsNow.canPrevious)
                                } else {
                                    SwipeOutcome.SpringBack
                                }
                                log?.invoke(
                                    "mini player swipe ${rawX.toInt()} px, ${vx.toInt()} px/s, cancelled=$cancelled, " +
                                        "next=${skipsNow.canNext}, previous=${skipsNow.canPrevious} -> $outcome",
                                )
                                commit(outcome, widthPx, vx)
                            }
                            null -> Unit
                        }
                    } finally {
                        // A gesture cut short (e.g. the shell left) must not leave the sheet held mid-drag.
                        if (axis == DragAxis.Vertical) sheet.dragCancelled()
                    }
                }
            }
            .clickable(onClickLabel = openNowPlaying, onClick = sheet::open),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offset.value
                    alpha = 1f - 0.4f * (abs(offset.value) / size.width.coerceAtLeast(1f)).coerceAtMost(1f)
                }
                // The thumbnail's corners keep the same gap from the round end as its top and bottom from the edges.
                .padding(start = pillLeadingInset(MiniPlayerHeight.dp, ArtworkSize, ArtworkRadius)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(track.artworkUri, Modifier.size(ArtworkSize).handoffThumbnail(handoff, artworkRadiusPx), RoundedCornerShape(ArtworkRadius))
            Spacer(Modifier.width(ArtworkTitleGap))
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = LocalTranslucentContentColors.current.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
