package io.sonora.feature.dj.controls

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.feature.dj.R
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

/**
 * The CDJ tempo fader, laid horizontally (right is faster): a translucent capsule track with a capsule thumb of
 * the same translucency over it, showing [tempo] on its range ([PlaybackTempo.range]); 0 % is the centre.
 *
 * It is relative, like [io.sonora.core.designsystem.component.CapsuleSlider]: a touch never jumps the thumb, only the
 * finger's horizontal travel moves it, from where it is, and a plain tap changes nothing. 0 % is a sticky detent
 * ([TempoFaderMath.DETENT_ZONE]) announced with a haptic tick. Moving the finger up or down away from the fader enters
 * fine mode: the gain is halved, quartered, then divided by 8 ([TempoFaderMath.fineDivisor]), each level with a light
 * tick. A drag reports [onDragStart], then every position through [onDrag] (a fader fraction, -1..1, for a preview),
 * and ends with exactly one of [onRelease] (store it) or [onCancel] (a parent took the pointer; drop the preview).
 *
 * Accessibility: it is a slider over the range's percent ([onSetTempoPercent]) with custom actions to reset to 0 %
 * ([onReset]) and, when the playing track's BPM is known ([onNudge] not null), to nudge by ±0.1 BPM (direction -1 or
 * +1).
 *
 * The thumb moves in layout ([Modifier.offset] with a lambda) and the fill in draw, so a drag never recomposes the
 * fader itself; [tempo] is read in layout, draw and semantics only, so a caller that reads a state in it does not recompose
 * for a drag's every step. The fader is always laid out left to right: it is an instrument, not text.
 */
@Composable
fun TempoFader(
    tempo: () -> PlaybackTempo,
    onDragStart: () -> Unit,
    onDrag: (Double) -> Unit,
    onRelease: (Double) -> Unit,
    onCancel: () -> Unit,
    onSetTempoPercent: (Double) -> Unit,
    onReset: () -> Unit,
    onNudge: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
    /** The fader's height; the thumb and the track keep their margins inside it (a compact row passes less than the default 48 dp). */
    height: Dp = FaderHeight,
) {
    val faderDrag = remember { TempoFaderDrag() }
    val latestTempo by rememberUpdatedState(tempo)
    val start by rememberUpdatedState(onDragStart)
    val move by rememberUpdatedState(onDrag)
    val release by rememberUpdatedState(onRelease)
    val cancel by rememberUpdatedState(onCancel)
    val haptics = LocalHapticFeedback.current
    var widthPx by remember { mutableIntStateOf(0) }
    val shown: () -> Double = { if (faderDrag.dragging) faderDrag.fraction else TempoFaderMath.fractionOf(latestTempo()) }
    val lit by animateFloatAsState(if (faderDrag.dragging) 1f else 0f, spring(), label = "tempoThumbLit")

    val locale = LocalConfiguration.current.locales[0]
    val title = stringResource(R.string.dj_tempo_fader)
    val resetLabel = stringResource(R.string.dj_tempo_reset)
    val step = DjTempoText.nudgeStep(locale)
    val slowerLabel = stringResource(R.string.dj_tempo_nudge_down, step)
    val fasterLabel = stringResource(R.string.dj_tempo_nudge_up, step)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier
                // Its own layer: its fill and thumb move on every step of a drag, and a draw that shares its parent's layer
                // redraws the whole screen around it.
                .graphicsLayer {}
                .fillMaxWidth()
                .height(height)
                .onSizeChanged { widthPx = it.width }
                .semantics {
                    // Read here, not in composition: a drag changes the tempo on every pointer event, and the fader must not
                    // recompose (nor the controls around it) for each; only these semantics are made again.
                    val now = latestTempo()
                    val range = now.range
                    contentDescription = title
                    stateDescription = DjTempoText.tempoPercent(now.tempoPercent, locale)
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        now.tempoPercent.toFloat(),
                        (range.minBp - PlaybackTempo.NORMAL_SPEED_BASIS_POINTS) / 100f..(range.maxBp - PlaybackTempo.NORMAL_SPEED_BASIS_POINTS) / 100f,
                        steps = 0,
                    )
                    setProgress { target ->
                        onSetTempoPercent(target.toDouble())
                        true
                    }
                    customActions = buildList {
                        add(CustomAccessibilityAction(resetLabel) { onReset(); true })
                        if (onNudge != null) {
                            add(CustomAccessibilityAction(slowerLabel) { onNudge(-1); true })
                            add(CustomAccessibilityAction(fasterLabel) { onNudge(1); true })
                        }
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // A plain tap never crosses the slop, so it changes nothing.
                        val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            ?: return@awaitEachGesture
                        val travel = size.width - ThumbWidth.toPx()
                        val half = size.height / 2f
                        val fineStep = FineStep.toPx()
                        var divisor = TempoFaderMath.fineDivisor(slop.position.y - half, half, fineStep)
                        faderDrag.start(TempoFaderMath.fractionOf(latestTempo()))
                        start()
                        // Measured from the touch-down point, so the travel inside the touch slop counts too.
                        val firstTravel = TempoFaderMath.fractionDelta(slop.position.x - down.position.x, travel, divisor)
                        if (faderDrag.dragBy(firstTravel)) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        move(faderDrag.fraction)
                        val released = try {
                            drag(slop.id) { change ->
                                // Read the travel before consuming: a consumed change reports a zero positionChange().
                                val dx = change.positionChange().x
                                change.consume()
                                val level = TempoFaderMath.fineDivisor(change.position.y - half, half, fineStep)
                                if (level != divisor) {
                                    divisor = level
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                }
                                if (faderDrag.dragBy(TempoFaderMath.fractionDelta(dx, travel, divisor))) {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                }
                                move(faderDrag.fraction)
                            }
                        } catch (e: CancellationException) {
                            faderDrag.cancel()
                            cancel()
                            throw e
                        }
                        if (released) {
                            faderDrag.end()?.let { release(it) }
                        } else {
                            faderDrag.cancel()
                            cancel()
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            TranslucentSurface(
                Modifier.fillMaxWidth().height(minOf(TrackHeight, height - TrackMargin)),
                shape = CapsuleShape,
                elevation = 0.dp,
                darkTheme = true,
                tinted = false,
            ) {
                val colors = LocalTranslucentContentColors.current
                Canvas(Modifier.fillMaxSize()) {
                    drawTrack(shown(), ThumbWidth.toPx(), colors.label, colors.accent)
                }
            }
            TranslucentSurface(
                Modifier
                    .offset { IntOffset(TempoFaderMath.thumbOffsetPx(shown(), widthPx.toFloat(), ThumbWidth.toPx()).roundToInt(), 0) }
                    .size(ThumbWidth, minOf(ThumbHeight, height - ThumbMargin)),
                shape = CapsuleShape,
                elevation = ThumbElevation,
                darkTheme = true,
                tinted = false,
                // The light follows the thumb, as a real cap catches the room's light while it slides.
                lightShift = { shown().toFloat() },
                wash = { Color.White.copy(alpha = ThumbLitWash * lit) },
            ) {
                val colors = LocalTranslucentContentColors.current
                Canvas(Modifier.fillMaxSize()) {
                    // The cap's centre line; lit in the accent at exactly 0 %, as a CDJ lights its tempo reset lamp.
                    val atZero = shown() == 0.0
                    val w = GripWidth.toPx()
                    val h = GripHeight.toPx()
                    drawRoundRect(
                        if (atZero) colors.accent else colors.label.copy(alpha = 0.9f),
                        Offset((size.width - w) / 2f, (size.height - h) / 2f),
                        Size(w, h),
                        CornerRadius(w / 2f, w / 2f),
                    )
                }
            }
        }
    }
}

