package io.sonora.feature.dj.controls

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.component.TranslucentPill
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.core.djmodel.DjAnalysis
import io.sonora.core.djmodel.GridEdit
import io.sonora.core.djmodel.GridEdits
import io.sonora.core.djmodel.TapTempo
import io.sonora.feature.dj.BpmEntry
import io.sonora.feature.dj.GridEditCommand
import io.sonora.feature.dj.GridEditText
import io.sonora.feature.dj.R
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The beat grid editor: a compact panel of dark translucent controls that takes the place of the tempo section and the
 * volume row while it is open, so the waveform and its red bar lines stay in view above it and move as each button is
 * pressed. From the top: the title (with the [GridEditedChip] while the grid is edited) and Done; the track's own
 * tempo with two decimals between −1 / −0.01 and +0.01 / +1 (the four step buttons repeat, faster the longer they are
 * held; tapping the value opens a numeric entry, see [BpmMode]); halve, double, Tap tempo and Reset; the bar start
 * (a beat earlier, here, a beat later: the phrase moves with it); the phrase start (a bar earlier, a bar later: the
 * bar lines stay); and the whole-grid nudge in milliseconds.
 *
 * [analysis] carries the edit ([DjAnalysis.gridEdit]); every button reports its [GridEditCommand] with the playhead's
 * position read from [position] at the moment of the tap (the same position the waveform draws at), which "Set bar
 * start here" uses to pick the beat. Nothing here writes: the caller saves the edit and the analysis comes back with
 * it.
 *
 * The tempo row has three faces and the editor is still six rows tall: the steps, the typed entry and the tapped tempo
 * (Cancel, the value, Set; the Tap button stays in the row below). A typed or tapped tempo goes to the beat grid only on
 * Set (the keyboard's Done in the entry), through [GridEditCommand.SetBpm] like every other change, so the waveform does not jump with each tap
 * while the estimate settles. A typed tempo that is not valid is refused and the reason replaces the title.
 *
 * Like the tempo section these are translucent pills over Now Playing's artwork backdrop (the caller provides
 * `LocalTranslucentBackdrop`), always dark, with accessibility labels that say what each does.
 */
