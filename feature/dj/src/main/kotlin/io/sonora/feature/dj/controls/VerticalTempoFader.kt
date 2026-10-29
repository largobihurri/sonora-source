package io.sonora.feature.dj.controls

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.feature.dj.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

/**
 * The CDJ pitch fader, standing upright (tablets, T6): the same instrument as [TempoFader], turned a quarter, in a Pioneer CDJ's
 * direction: "-" on top and "+" at the bottom, so pulling the thumb towards you speeds up; 0 % is the middle, with its
 * sticky detent. It shows [tempo] on its range and its math is [TempoFaderMath]'s vertical half, so the tempo logic is the horizontal fader's exactly: relative like iOS
 * sliders (a touch never jumps the thumb, a plain tap changes nothing), the 0 % detent with its haptic tick, and fine mode,
 * entered here by moving the finger sideways away from the fader (the gain halved, quartered, then divided by 8, each level
 * with a light tick). A drag reports [onDragStart], every position through [onDrag] and ends with exactly one of
 * [onRelease] or [onCancel], as [TempoFader] does; the semantics (a slider over the percent, reset, ±0.1 BPM nudges) are the
 * same too. [tempo] is read in layout, draw and semantics only, so a caller that reads a state in it does not recompose for
 * a drag's every step.
 *
 * Give it the height to stand tall (the caller's [modifier] sets the width and the height); the track keeps the thumb's
 * margins inside it. The thumb moves in layout and the fill in draw, so a drag never recomposes the fader itself.
 */
