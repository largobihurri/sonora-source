package io.sonora.feature.dj.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.feature.dj.R
import io.sonora.feature.dj.waveform.DjWaveformPalette

/** What the loop controls do, as lambdas the caller wires to the view model. */
@Immutable
class DjLoopActions(
    val onIn: () -> Unit,
    val onOut: () -> Unit,
    val onExit: () -> Unit,
    val onReloop: () -> Unit,
    val onHalve: () -> Unit,
    val onDouble: () -> Unit,
    /** An arrow of the beat-length stepper: -1 for a shorter loop, +1 for a longer one. */
    val onStep: (Int) -> Unit,
    /** The middle of the stepper: a beat loop of the length it shows. */
    val onBeatLoop: () -> Unit,
    /** A hot cue pad was held while a loop plays: save the loop on that pad. */
    val onSaveToPad: (slot: Int) -> Unit,
) {
    companion object {
        /** Does nothing (previews and tests). */
        val NONE = DjLoopActions({}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

/**
 * The loop face of the pad panel's first row (a CDJ's loop section): IN, OUT, EXIT or RELOOP, 1/2X and 2X, [DjControlSizes.control]
 * tall. The lit buttons are washed in the loop colour: IN while a start is marked, OUT while a loop plays (softer while it waits
 * for the audio to reach its end). EXIT is RELOOP once the loop was left. A button that cannot act dims and keeps its place.
 */
@Composable
internal fun LoopTransportRow(loop: DjLoopPanelState, actions: DjLoopActions, sizes: DjControlSizes, modifier: Modifier = Modifier) {
    val unavailable = if (loop.available) null else stringResource(R.string.dj_loop_state_unavailable)
    val weights = DjPanelLayout.TransportWeights
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
        GridButton(
            label = stringResource(R.string.dj_loop_in),
            description = stringResource(R.string.dj_loop_in_description),
            onClick = actions.onIn,
            modifier = Modifier.weight(weights[0]),
            enabled = loop.inEnabled,
            height = sizes.control,
            wash = if (loop.inLit) LitWash else Color.Transparent,
            stateText = unavailable ?: if (loop.inLit) stringResource(R.string.dj_loop_state_marking) else null,
            selected = loop.inLit,
        )
        val outState = when {
            unavailable != null -> unavailable
            loop.armed -> stringResource(R.string.dj_loop_state_armed)
            loop.outLit -> stringResource(R.string.dj_loop_state_looping)
            else -> null
        }
        GridButton(
            label = stringResource(R.string.dj_loop_out),
            description = stringResource(R.string.dj_loop_out_description),
            onClick = actions.onOut,
            modifier = Modifier.weight(weights[1]),
            enabled = loop.outEnabled,
            height = sizes.control,
            wash = when {
                loop.armed -> ArmedWash
                loop.outLit -> LitWash
                else -> Color.Transparent
            },
            stateText = outState,
            selected = loop.outLit,
        )
        val exiting = loop.exitMode == LoopExitMode.EXIT
        GridButton(
            label = stringResource(if (exiting) R.string.dj_loop_exit else R.string.dj_loop_reloop),
            description = stringResource(if (exiting) R.string.dj_loop_exit_description else R.string.dj_loop_reloop_description),
            onClick = if (exiting) actions.onExit else actions.onReloop,
            modifier = Modifier.weight(weights[2]),
            enabled = loop.exitReloopEnabled,
            height = sizes.control,
            stateText = unavailable,
        )
        GridButton(
            label = stringResource(R.string.dj_loop_half),
            description = stringResource(R.string.dj_loop_half_description),
            onClick = actions.onHalve,
            modifier = Modifier.weight(weights[3]),
            enabled = loop.resizeEnabled,
            height = sizes.control,
            stateText = unavailable,
        )
        GridButton(
            label = stringResource(R.string.dj_loop_double),
            description = stringResource(R.string.dj_loop_double_description),
            onClick = actions.onDouble,
            modifier = Modifier.weight(weights[4]),
            enabled = loop.resizeEnabled,
            height = sizes.control,
            stateText = unavailable,
        )
    }
}

/**
 * The beat-length stepper of the loop face's second row: ‹ and › move one size (1/32 to 32 beats), the middle shows the size
 * (the loop's own while one plays) and starts a beat loop of it when tapped. Beat loops need a beat grid: without one the
 * stepper dims. [modifier] is given the room left after the switch and Q.
 */
@Composable
internal fun BeatLengthStepperRow(loop: DjLoopPanelState, actions: DjLoopActions, height: Dp, gap: Dp, modifier: Modifier = Modifier) {
    val unavailable = when {
        !loop.available -> stringResource(R.string.dj_loop_state_unavailable)
        !loop.beatsEnabled -> stringResource(R.string.dj_loop_beats_needs_grid)
        else -> null
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        GridButton(
            label = stringResource(R.string.dj_loop_beats_smaller_label),
            description = stringResource(R.string.dj_loop_beats_smaller),
            onClick = { actions.onStep(-1) },
            modifier = Modifier.width(DjPanelLayout.StepperArrowWidth),
            enabled = loop.canStepDown,
            height = height,
            stateText = unavailable,
        )
        BeatLengthValue(loop, unavailable, actions.onBeatLoop, height, Modifier.weight(1f))
        GridButton(
            label = stringResource(R.string.dj_loop_beats_larger_label),
            description = stringResource(R.string.dj_loop_beats_larger),
            onClick = { actions.onStep(+1) },
            modifier = Modifier.width(DjPanelLayout.StepperArrowWidth),
            enabled = loop.canStepUp,
            height = height,
            stateText = unavailable,
        )
    }
}

/** The stepper's middle: the size in beats ("4") over its unit, a button that starts a beat loop of that size. */
@Composable
private fun BeatLengthValue(loop: DjLoopPanelState, unavailable: String?, onClick: () -> Unit, height: Dp, modifier: Modifier) {
    val length = BeatLengthStepper.label(loop.beats)
    val description = stringResource(R.string.dj_loop_beats_description, length)
    val enabled = loop.beatsEnabled
    val wash = when {
        !loop.beatsLit -> Color.Transparent
        loop.armed -> ArmedWash
        else -> LitWash
    }
    TranslucentSurface(
        modifier.height(height),
        shape = CapsuleShape,
        elevation = 0.dp,
        darkTheme = true,
        tinted = false,
        wash = { wash },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CapsuleShape)
                .semantics {
                    contentDescription = description
                    if (unavailable != null) stateDescription = unavailable
                    if (loop.beatsLit) selected = true
                    if (!enabled) disabled()
                }
                .selectable(selected = loop.beatsLit, enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val colors = LocalTranslucentContentColors.current
            val ink = if (enabled) colors.label else colors.label.copy(alpha = DisabledAlpha)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(length, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    stringResource(R.string.dj_loop_beats_unit),
                    Modifier.padding(start = 3.dp, bottom = 1.dp),
                    color = ink.copy(alpha = UnitAlpha),
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The `Cues | Loop` switch, a translucent capsule of two segments ([DjPanelLayout.ModeSwitchWidth] wide) that swaps the pad panel's
 * face. The segment of the mode in force is washed in the accent colour and announced as selected.
 */
@Composable
internal fun PadModeSwitch(mode: DjPadMode, onMode: (DjPadMode) -> Unit, height: Dp, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.dj_loop_mode_description)
    TranslucentSurface(
        modifier.width(DjPanelLayout.ModeSwitchWidth).height(height),
        shape = CapsuleShape,
        elevation = 0.dp,
        darkTheme = true,
        tinted = false,
    ) {
        Row(Modifier.fillMaxSize().selectableGroup().semantics { contentDescription = description }) {
            ModeSegment(stringResource(R.string.dj_loop_mode_cues), mode == DjPadMode.CUES) { onMode(DjPadMode.CUES) }
            ModeSegment(stringResource(R.string.dj_loop_mode_loop), mode == DjPadMode.LOOP) { onMode(DjPadMode.LOOP) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ModeSegment(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalTranslucentContentColors.current
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(2.dp)
            .clip(CapsuleShape)
            .background(if (selected) colors.accent.copy(alpha = SelectedSegmentAlpha) else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = SegmentStyle.copy(color = if (selected) colors.accent else colors.label.copy(alpha = UnselectedAlpha)),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = SegmentStyle.fontSize, stepSize = 0.5.sp),
        )
    }
}

/** The loop colour as a wash on the translucency: strong while the loop plays, soft while it waits for its end. */
private val LoopColor = Color(DjWaveformPalette.LOOP_COLOR)
private val LitWash = LoopColor.copy(alpha = 0.55f)
private val ArmedWash = LoopColor.copy(alpha = 0.30f)

private val SegmentStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
private const val SelectedSegmentAlpha = 0.28f
private const val UnselectedAlpha = 0.6f
private const val UnitAlpha = 0.7f
private const val DisabledAlpha = 0.38f
