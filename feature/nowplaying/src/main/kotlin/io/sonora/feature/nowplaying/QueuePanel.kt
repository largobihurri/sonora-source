package io.sonora.feature.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.sonora.core.designsystem.component.Artwork
import io.sonora.core.designsystem.component.DragAutoScrollEffect
import io.sonora.core.designsystem.component.TranslucentPanel
import io.sonora.core.designsystem.component.TranslucentPanelHeight
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.component.scrollEdgeFade
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.QueueItem
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.model.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Secondary text, as a fraction of the translucent label colour (so it follows light and dark). */
private const val SECONDARY_ALPHA = 0.62f

/**
 * The well a swiped row reveals: iOS system red as a translucent tint, and the bin in the theme's destructive red (the
 * context menu's), light on dark translucency and deep on light translucency.
 */
private val RemoveTintDark = Color(0xFFFF453A)
private val RemoveTintLight = Color(0xFFFF3B30)
private val RemoveGlyphDark = Color(0xFFFF6961)
private val RemoveGlyphLight = Color(0xFFD70015)

private val ListShape = RoundedCornerShape(18.dp)

/** The translucent label colour, and a softer one for secondary text; read inside the panel, where the surface provides them. */
private val label: Color @Composable get() = LocalTranslucentContentColors.current.label
private val secondary: Color @Composable get() = label.copy(alpha = SECONDARY_ALPHA)

/**
 * Translucent "Up Next" panel, tall (about 94% of the screen): the current track with the shuffle and repeat capsules under
 * it, then the "Up Next" title with Clear, and the real upcoming order in a rounded card whose rows fade at its edges
 * as they scroll. Tap a row to play it, drag its handle to reorder, swipe it left to remove; Clear empties Up Next.
 * Tapping outside or Back closes it.
 */
@Composable
internal fun QueuePanel(state: PlaybackState, viewModel: NowPlayingViewModel, onDismiss: () -> Unit) {
    val listState = rememberLazyListState()
    val reorder = remember(listState) { UpNextReorder(listState) }
    TranslucentPanel(stringResource(R.string.now_playing_up_next), onDismiss, height = TranslucentPanelHeight.Tall) {
        QueueContent(state, viewModel, listState, reorder)
    }
}

/**
 * The Up Next queue in place of the artwork (tablets, see [SidePanel]): the same content as [QueuePanel], on a translucent card
 * over the artwork backdrop that fills the room it is given.
 */
@Composable
internal fun QueueSidePanel(state: PlaybackState, viewModel: NowPlayingViewModel, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val reorder = remember(listState) { UpNextReorder(listState) }
    // Always dark over the artwork backdrop, whatever the app theme, like the bottom row's capsule.
    TranslucentSurface(modifier.fillMaxSize(), shape = SidePanelShape, elevation = 0.dp, darkTheme = true, tinted = false) {
        QueueContent(state, viewModel, listState, reorder)
    }
}

private val SidePanelShape = RoundedCornerShape(28.dp)

/**
 * What the Up Next panel holds, filling its container: the current track with the shuffle and repeat capsules under
 * it, the "Up Next" title with Clear, and the upcoming order in a rounded card (see [QueuePanel]).
 */
@Composable
private fun QueueContent(state: PlaybackState, viewModel: NowPlayingViewModel, listState: LazyListState, reorder: UpNextReorder) {
    Column(Modifier.fillMaxHeight()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
            state.currentTrack?.let { CurrentRow(it) }
            // As in Apple Music, the playback modes sit under the current track as two wide capsules, and the
            // list's title gets a line of its own: a long title ("A continuación") never shares it with buttons.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeCapsule(
                    SonoraIcons.Shuffle,
                    stringResource(R.string.now_playing_shuffle),
                    state.shuffleEnabled,
                    viewModel::toggleShuffle,
                    Modifier.weight(1f),
                )
                ModeCapsule(
                    icon = if (state.repeatMode == RepeatMode.ONE) SonoraIcons.RepeatOne else SonoraIcons.Repeat,
                    label = stringResource(
                        when (state.repeatMode) {
                            RepeatMode.OFF -> R.string.queue_repeat_off
                            RepeatMode.ALL -> R.string.queue_repeat_all
                            RepeatMode.ONE -> R.string.queue_repeat_one
                        },
                    ),
                    active = state.repeatMode != RepeatMode.OFF,
                    onClick = viewModel::cycleRepeat,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.size(14.dp))
            Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                // One line, never split inside a word: at large text sizes it steps down rather than wrap.
                Text(
                    stringResource(R.string.now_playing_up_next),
                    color = label,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = 15.sp, maxFontSize = 20.sp, stepSize = 0.5.sp),
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (state.upNext.isNotEmpty()) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.queue_clear),
                        color = LocalTranslucentContentColors.current.accent,
                        fontSize = 17.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClick = viewModel::clearUpcoming)
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                    )
                }
            }
        }
        Spacer(Modifier.size(4.dp))
        // The list's card takes the rest of the panel; a short queue leaves it empty below, a long one scrolls.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                .clip(ListShape)
                .background(label.copy(alpha = 0.07f)),
        ) {
            if (state.upNext.isEmpty()) {
                Text(
                    stringResource(R.string.queue_empty),
                    color = secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                )
            } else {
                UpNextList(state, viewModel, reorder, listState)
            }
        }
    }
}