@Composable
fun VerticalTempoFader(
    tempo: () -> PlaybackTempo,
    onDragStart: () -> Unit,
    onDrag: (Double) -> Unit,
    onRelease: (Double) -> Unit,
    onCancel: () -> Unit,
    onSetTempoPercent: (Double) -> Unit,
    onReset: () -> Unit,
    onNudge: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
    /** The fader's width; the thumb is a little narrower than it. */
    width: Dp = FaderWidth,
) {
    val faderDrag = remember { TempoFaderDrag() }
    val latestTempo by rememberUpdatedState(tempo)
    val start by rememberUpdatedState(onDragStart)
    val move by rememberUpdatedState(onDrag)
    val release by rememberUpdatedState(onRelease)
    val cancel by rememberUpdatedState(onCancel)
    val haptics = LocalHapticFeedback.current
    var heightPx by remember { mutableIntStateOf(0) }
    val shown: () -> Double = { if (faderDrag.dragging) faderDrag.fraction else TempoFaderMath.fractionOf(latestTempo()) }
    val lit by animateFloatAsState(if (faderDrag.dragging) 1f else 0f, spring(), label = "verticalTempoThumbLit")

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
                .width(width)
                .fillMaxHeight()
                .onSizeChanged { heightPx = it.height }
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
                        val slop = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            ?: return@awaitEachGesture
                        val travel = size.height - ThumbHeight.toPx()
                        val half = size.width / 2f
                        val fineStep = FineStep.toPx()
                        var divisor = TempoFaderMath.fineDivisor(slop.position.x - half, half, fineStep)
                        faderDrag.start(TempoFaderMath.fractionOf(latestTempo()))
                        start()
                        // Measured from the touch-down point, so the travel inside the touch slop counts too.
                        val firstTravel = TempoFaderMath.verticalFractionDelta(slop.position.y - down.position.y, travel, divisor)
                        if (faderDrag.dragBy(firstTravel)) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        move(faderDrag.fraction)
                        val released = try {
                            drag(slop.id) { change ->
                                // Read the travel before consuming: a consumed change reports a zero positionChange().
                                val dy = change.positionChange().y
                                change.consume()
                                val level = TempoFaderMath.fineDivisor(change.position.x - half, half, fineStep)
                                if (level != divisor) {
                                    divisor = level
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                }
                                if (faderDrag.dragBy(TempoFaderMath.verticalFractionDelta(dy, travel, divisor))) {
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
            contentAlignment = Alignment.TopCenter,
        ) {
            TranslucentSurface(
                Modifier.width(minOf(TrackWidth, width - TrackMargin)).fillMaxHeight(),
                shape = CapsuleShape,
                elevation = 0.dp,
                darkTheme = true,
                tinted = false,
            ) {
                val colors = LocalTranslucentContentColors.current
                Canvas(Modifier.fillMaxSize()) {
                    drawTrack(shown(), ThumbHeight.toPx(), colors.label, colors.accent)
                }
            }
            TranslucentSurface(
                Modifier
                    .offset { IntOffset(0, TempoFaderMath.verticalThumbOffsetPx(shown(), heightPx.toFloat(), ThumbHeight.toPx()).roundToInt()) }
                    .size(minOf(ThumbWidth, width - ThumbMargin), ThumbHeight),
                shape = CapsuleShape,
                elevation = ThumbElevation,
                darkTheme = true,
                tinted = false,
                wash = { Color.White.copy(alpha = ThumbLitWash * lit) },
            ) {
                val colors = LocalTranslucentContentColors.current
                Canvas(Modifier.fillMaxSize()) {
                    // The cap's centre line, across the travel; lit in the accent at exactly 0 %, as a CDJ lights its reset lamp.
                    val atZero = shown() == 0.0
                    val w = GripLength.toPx()
                    val h = GripThickness.toPx()
                    drawRoundRect(
                        if (atZero) colors.accent else colors.label.copy(alpha = 0.9f),
                        Offset((size.width - w) / 2f, (size.height - h) / 2f),
                        Size(w, h),
                        CornerRadius(h / 2f, h / 2f),
                    )
                }
            }
        }
    }
}

/**
 * The track's marks, at the thumb centre's heights: a centre tick for 0 %, quieter ticks at the half-way points, and an
 * accent bar from the centre to the thumb.
 */
private fun DrawScope.drawTrack(fraction: Double, thumbHeightPx: Float, label: Color, accent: Color) {
    fun y(f: Double) = TempoFaderMath.verticalMarkY(f, size.height, thumbHeightPx)
    val cx = size.width / 2f
    val centre = y(0.0)
    val thumb = y(fraction)
    val bar = FillWidth.toPx()
    if (thumb != centre) {
        drawRoundRect(
            accent,
            Offset(cx - bar / 2f, minOf(centre, thumb)),
            Size(bar, abs(thumb - centre)),
            CornerRadius(bar / 2f, bar / 2f),
        )
    }
    val tick = TickThickness.toPx()
    for (f in listOf(-0.5, 0.5)) {
        val w = MinorTickLength.toPx()
        drawRoundRect(label.copy(alpha = 0.4f), Offset(cx - w / 2f, y(f) - tick / 2f), Size(w, tick), CornerRadius(tick / 2f, tick / 2f))
    }
    val w = CentreTickLength.toPx()
    drawRoundRect(label.copy(alpha = 0.85f), Offset(cx - w / 2f, centre - tick / 2f), Size(w, tick), CornerRadius(tick / 2f, tick / 2f))
}

private val FaderWidth = 72.dp
private val TrackWidth = 22.dp

/** What a narrow fader takes off the track and the thumb: at least this much stays free around each. */
private val TrackMargin = 24.dp
private val ThumbMargin = 8.dp
private val ThumbWidth = 56.dp
private val ThumbHeight = 64.dp
private val ThumbElevation = 8.dp
private val GripLength = 22.dp
private val GripThickness = 3.dp
private val FillWidth = 4.dp
private val TickThickness = 2.dp
private val CentreTickLength = 12.dp
private val MinorTickLength = 6.dp

/** How far the finger must move sideways from the fader for each finer gain level. */
private val FineStep = 40.dp

/** How much the thumb brightens while held. */
private const val ThumbLitWash = 0.10f
