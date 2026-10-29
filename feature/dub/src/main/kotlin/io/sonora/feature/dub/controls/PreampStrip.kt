package io.sonora.feature.dub.controls

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.core.designsystem.theme.SonoraTheme
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.feature.dub.R
import kotlinx.coroutines.CancellationException

/**
 * One way of the five-way DUB preamp (design §10.1, §10.2), as a vertical strip: the way's name, its high-pass and
 * low-pass corners (drag up or down to set, double tap to reset to [way]'s default, value in Hz or kHz, "Off" at the
 * end of the range), a vertical level fader with a sticky 0 dB detent and a haptic tick ([FaderMapping]), and a large
 * kill switch (tap toggles, hold is momentary). Values preview while dragging (`final` false) and are kept on release
 * (`final` true). The high-pass never passes the low-pass. For screen readers the strip announces its way, corners,
 * level and kill state, with actions to kill or bring it back and to reset its filters and level; each control is
 * also adjustable on its own.
 */
@Composable
fun PreampStrip(
    way: DubWay,
    highPassHz: Float,
    lowPassHz: Float,
    levelDb: Double,
    killed: Boolean,
    onHighPassChange: (hz: Float, final: Boolean) -> Unit,
    onLowPassChange: (hz: Float, final: Boolean) -> Unit,
    onLevelChange: (db: Double, final: Boolean) -> Unit,
    onKilledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    width: Dp = StripWidth,
) {
    val locale = LocalConfiguration.current.locales[0]
    val name = stringResource(way.label)
    val title = stringResource(R.string.dub_way_title, way.number, name)
    val off = stringResource(R.string.dub_corner_off)
    val hpText = if (CornerMapping.isHighPassOff(highPassHz)) off else DubText.frequency(highPassHz, locale)
    val lpText = if (CornerMapping.isLowPassOff(lowPassHz)) off else DubText.frequency(lowPassHz, locale)
    val levelText = DubText.decibels(levelDb, locale)
    val corners = stringResource(R.string.dub_way_state, hpText, lpText, levelText)
    val killState = stringResource(if (killed) R.string.dub_killed else R.string.dub_live)
    val killLabel = stringResource(if (killed) R.string.dub_unkill_action else R.string.dub_kill_action)
    val resetLabel = stringResource(R.string.dub_reset_way)

    Column(
        modifier
            .width(width)
            .alpha(if (enabled) 1f else DisabledAlpha)
            .semantics {
                isTraversalGroup = true
                contentDescription = title
                stateDescription = "$corners, $killState"
                if (enabled) {
                    customActions = listOf(
                        CustomAccessibilityAction(killLabel) { onKilledChange(!killed); true },
                        CustomAccessibilityAction(resetLabel) {
                            onHighPassChange(way.defaultHighPassHz, true)
                            onLowPassChange(way.defaultLowPassHz, true)
                            onLevelChange(0.0, true)
                            true
                        },
                    )
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(StripGap),
    ) {
        Text(
            name,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            minLines = 2,
        )
        CornerControl(
            shortLabel = stringResource(R.string.dub_corner_high_pass_short),
            title = stringResource(R.string.dub_high_pass),
            hz = highPassHz,
            valueText = hpText,
            defaultHz = way.defaultHighPassHz,
            clamp = { CornerMapping.clampHighPass(it, lowPassHz) },
            onChange = onHighPassChange,
            enabled = enabled,
        )
        CornerControl(
            shortLabel = stringResource(R.string.dub_corner_low_pass_short),
            title = stringResource(R.string.dub_low_pass),
            hz = lowPassHz,
            valueText = lpText,
            defaultHz = way.defaultLowPassHz,
            clamp = { CornerMapping.clampLowPass(it, highPassHz) },
            onChange = onLowPassChange,
            enabled = enabled,
        )
        LevelFader(
            levelDb = levelDb,
            levelText = levelText,
            onLevelChange = onLevelChange,
            enabled = enabled,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Text(levelText, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        KillSwitch(
            killed = killed,
            onKilledChange = onKilledChange,
            label = stringResource(R.string.dub_kill),
            description = title,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(KillHeight),
        )
    }
}

/**
 * A corner frequency: a small capsule showing [shortLabel] and [valueText]. A vertical drag moves it along the log map
 * (up is higher, [CornerSpan] of travel for the whole range), clamped by [clamp]; a double tap resets it to [defaultHz].
 */
@Composable
private fun CornerControl(
    shortLabel: String,
    title: String,
    hz: Float,
    valueText: String,
    defaultHz: Float,
    clamp: (Float) -> Float,
    onChange: (hz: Float, final: Boolean) -> Unit,
    enabled: Boolean,
) {
    val latestHz by rememberUpdatedState(hz)
    val latestClamp by rememberUpdatedState(clamp)
    val change by rememberUpdatedState(onChange)
    val haptics = LocalHapticFeedback.current
    val resetLabel = stringResource(R.string.dub_reset_corner)
    TranslucentSurface(
        Modifier
            .fillMaxWidth()
            .height(CornerHeight)
            .semantics {
                contentDescription = title
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(CornerMapping.positionOf(hz), 0f..1f, steps = 0)
                if (enabled) {
                    setProgress { target -> onChange(clamp(CornerMapping.hzOf(target)), true); true }
                    customActions = listOf(CustomAccessibilityAction(resetLabel) { onChange(clamp(defaultHz), true); true })
                } else {
                    disabled()
                }
            },
        shape = RoundedCornerShape(CornerRadiusDp),
        elevation = 0.dp,
        darkTheme = true,
        tinted = false,
    ) {
        val colors = LocalTranslucentContentColors.current
        Column(
            Modifier
                .fillMaxSize()
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onDoubleTap = {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            change(latestClamp(defaultHz), true)
                        },
                    )
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val slop = awaitVerticalTouchSlopOrCancellation(down.id) { c, _ -> c.consume() } ?: return@awaitEachGesture
                        val span = CornerSpan.toPx()
                        var current = latestClamp(CornerMapping.dragBy(latestHz, -(slop.position.y - down.position.y) / span))
                        change(current, false)
                        try {
                            verticalDrag(slop.id) { c ->
                                val dy = c.positionChange().y
                                c.consume()
                                current = latestClamp(CornerMapping.dragBy(current, -dy / span))
                                change(current, false)
                            }
                        } catch (e: CancellationException) {
                            change(current, true)
                            throw e
                        }
                        // Released or cancelled, the corner stays where the sweep got to.
                        change(current, true)
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(shortLabel, color = colors.label.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text(valueText, color = colors.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/**
 * The way's level fader, upright: minus infinity at the bottom, +6 dB at the top, 0 dB at three quarters with a
 * sticky detent and a haptic tick ([FaderMapping], [DetentDrag]). Relative, like the capsule sliders: a touch never jumps the
 * thumb and a plain tap changes nothing.
 */
@Composable
private fun LevelFader(
    levelDb: Double,
    levelText: String,
    onLevelChange: (db: Double, final: Boolean) -> Unit,
    enabled: Boolean,
    modifier: Modifier,
) {
    val drag = remember { DetentDrag(FaderMapping.axis) }
    val latestDb by rememberUpdatedState(levelDb)
    val change by rememberUpdatedState(onLevelChange)
    val haptics = LocalHapticFeedback.current
    val title = stringResource(R.string.dub_level)
    val resetLabel = stringResource(R.string.dub_reset_level)
    val shown: () -> Double = { if (drag.dragging) drag.value else FaderMapping.positionOf(latestDb) }
    val lit by animateFloatAsState(if (drag.dragging) 1f else 0f, spring(), label = "dubLevelThumbLit")
    val accent = SonoraTheme.accentOnDark

    Box(
        modifier
            .semantics {
                contentDescription = title
                stateDescription = levelText
                progressBarRangeInfo = ProgressBarRangeInfo(FaderMapping.positionOf(levelDb).toFloat(), 0f..1f, steps = 0)
                if (enabled) {
                    setProgress { target -> onLevelChange(FaderMapping.dbOf(target.toDouble()), true); true }
                    customActions = listOf(CustomAccessibilityAction(resetLabel) { onLevelChange(0.0, true); true })
                } else {
                    disabled()
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = awaitVerticalTouchSlopOrCancellation(down.id) { c, _ -> c.consume() } ?: return@awaitEachGesture
                    val travel = (size.height - ThumbHeight.toPx()).coerceAtLeast(1f)
                    drag.start(FaderMapping.positionOf(latestDb))
                    if (drag.dragBy(-(slop.position.y - down.position.y).toDouble() / travel)) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                    change(FaderMapping.dbOf(drag.value), false)
                    try {
                        verticalDrag(slop.id) { c ->
                            val dy = c.positionChange().y
                            c.consume()
                            if (drag.dragBy(-dy.toDouble() / travel)) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            change(FaderMapping.dbOf(drag.value), false)
                        }
                    } catch (e: CancellationException) {
                        drag.end()?.let { change(FaderMapping.dbOf(it), true) }
                        throw e
                    }
                    // Released or cancelled, the level stays where the finger left it.
                    drag.end()?.let { change(FaderMapping.dbOf(it), true) }
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        TranslucentSurface(
            Modifier.width(TrackWidth).fillMaxSize(),
            shape = CapsuleShape,
            elevation = 0.dp,
            darkTheme = true,
            tinted = false,
        ) {
            val colors = LocalTranslucentContentColors.current
            Canvas(Modifier.fillMaxSize()) { drawLevelTrack(shown(), ThumbHeight.toPx(), colors.label, accent) }
        }
        Canvas(Modifier.fillMaxSize()) {
            val thumb = ThumbHeight.toPx()
            val top = thumbTop(shown(), size.height, thumb)
            val w = minOf(ThumbWidth.toPx(), size.width - 4.dp.toPx())
            val atUnity = shown() == FaderMapping.UNITY_POSITION
            drawRoundRect(
                Color.White.copy(alpha = ThumbAlpha + ThumbLitAlpha * lit),
                Offset((size.width - w) / 2f, top),
                Size(w, thumb),
                CornerRadius(thumb / 2f, thumb / 2f),
            )
            val grip = 3.dp.toPx()
            drawRoundRect(
                if (atUnity) accent else Color.Black.copy(alpha = 0.6f),
                Offset((size.width - w * 0.6f) / 2f, top + (thumb - grip) / 2f),
                Size(w * 0.6f, grip),
                CornerRadius(grip / 2f, grip / 2f),
            )
        }
    }
}

/** The thumb's top edge for [position] 0..1 (1 at the top). */
private fun thumbTop(position: Double, heightPx: Float, thumbPx: Float): Float {
    val travel = (heightPx - thumbPx).coerceAtLeast(0f)
    return ((1.0 - position.coerceIn(0.0, 1.0)) * travel).toFloat()
}

/** The track: an accent fill from the bottom to the thumb, and the 0 dB tick across it. */
private fun DrawScope.drawLevelTrack(position: Double, thumbPx: Float, label: Color, accent: Color) {
    val cx = size.width / 2f
    val bar = 4.dp.toPx()
    val thumbCentre = thumbTop(position, size.height, thumbPx) + thumbPx / 2f
    val bottom = size.height - thumbPx / 2f
    if (bottom > thumbCentre) {
        drawRoundRect(accent, Offset(cx - bar / 2f, thumbCentre), Size(bar, bottom - thumbCentre), CornerRadius(bar / 2f, bar / 2f))
    }
    val unity = thumbTop(FaderMapping.UNITY_POSITION, size.height, thumbPx) + thumbPx / 2f
    val tick = 2.dp.toPx()
    val w = size.width * 0.8f
    drawRoundRect(label.copy(alpha = 0.85f), Offset(cx - w / 2f, unity - tick / 2f), Size(w, tick), CornerRadius(tick / 2f, tick / 2f))
}

/** The strip's width on a phone page; five of them and their gaps fit 360 dp. */
val StripWidth = 64.dp
private val StripGap = 8.dp
private val CornerHeight = 44.dp
private val CornerRadiusDp = 12.dp
private val CornerSpan = 240.dp
private val KillHeight = 56.dp
private val TrackWidth = 18.dp
private val ThumbWidth = 44.dp
private val ThumbHeight = 28.dp
private const val ThumbAlpha = 0.8f
private const val ThumbLitAlpha = 0.2f
private const val DisabledAlpha = 0.4f