@Composable
fun DjGridEditor(
    analysis: DjAnalysis,
    position: () -> Double,
    onCommand: (GridEditCommand, positionMs: Double) -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    /** The row heights and gaps: the editor is [DjControlSizes.gridEditor] tall (D4.1b). */
    sizes: DjControlSizes = DjControlSizes.Comfortable,
) {
    val locale = LocalConfiguration.current.locales[0]
    val edit = analysis.gridEdit
    // The track's own tempo; a track with no grid starts from the one the first edit gives it.
    val bpm = edit?.bpm ?: analysis.grid?.bpmAt(0.0) ?: (GridEdit.DEFAULT_BPM_HUNDREDTHS / HUNDREDTHS)
    val hundredths = edit?.bpmHundredths ?: (bpm * HUNDREDTHS).roundToInt()
    val send: (GridEditCommand) -> Unit = { onCommand(it, position()) }
    val panel = stringResource(R.string.dj_grid_panel_description)
    val haptics = LocalHapticFeedback.current

    var mode by rememberSaveable { mutableStateOf(BpmMode.Steps) }
    var problem by remember { mutableStateOf<BpmEntry.Problem?>(null) }
    val tapTempo = remember { TapTempo { SystemClock.elapsedRealtimeNanos() } }
    var taps by remember { mutableIntStateOf(0) }
    var tapped by remember { mutableStateOf<Int?>(null) }
    val backToSteps = {
        mode = BpmMode.Steps
        problem = null
        tapTempo.reset()
        taps = 0
        tapped = null
    }
    val applyBpm = { tempo: Int ->
        send(GridEditCommand.SetBpm(tempo))
        backToSteps()
    }
    // A finger landing on Tap counts at that moment (the click would come when it lifts); a click that was not preceded
    // by a touch (TalkBack, a keyboard) counts too.
    val tapGate = remember { PressClickGate() }
    val registerTap = {
        if (mode != BpmMode.Tap) {
            backToSteps()
            mode = BpmMode.Tap
        }
        tapped = tapTempo.tap()
        taps = tapTempo.tapCount
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }
    Column(
        modifier.fillMaxWidth().semantics { paneTitle = panel },
        verticalArrangement = Arrangement.spacedBy(sizes.gap),
    ) {
        Row(Modifier.fillMaxWidth().height(sizes.control), verticalAlignment = Alignment.CenterVertically) {
            // The title is a short word that shrinks (down to 12 sp) before it is ever cut; the marker and Done keep their
            // size. Read out in full ("Beat grid editor"), as the short word is only for the eye. A typed tempo that was
            // refused takes its place with the reason (two lines at most), announced when it appears.
            Row(Modifier.weight(1f).padding(end = ItemGap), verticalAlignment = Alignment.CenterVertically) {
                val refusal = problem
                if (refusal != null) {
                    BasicText(
                        bpmProblemText(refusal, locale),
                        Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                        style = MessageStyle.copy(color = ErrorInk),
                        maxLines = 2,
                        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MessageStyle.fontSize, stepSize = 0.5.sp),
                    )
                } else {
                    BasicText(
                        stringResource(R.string.dj_grid_title),
                        Modifier.weight(1f, fill = false).semantics { contentDescription = panel },
                        style = TitleStyle.copy(color = Color.White),
                        maxLines = 1,
                        softWrap = false,
                        autoSize = TextAutoSize.StepBased(minFontSize = TitleMinSize, maxFontSize = TitleStyle.fontSize, stepSize = 0.5.sp),
                    )
                    if (edit != null) GridEditedChip(Modifier.padding(start = 10.dp))
                }
            }
            GridButton(
                label = stringResource(R.string.dj_grid_done),
                description = stringResource(R.string.dj_grid_done_description),
                onClick = onDone,
                modifier = Modifier.width(DoneWidth),
                height = sizes.control,
            )
        }
        when (mode) {
            BpmMode.Steps -> Row(Modifier.fillMaxWidth().height(sizes.control), horizontalArrangement = Arrangement.spacedBy(ItemGap), verticalAlignment = Alignment.CenterVertically) {
                for (step in BigAndSmallDown) BpmStepButton(step, locale, send, sizes.control, Modifier.weight(1f))
                BpmValue(bpm, locale, onClick = { mode = BpmMode.Entry }, modifier = Modifier.weight(BpmValueWeight))
                for (step in SmallAndBigUp) BpmStepButton(step, locale, send, sizes.control, Modifier.weight(1f))
            }
            BpmMode.Entry -> BpmEntryRow(
                initialText = GridEditText.bpm(bpm, locale),
                rejected = problem != null,
                onEdited = { problem = null },
                onSubmit = { text ->
                    when (val result = BpmEntry.parse(text)) {
                        is BpmEntry.Result.Valid -> applyBpm(result.bpmHundredths)
                        is BpmEntry.Result.Invalid -> problem = result.problem
                    }
                },
                onCancel = backToSteps,
                height = sizes.control,
            )
            BpmMode.Tap -> BpmTapRow(
                proposalHundredths = tapped,
                taps = taps,
                minTaps = TapTempo.MIN_TAPS,
                locale = locale,
                onApply = applyBpm,
                onCancel = backToSteps,
                height = sizes.control,
            )
        }
        Row(Modifier.fillMaxWidth().height(sizes.control), horizontalArrangement = Arrangement.spacedBy(ItemGap), verticalAlignment = Alignment.CenterVertically) {
            GridButton(
                label = stringResource(R.string.dj_grid_halve_label),
                description = stringResource(R.string.dj_grid_halve),
                onClick = {
                    backToSteps()
                    send(GridEditCommand.HalveBpm)
                },
                modifier = Modifier.weight(HalveDoubleWeight),
                height = sizes.control,
                enabled = GridEdits.canHalve(hundredths),
            )
            GridButton(
                label = stringResource(R.string.dj_grid_double_label),
                description = stringResource(R.string.dj_grid_double),
                onClick = {
                    backToSteps()
                    send(GridEditCommand.DoubleBpm)
                },
                modifier = Modifier.weight(HalveDoubleWeight),
                height = sizes.control,
                enabled = GridEdits.canDouble(hundredths),
            )
            GridButton(
                label = stringResource(R.string.dj_grid_tap_label),
                description = stringResource(R.string.dj_grid_tap_description),
                onClick = {
                    if (tapGate.clicked()) registerTap()
                },
                modifier = Modifier
                    .weight(TapWeight)
                    .onPressDown(onEnd = { released -> tapGate.ended(released) }) {
                        if (tapGate.pressed()) registerTap()
                    },
                height = sizes.control,
                emphasised = mode == BpmMode.Tap,
            )
            GridButton(
                label = stringResource(R.string.dj_grid_reset),
                description = stringResource(R.string.dj_grid_reset_description),
                onClick = {
                    backToSteps()
                    onReset()
                },
                modifier = Modifier.weight(ResetWeight),
                height = sizes.control,
                enabled = edit != null,
            )
        }
        Row(Modifier.fillMaxWidth().height(sizes.control), horizontalArrangement = Arrangement.spacedBy(ItemGap), verticalAlignment = Alignment.CenterVertically) {
            GridButton(
                label = stringResource(R.string.dj_grid_beat_earlier),
                description = stringResource(R.string.dj_grid_beat_earlier_description),
                onClick = { send(GridEditCommand.ShiftBeats(-1)) },
                modifier = Modifier.weight(1f),
                height = sizes.control,
            )
            GridButton(
                label = stringResource(R.string.dj_grid_set_bar_start),
                description = stringResource(R.string.dj_grid_set_bar_start_description),
                onClick = { send(GridEditCommand.SetBarStartHere) },
                modifier = Modifier.weight(BarStartWeight),
                height = sizes.control,
                emphasised = true,
            )
            GridButton(
                label = stringResource(R.string.dj_grid_beat_later),
                description = stringResource(R.string.dj_grid_beat_later_description),
                onClick = { send(GridEditCommand.ShiftBeats(1)) },
                modifier = Modifier.weight(1f),
                height = sizes.control,
            )
        }
        Row(Modifier.fillMaxWidth().height(sizes.control), horizontalArrangement = Arrangement.spacedBy(ItemGap), verticalAlignment = Alignment.CenterVertically) {
            GridButton(
                label = stringResource(R.string.dj_grid_bar_earlier),
                description = stringResource(R.string.dj_grid_bar_earlier_description),
                onClick = { send(GridEditCommand.ShiftBars(-1)) },
                modifier = Modifier.weight(1f),
                height = sizes.control,
            )
            // Names what the two buttons move; it is not a button (the phrase start is set with "Set bar start here").
            BasicText(
                stringResource(R.string.dj_grid_phrase_start),
                Modifier.weight(BarStartWeight).clearAndSetSemantics { },
                style = CaptionStyle.copy(color = Color.White.copy(alpha = CaptionAlpha)),
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = CaptionStyle.fontSize, stepSize = 0.5.sp),
            )
            GridButton(
                label = stringResource(R.string.dj_grid_bar_later),
                description = stringResource(R.string.dj_grid_bar_later_description),
                onClick = { send(GridEditCommand.ShiftBars(1)) },
                modifier = Modifier.weight(1f),
                height = sizes.control,
            )
        }
        Row(Modifier.fillMaxWidth().height(sizes.control), horizontalArrangement = Arrangement.spacedBy(ItemGap), verticalAlignment = Alignment.CenterVertically) {
            val unit = stringResource(R.string.dj_grid_ms_unit)
            for (ms in NudgeSteps) {
                GridButton(
                    label = GridEditText.nudgeLabel(ms, unit, locale),
                    description = stringResource(
                        if (ms < 0) R.string.dj_grid_nudge_earlier else R.string.dj_grid_nudge_later,
                        GridEditText.nudgeStep(ms, locale),
                    ),
                    onClick = { send(GridEditCommand.Nudge(ms.toDouble())) },
                    modifier = Modifier.weight(1f),
                    height = sizes.control,
                )
            }
        }
    }
}

