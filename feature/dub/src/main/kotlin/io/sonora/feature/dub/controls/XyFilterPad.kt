package io.sonora.feature.dub.controls

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.TranslucentPill
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.theme.SonoraTheme
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.feature.dub.R
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The XY sweep filter pad (design §2 "XY filter", §4): a square whose X runs low-pass, flat (the detent, a true
 * bypass), high-pass and whose Y is the resonance ([XyMapping]). The finger is absolute: the crosshair sits under it,
 * a haptic tick marks the moment it reaches the flat detent (once per crossing), and on lift the pad glides to the
 * centre over 150 ms unless [locked]. The pad is controlled: it shows [position] and reports every new one, the glide's
 * frames included, through [onPositionChange]; the LOCK pill reports through [onLockedChange].
 *
 * Screen readers get two adjustable nodes, the sweep (X) and the resonance (Y), each with step actions through its
 * progress, plus a "Centre" action. It excludes the system back gesture, so an edge sweep never turns into a back.
 */
@Composable
fun XyFilterPad(
    position: XyPosition,
    onPositionChange: (XyPosition) -> Unit,
    locked: Boolean,
    onLockedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val latestPosition by rememberUpdatedState(position)
    val latestLocked by rememberUpdatedState(locked)
    val report by rememberUpdatedState(onPositionChange)
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val glide = remember { GlideJob() }

    val locale = LocalConfiguration.current.locales[0]
    val value = XyMapping.valueAt(position)
    val sweepTitle = stringResource(R.string.dub_xy_cutoff)
    val resonanceTitle = stringResource(R.string.dub_xy_resonance)
    val sweepText = sweepDescription(value, locale)
    val qText = stringResource(R.string.dub_xy_q, DubText.q(value.q, locale))
    val centreLabel = stringResource(R.string.dub_xy_centre)
    val centreAction = CustomAccessibilityAction(centreLabel) {
        glide.cancel()
        report(XyMapping.Centre)
        true
    }

    Box(modifier.aspectRatio(1f).alpha(if (enabled) 1f else DisabledAlpha)) {
        TranslucentSurface(
            Modifier.fillMaxSize().systemGestureExclusion(),
            shape = RoundedCornerShape(PadCorner),
            elevation = 0.dp,
            darkTheme = true,
            tinted = false,
        ) {
            val colors = LocalTranslucentContentColors.current
            val accent = SonoraTheme.accentOnDark
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            glide.cancel()
                            // The gesture's own position: the parent applies each report a frame later.
                            var current = latestPosition
                            fun follow(x: Float, y: Float) {
                                val touch = XyMapping.touch(current, x / size.width, 1f - y / size.height)
                                if (touch.hitsDetent) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                if (touch.position != current) {
                                    current = touch.position
                                    report(current)
                                }
                            }
                            follow(down.position.x, down.position.y)
                            try {
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.changedToUpIgnoreConsumed()) break
                                    follow(change.position.x, change.position.y)
                                    change.consume()
                                }
                            } catch (e: CancellationException) {
                                lift(latestLocked, current, glide, scope) { report(it) }
                                throw e
                            }
                            lift(latestLocked, current, glide, scope) { report(it) }
                        }
                    }
                    .clearAndSetSemantics { },
            ) {
                drawField(latestPosition, colors.label, accent)
            }
            EdgeLabel(stringResource(R.string.dub_xy_low_pass_edge), Modifier.align(Alignment.CenterStart).padding(start = LabelInset))
            EdgeLabel(stringResource(R.string.dub_xy_high_pass_edge), Modifier.align(Alignment.CenterEnd).padding(end = LabelInset))
            EdgeLabel(stringResource(R.string.dub_xy_resonance_edge), Modifier.align(Alignment.TopCenter).padding(top = LabelInset))
            // The two adjustable nodes for screen readers, laid over the pad with no touch handling of their own.
            Box(
                Modifier.fillMaxSize().semantics {
                    contentDescription = sweepTitle
                    stateDescription = sweepText
                    progressBarRangeInfo = ProgressBarRangeInfo(position.x, 0f..1f, steps = 0)
                    if (enabled) {
                        setProgress { target -> glide.cancel(); report(position.copy(x = target.coerceIn(0f, 1f))); true }
                        customActions = listOf(centreAction)
                    } else {
                        disabled()
                    }
                },
            )
            Box(
                Modifier.fillMaxSize().semantics {
                    contentDescription = resonanceTitle
                    stateDescription = qText
                    progressBarRangeInfo = ProgressBarRangeInfo(position.y, 0f..1f, steps = 0)
                    if (enabled) {
                        setProgress { target -> glide.cancel(); report(position.copy(y = target.coerceIn(0f, 1f))); true }
                        customActions = listOf(centreAction)
                    } else {
                        disabled()
                    }
                },
            )
        }
        LockPill(locked, onLockedChange, enabled, Modifier.align(Alignment.BottomEnd).padding(LockInset))
    }
}