/**
 * The track's marks, in the thumb centre's coordinates: a centre tick for 0 %, quieter ticks at the half-way points,
 * and an accent bar from the centre to the thumb.
 */
private fun DrawScope.drawTrack(fraction: Double, thumbWidthPx: Float, label: Color, accent: Color) {
    fun x(f: Double) = TempoFaderMath.thumbOffsetPx(f, size.width, thumbWidthPx) + thumbWidthPx / 2f
    val cy = size.height / 2f
    val centre = x(0.0)
    val thumb = x(fraction)
    val bar = FillHeight.toPx()
    if (thumb != centre) {
        drawRoundRect(
            accent,
            Offset(minOf(centre, thumb), cy - bar / 2f),
            Size(kotlin.math.abs(thumb - centre), bar),
            CornerRadius(bar / 2f, bar / 2f),
        )
    }
    val tick = TickWidth.toPx()
    for (f in listOf(-0.5, 0.5)) {
        val h = MinorTickHeight.toPx()
        drawRoundRect(label.copy(alpha = 0.4f), Offset(x(f) - tick / 2f, cy - h / 2f), Size(tick, h), CornerRadius(tick / 2f, tick / 2f))
    }
    val h = CentreTickHeight.toPx()
    drawRoundRect(label.copy(alpha = 0.85f), Offset(centre - tick / 2f, cy - h / 2f), Size(tick, h), CornerRadius(tick / 2f, tick / 2f))
}

private val FaderHeight = 48.dp
private val TrackHeight = 22.dp

/** What a short fader takes off the track and the thumb: at least this much stays free around each. */
private val TrackMargin = 20.dp
private val ThumbMargin = 4.dp
private val ThumbWidth = 60.dp
private val ThumbHeight = 38.dp
private val ThumbElevation = 8.dp
private val GripWidth = 3.dp
private val GripHeight = 18.dp
private val FillHeight = 4.dp
private val TickWidth = 2.dp
private val CentreTickHeight = 12.dp
private val MinorTickHeight = 6.dp

/** How far the finger must move away from the fader for each finer gain level. */
private val FineStep = 40.dp

/** How much the thumb brightens while held. */
private const val ThumbLitWash = 0.10f