/**
 * One of the four tempo step buttons: [deltaHundredths] hundredths of a BPM, signed. A press does one step; held, it
 * repeats on [HoldRepeatSchedule.Default] with a haptic tick per repeat, the click that ends a hold doing nothing more.
 */
@Composable
private fun BpmStepButton(deltaHundredths: Int, locale: Locale, send: (GridEditCommand) -> Unit, height: Dp, modifier: Modifier) {
    val step = GridEditText.bpmStep(deltaHundredths, locale)
    val hold = remember { HoldRepeatState() }
    GridButton(
        label = GridEditText.bpmStepLabel(deltaHundredths, locale),
        description = stringResource(if (deltaHundredths < 0) R.string.dj_grid_bpm_down else R.string.dj_grid_bpm_up, step),
        onClick = { if (!hold.consumeClick()) send(GridEditCommand.AdjustBpm(deltaHundredths)) },
        modifier = modifier.holdToRepeat(hold) { send(GridEditCommand.AdjustBpm(deltaHundredths)) },
        height = height,
    )
}

/**
 * A translucent pill of the grid editor, [height] tall (44 dp by default). A button that cannot act now ([enabled] false) dims and ignores taps but stays
 * in place, announced as disabled; the main action ([emphasised]) is lettered in the accent colour.
 */
