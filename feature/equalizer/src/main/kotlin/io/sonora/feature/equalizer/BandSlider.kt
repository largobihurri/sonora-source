package io.sonora.feature.equalizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.text.currentLocale
import io.sonora.core.domain.equalizer.EqualizerSettings
import kotlinx.coroutines.CancellationException

private val TrackWidth = 4.dp

/**
 * One band's vertical slider: its gain on top, the track ([trackHeight] tall, 0 dB in the middle, filled towards the
 * gain in the translucent accent) with a white [knob] as on iOS, then its frequency and, in four-band mode, its [name].
 * Drags are relative (the knob moves by the finger's travel, never to the touch point) and follow the finger
 * continuously; a double tap sets the band back to 0 dB. While the equalizer is off ([active] false) the track and
 * knob dim; the labels keep their contrast. [compactLabels] sets them a size smaller for narrow columns.
 */
@Composable
internal fun BandSlider(
    gain: Float,
    frequencyLabel: String,
    name: String?,
    description: String,
    active: Boolean,
    trackHeight: Dp,
    knob: DpSize,
    compactLabels: Boolean,
    onChange: (Float) -> Unit,
    onFinished: (Float) -> Unit,
    onCancelled: (originalGain: Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(gain)
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onFinished)
    val cancelled by rememberUpdatedState(onCancelled)
    val reset by rememberUpdatedState(onReset)
    val drag = remember { BandDrag({ current }, { change(it) }, { finished(it) }, { cancelled(it) }) }
    val shown = if (drag.dragging) drag.gain else gain
    val locale = currentLocale()
    val gainText = EqualizerText.gain(EqualizerSettings.quantize(shown), locale)
    val stateText = stringResource(R.string.equalizer_gain_db, gainText)
    val resetLabel = stringResource(R.string.equalizer_reset)
    val colors = LocalTranslucentContentColors.current
    val label = colors.label
    val accent = colors.accent
    val secondary = label.copy(alpha = SECONDARY_ALPHA)
    val darkTranslucent = label.luminance() > 0.5f
    val labelStyle = TextStyle(fontSize = if (compactLabels) 10.sp else 11.sp, lineHeight = SliderLabelLineHeight, textAlign = TextAlign.Center)
    // The gesture outlives recompositions (a mode change resizes the knob); it reads the latest size.
    val knobHeight by rememberUpdatedState(knob.height)

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(gainText, style = labelStyle, color = if (drag.dragging) label else secondary, maxLines = 1)
        Spacer(Modifier.height(SLIDER_LABEL_GAP))
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    stateDescription = stateText
                    progressBarRangeInfo = ProgressBarRangeInfo(shown, -MAX..MAX, steps = 0)
                    setProgress { target ->
                        finished(EqualizerSettings.quantize(target))
                        true
                    }
                    customActions = listOf(CustomAccessibilityAction(resetLabel) { reset(); true })
                }
                .pointerInput(Unit) {
                    var lastTapUptime = Long.MIN_VALUE / 2
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val slop = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        if (slop == null) {
                            // No drag: a released, unconsumed pointer that stayed put is a tap; two make a reset.
                            val up = currentEvent.changes.firstOrNull { it.id == down.id }
                            val tapped = up != null && !up.pressed && !up.isConsumed &&
                                (up.position - down.position).getDistance() < viewConfiguration.touchSlop
                            if (tapped) {
                                if (down.uptimeMillis - lastTapUptime <= viewConfiguration.doubleTapTimeoutMillis) {
                                    reset()
                                    lastTapUptime = Long.MIN_VALUE / 2
                                } else {
                                    lastTapUptime = up.uptimeMillis
                                }
                            }
                            return@awaitEachGesture
                        }
                        lastTapUptime = Long.MIN_VALUE / 2
                        val travel = size.height - knobHeight.toPx()
                        // Measured from the touch-down point, so the travel inside the touch slop counts too.
                        drag.start(trackHeightPx = travel, travelBeforeStartPx = slop.position.y - down.position.y)
                        val released = try {
                            verticalDrag(slop.id) { change ->
                                // Read the travel before consuming: a consumed change reports a zero positionChange().
                                val dy = change.positionChange().y
                                change.consume()
                                drag.dragBy(dy)
                            }
                        } catch (e: CancellationException) {
                            drag.cancel()
                            throw e
                        }
                        if (released) drag.end() else drag.cancel()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = if (active) 1f else INACTIVE_ALPHA }) {
                val knobH = knobHeight.toPx()
                val knobW = knob.width.toPx().coerceAtMost(size.width)
                val trackW = TrackWidth.toPx()
                val top = knobH / 2
                val travel = size.height - knobH
                val cx = size.width / 2
                fun y(db: Float) = top + (MAX - db.coerceIn(-MAX, MAX)) / (2 * MAX) * travel
                val zero = y(0f)
                val knobY = y(shown)
                val r = CornerRadius(trackW / 2, trackW / 2)
                drawRoundRect(label.copy(alpha = TRACK_ALPHA), Offset(cx - trackW / 2, top), Size(trackW, travel), r)
                drawLine(label.copy(alpha = 0.35f), Offset(cx - knobW / 2, zero), Offset(cx + knobW / 2, zero), 1.dp.toPx())
                val fillTop = minOf(zero, knobY)
                drawRoundRect(accent, Offset(cx - trackW / 2, fillTop), Size(trackW, kotlin.math.abs(knobY - zero)), r)
                // The knob: white as on iOS, on a soft shadow; on light translucency a hairline keeps its edge.
                val knobTopLeft = Offset(cx - knobW / 2, knobY - knobH / 2)
                val knobCorner = CornerRadius(knobH / 2, knobH / 2)
                val lift = 1.dp.toPx()
                drawRoundRect(
                    KnobShadow.copy(alpha = if (darkTranslucent) 0.35f else 0.16f),
                    knobTopLeft + Offset(-lift / 2, lift),
                    Size(knobW + lift, knobH + lift),
                    CornerRadius(knobH / 2 + lift, knobH / 2 + lift),
                )
                drawRoundRect(KnobColor, knobTopLeft, Size(knobW, knobH), knobCorner)
                if (!darkTranslucent) {
                    drawRoundRect(label.copy(alpha = 0.12f), knobTopLeft, Size(knobW, knobH), knobCorner, style = Stroke(0.75.dp.toPx()))
                }
            }
        }
        Spacer(Modifier.height(SLIDER_LABEL_GAP))
        Text(frequencyLabel, style = labelStyle, color = secondary, maxLines = 1)
        if (name != null) {
            Text(name, style = labelStyle, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private const val MAX = EqualizerSettings.MAX_GAIN_DB
private const val TRACK_ALPHA = 0.2f
internal val SLIDER_LABEL_GAP = 4.dp

/** A slider knob stays white on either theme, as the switch knob and iOS's own; its shadow is a plain dark veil. */
private val KnobColor = Color.White
private val KnobShadow = Color.Black
/** Secondary text, as a fraction of the translucent label colour (so it follows light and dark). */
private const val SECONDARY_ALPHA = 0.62f