/** The running glide, so a new touch or an action can stop it. */
private class GlideJob {
    var job: Job? = null

    fun cancel() {
        job?.cancel()
        job = null
    }
}

/** The finger lifted: glide to the centre unless LOCK holds the pad where it is. */
private fun lift(
    locked: Boolean,
    from: XyPosition,
    glide: GlideJob,
    scope: CoroutineScope,
    report: (XyPosition) -> Unit,
) {
    val target = XyMapping.liftTarget(locked) ?: return
    if (from == target) return
    glide.cancel()
    glide.job = scope.launch {
        val progress = Animatable(0f)
        progress.animateTo(1f, tween(XyMapping.GLIDE_MS, easing = LinearEasing)) {
            report(XyMapping.glide(from, target, this.value))
        }
    }
}

@Composable
private fun sweepDescription(value: XyFilterValue, locale: Locale): String = when (value.mode) {
    XyFilterMode.FLAT -> stringResource(R.string.dub_xy_flat)
    XyFilterMode.LOW_PASS -> stringResource(R.string.dub_xy_low_pass, DubText.frequency(value.cutoffHz, locale))
    XyFilterMode.HIGH_PASS -> stringResource(R.string.dub_xy_high_pass, DubText.frequency(value.cutoffHz, locale))
}

/** An edge label, left out on a pad too small to keep it clear of the centre and the LOCK pill. */
@Composable
private fun EdgeLabel(text: String, modifier: Modifier) {
    val colors = LocalTranslucentContentColors.current
    Text(
        text,
        Modifier.onlyOnPadsOf(EdgeLabelMinSide).then(modifier),
        color = colors.label.copy(alpha = LabelAlpha),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

/** Lays the element out only when the pad around it (the incoming constraints) is at least [minSide] on each side. */
private fun Modifier.onlyOnPadsOf(minSide: Dp): Modifier = layout { measurable, constraints ->
    val side = minOf(constraints.maxWidth, constraints.maxHeight)
    if (!XyMapping.showsEdgeLabels(side, minSide.roundToPx())) return@layout layout(0, 0) {}
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

@Composable
private fun LockPill(locked: Boolean, onLockedChange: (Boolean) -> Unit, enabled: Boolean, modifier: Modifier) {
    val label = stringResource(R.string.dub_xy_lock)
    val state = stringResource(if (locked) R.string.dub_xy_locked else R.string.dub_xy_unlocked)
    val accent = SonoraTheme.accentOnDark
    TranslucentPill(
        onClick = { if (enabled) onLockedChange(!locked) },
        modifier = modifier,
        height = LockHeight,
        darkTheme = true,
        wash = { if (locked) accent.copy(alpha = LockedWash) else Color.Transparent },
        contentModifier = Modifier.semantics { stateDescription = state },
    ) {
        val colors = LocalTranslucentContentColors.current
        Text(label, color = colors.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

/** The pad's field: the detent line down the middle, the quarter grid, and the crosshair with its dot. */
private fun DrawScope.drawField(position: XyPosition, label: Color, accent: Color) {
    val w = size.width
    val h = size.height
    val hairline = 1.dp.toPx()
    val grid = label.copy(alpha = GridAlpha)
    for (f in listOf(0.25f, 0.75f)) {
        drawLine(grid, Offset(w * f, 0f), Offset(w * f, h), hairline)
        drawLine(grid, Offset(0f, h * f), Offset(w, h * f), hairline)
    }
    drawLine(label.copy(alpha = DetentAlpha), Offset(w / 2f, 0f), Offset(w / 2f, h), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
    val cx = position.x.coerceIn(0f, 1f) * w
    val cy = (1f - position.y.coerceIn(0f, 1f)) * h
    val flat = XyMapping.valueAt(position).mode == XyFilterMode.FLAT
    val cross = if (flat) label.copy(alpha = 0.6f) else accent
    drawLine(cross.copy(alpha = 0.5f), Offset(cx, 0f), Offset(cx, h), hairline)
    drawLine(cross.copy(alpha = 0.5f), Offset(0f, cy), Offset(w, cy), hairline)
    drawCircle(cross, DotRadius.toPx(), Offset(cx, cy))
    drawCircle(label.copy(alpha = 0.9f), DotRadius.toPx(), Offset(cx, cy), style = Stroke(hairline * 1.5f))
}

private val PadCorner = 24.dp
private val LabelInset = 10.dp

// Under this the side labels reach the centre dot and HP meets the LOCK pill (about 96 dp); 120 dp leaves a margin.
private val EdgeLabelMinSide = 120.dp
private val LockInset = 8.dp
private val LockHeight = 32.dp
private val DotRadius = 12.dp
private const val LabelAlpha = 0.75f
private const val GridAlpha = 0.12f
private const val DetentAlpha = 0.35f
private const val LockedWash = 0.45f
private const val DisabledAlpha = 0.4f