@Composable
private fun UpNextList(
    state: PlaybackState,
    viewModel: NowPlayingViewModel,
    reorder: UpNextReorder,
    listState: LazyListState,
) {
    val labels = rememberUpNextLabels()
    val upNext = state.upNext.map { it.value }
    // Gesture callbacks outlive recompositions; they read the latest queue through these.
    val latestUpNext by rememberUpdatedState(upNext)
    val dragging = reorder.draggedId != null
    val shown = if (dragging) reorder.order else upNext
    // A row held at the list's top or bottom edge scrolls it, and keeps passing the rows that scroll under it.
    DragAutoScrollEffect(listState, active = dragging, probe = reorder::draggedMiddle, onScrolled = reorder::scrolled)
    val draggedRowColor = MaterialTheme.colorScheme.surfaceContainerHigh // opaque, so the row reads over its neighbours
    LazyColumn(
        state = listState,
        // Rows scrolled under the card's top and bottom fade out rather than being cut.
        modifier = Modifier.fillMaxSize().scrollEdgeFade(listState),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        itemsIndexed(shown, key = { _, item -> item.id }) { position, item ->
            val isDragged = item.id == reorder.draggedId
            val rowModifier = if (isDragged) {
                Modifier
                    .zIndex(1f)
                    .graphicsLayer {
                        translationY = reorder.translationOf(item.id)
                        scaleX = 1.02f
                        scaleY = 1.02f
                        shadowElevation = 16f
                        shape = RoundedCornerShape(10.dp)
                        clip = true
                    }
                    .background(draggedRowColor)
            } else {
                Modifier.animateItem()
            }
            val handle = Modifier.pointerInput(item.id) {
                detectDragGestures(
                    onDragStart = { reorder.start(item.id, latestUpNext) },
                    onDrag = { change, amount ->
                        change.consume()
                        reorder.drag(amount.y)
                    },
                    onDragEnd = {
                        reorder.end(latestUpNext.map { it.id })?.let { (from, to) -> viewModel.moveItem(item.id, by = to - from) }
                    },
                    onDragCancel = reorder::cancel,
                )
            }
            SwipeToRemove(
                enabled = !dragging,
                onRemove = { viewModel.removeItem(item.id) },
                modifier = rowModifier.semantics {
                    customActions = upNextActions(item.id, position, shown.size, viewModel, labels)
                },
            ) {
                // Only the track part plays the row; the grip is a sibling, so pressing it can never jump.
                UpNextRow(item, onClick = { viewModel.skipToItem(item.id) }, enabled = !dragging) { DragHandle(handle) }
            }
        }
    }
}

/** Screen-reader equivalents of swipe and drag. */
private fun upNextActions(itemId: Long, position: Int, count: Int, viewModel: NowPlayingViewModel, labels: UpNextLabels) =
    buildList {
        add(CustomAccessibilityAction(labels.remove) { viewModel.removeItem(itemId); true })
        if (position > 0) add(CustomAccessibilityAction(labels.moveUp) { viewModel.moveItem(itemId, by = -1); true })
        if (position < count - 1) add(CustomAccessibilityAction(labels.moveDown) { viewModel.moveItem(itemId, by = 1); true })
    }

/** Spoken names of the Up Next row actions, read once per composition. */
private class UpNextLabels(val remove: String, val moveUp: String, val moveDown: String)

@Composable
private fun rememberUpNextLabels(): UpNextLabels {
    val remove = stringResource(R.string.queue_remove)
    val moveUp = stringResource(R.string.queue_move_up)
    val moveDown = stringResource(R.string.queue_move_down)
    return remember(remove, moveUp, moveDown) { UpNextLabels(remove, moveUp, moveDown) }
}

@Composable
private fun CurrentRow(track: Track) {
    Text(stringResource(R.string.queue_now_playing), color = secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.size(6.dp))
    TrackLine(track, Modifier.padding(bottom = 16.dp))
}