@Composable
internal fun GridButton(
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasised: Boolean = false,
    height: Dp = DefaultButtonHeight,
    /** A colour the pill is washed in while the button is lit (a loop button that is on); transparent for none. */
    wash: Color = Color.Transparent,
    /** What the button says about its state to a screen reader ("Looping"), when it has one. */
    stateText: String? = null,
    /** Whether the button is on, announced as selected. */
    selected: Boolean = false,
) {
    TranslucentPill(
        onClick = { if (enabled) onClick() },
        modifier = modifier,
        height = height,
        onClickLabel = description,
        startPadding = 6.dp,
        endPadding = 6.dp,
        darkTheme = true,
        tinted = false,
        wash = { wash },
        contentModifier = Modifier.semantics {
            contentDescription = description
            if (stateText != null) stateDescription = stateText
            if (selected) this.selected = true
            if (!enabled) disabled()
        },
    ) {
        val colors = LocalTranslucentContentColors.current
        val ink = if (emphasised) colors.accent else colors.label
        // One line that shrinks to fit its button (a narrow phone, a long translation) instead of being cut.
        BasicText(
            label,
            Modifier.weight(1f).reflected(),
            style = ButtonLabelStyle.copy(color = if (enabled) ink else ink.copy(alpha = DisabledAlpha)),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = ButtonLabelStyle.fontSize, stepSize = 0.5.sp),
        )
    }
}

/**
 * The small marker that says the beat grid is the user's edit and not the analysed one: a dark translucent capsule reading
 * "Edited", 28 dp tall (18 dp when [compact], the badge in the time row under the overview). It only informs.
 */
@Composable
fun GridEditedChip(modifier: Modifier = Modifier, compact: Boolean = false) {
    val description = stringResource(R.string.dj_grid_edited_description)
    TranslucentSurface(
        modifier.height(if (compact) CompactChipHeight else ChipHeight),
        shape = CapsuleShape,
        elevation = 0.dp,
        darkTheme = true,
        tinted = true,
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .clip(CapsuleShape)
                .clearAndSetSemantics { contentDescription = description }
                .padding(horizontal = if (compact) 8.dp else 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.dj_grid_edited),
                color = LocalTranslucentContentColors.current.label,
                fontSize = if (compact) 11.sp else 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/** The tempo steps, in hundredths of a BPM: the two below the value and the two above it. */
private val BigAndSmallDown = listOf(-100, -1)
private val SmallAndBigUp = listOf(1, 100)

/** The grid nudges, in milliseconds. */
private val NudgeSteps = listOf(-10, -1, 1, 10)

private val TitleStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
private val TitleMinSize = 12.sp
private val MessageStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
private val ErrorInk = Color(0xFFFF8A80)
private val CaptionStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
private const val CaptionAlpha = 0.72f

private val ButtonLabelStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center).merge(TabularFigures)

private const val HUNDREDTHS = 100.0
private const val BpmValueWeight = 1.7f

// Halve, double, Tap and Reset share a row; the long Reset words (Basque "Berrezarri") get the most room.
private const val HalveDoubleWeight = 1f
private const val TapWeight = 1.4f
private const val ResetWeight = 1.6f
private const val BarStartWeight = 2.4f
private const val DisabledAlpha = 0.38f

private val ItemGap = 8.dp
private val DefaultButtonHeight = 44.dp
private val ChipHeight = 28.dp
private val CompactChipHeight = 18.dp
private val DoneWidth = 96.dp