@Composable
private fun UpNextRow(item: QueueItem, onClick: () -> Unit, enabled: Boolean, dragHandle: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 12.dp)) {
        TrackLine(
            item.track,
            Modifier
                .weight(1f)
                .clickable(enabled = enabled, onClickLabel = stringResource(R.string.queue_play), onClick = onClick)
                .padding(vertical = 5.dp),
        )
        dragHandle()
    }
}

/** Reorder grip; [modifier] carries the drag gesture. A press without a drag does nothing. */
@Composable
private fun DragHandle(modifier: Modifier = Modifier) {
    Icon(SonoraIcons.DragHandle, contentDescription = null, tint = secondary, modifier = modifier.size(44.dp).padding(11.dp))
}

@Composable
private fun TrackLine(track: Track, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Artwork(track.artworkUri, Modifier.size(44.dp), RoundedCornerShape(6.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Swipe left to remove: past 40 % of the width (or a fast fling) the row slides out and [onRemove] runs; otherwise it
 * springs back. If the row survives (the removal was refused) it returns after a beat. The row reveals a rounded,
 * inset translucent well tinted system red, with the bin in the theme's red centred in it; the tint deepens once letting
 * go would remove the row. All of it is drawn from the offset in the draw phase.
 */
@Composable
private fun SwipeToRemove(
    enabled: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var width by remember { mutableFloatStateOf(1f) }
    val bin = rememberVectorPainter(SonoraIcons.Trash)
    val darkTranslucent = label.luminance() > 0.5f
    val tint = if (darkTranslucent) RemoveTintDark else RemoveTintLight
    val glyph = ColorFilter.tint(if (darkTranslucent) RemoveGlyphDark else RemoveGlyphLight)
    Box(
        modifier
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .drawBehind {
                val revealed = -offset.value
                if (revealed <= 0f) return@drawBehind
                val inset = RemoveWellInset.toPx()
                val wellWidth = revealed - inset
                if (wellWidth <= 0f) return@drawBehind
                val armed = (revealed / (size.width * REMOVE_FRACTION)).coerceIn(0f, 1f)
                val wellLeft = size.width - inset - wellWidth
                drawRoundRect(
                    tint.copy(alpha = REMOVE_TINT_ALPHA + (REMOVE_ARMED_ALPHA - REMOVE_TINT_ALPHA) * armed * armed),
                    topLeft = Offset(wellLeft, inset),
                    size = Size(wellWidth, size.height - 2 * inset),
                    cornerRadius = CornerRadius(RemoveWellCorner.toPx()),
                )
                val icon = RemoveGlyphSize.toPx()
                val reveal = (wellWidth / (icon + 2 * inset)).coerceIn(0f, 1f)
                translate(left = wellLeft + (wellWidth - icon) / 2f, top = (size.height - icon) / 2f) {
                    with(bin) { draw(Size(icon, icon), alpha = reveal, colorFilter = glyph) }
                }
            }
            .draggable(
                orientation = Orientation.Horizontal,
                enabled = enabled,
                state = rememberDraggableState { delta ->
                    scope.launch { offset.snapTo((offset.value + delta).coerceIn(-width, 0f)) }
                },
                onDragStopped = { velocity ->
                    if (offset.value < -width * REMOVE_FRACTION || velocity < -1_500f) {
                        offset.animateTo(-width, tween(180))
                        onRemove()
                        delay(600)
                        offset.snapTo(0f)
                    } else {
                        offset.animateTo(0f, spring(dampingRatio = 0.8f))
                    }
                },
            ),
    ) {
        Box(Modifier.graphicsLayer { translationX = offset.value }) { content() }
    }
}

/** How far a row must be swiped to be removed on release. */
private const val REMOVE_FRACTION = 0.4f
private const val REMOVE_TINT_ALPHA = 0.22f
private const val REMOVE_ARMED_ALPHA = 0.42f
private val RemoveWellInset = 4.dp
private val RemoveWellCorner = 12.dp
private val RemoveGlyphSize = 22.dp

/**
 * A playback mode (shuffle, repeat) as a wide translucent capsule: a faint label tint with the label glyph when off, a
 * stronger tint with the accent glyph when on, so it follows the theme. Spoken as a switch with its state.
 */
@Composable
private fun ModeCapsule(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val on = stringResource(R.string.now_playing_on)
    val off = stringResource(R.string.now_playing_off)
    val colors = LocalTranslucentContentColors.current
    Box(
        modifier
            .height(44.dp)
            .clip(CapsuleShape)
            .background(colors.label.copy(alpha = if (active) 0.22f else 0.09f))
            .clickable(role = Role.Switch, onClick = onClick)
            .semantics {
                contentDescription = label
                stateDescription = if (active) on else off
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (active) colors.accent else colors.label, modifier = Modifier.size(20.dp))
    }
}
