package io.sonora.feature.dj.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.feature.dj.BpmEntry
import io.sonora.feature.dj.GridEditText
import io.sonora.feature.dj.R
import java.util.Locale

/** What the tempo row of the beat grid editor is doing. */
internal enum class BpmMode {
    /** The track's tempo between the four step buttons; tapping the value starts [Entry]. */
    Steps,

    /** A text field for the tempo, with Cancel and Set. */
    Entry,

    /** The tempo the Tap button has measured so far, with Cancel and Set. */
    Tap,
}

/** The message that says why a typed tempo was not applied (it replaces the editor title while shown). */
@Composable
internal fun bpmProblemText(problem: BpmEntry.Problem, locale: Locale): String = when (problem) {
    BpmEntry.Problem.Empty -> stringResource(R.string.dj_grid_bpm_error_empty)
    BpmEntry.Problem.NotANumber -> stringResource(R.string.dj_grid_bpm_error_not_a_number)
    BpmEntry.Problem.TooManyDecimals -> stringResource(R.string.dj_grid_bpm_error_decimals)
    BpmEntry.Problem.OutOfRange -> stringResource(
        R.string.dj_grid_bpm_error_range,
        GridEditText.bpm(BpmEntry.MIN_HUNDREDTHS / HUNDREDTHS, locale),
        GridEditText.bpm(BpmEntry.MAX_HUNDREDTHS / HUNDREDTHS, locale),
    )
}

/**
 * The tempo row while a tempo is being typed: Cancel, a numeric text field holding the current tempo selected (typing
 * replaces it) and Set. The keyboard's Done does what Set does. [onSubmit] gets the text as typed and judges it; the
 * field turns red while [rejected] and clears that by itself as soon as the text changes ([onEdited]).
 */
@Composable
internal fun BpmEntryRow(
    initialText: String,
    rejected: Boolean,
    onEdited: () -> Unit,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initialText, TextRange(0, initialText.length))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    // Set, Cancel and a track change all end the entry: the keyboard goes with it.
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
    val description = stringResource(R.string.dj_grid_bpm_field_description)
    Row(modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(RowGap), verticalAlignment = Alignment.CenterVertically) {
        GridButton(
            label = stringResource(R.string.dj_grid_bpm_cancel),
            description = stringResource(R.string.dj_grid_bpm_cancel_description),
            onClick = onCancel,
            modifier = Modifier.weight(SideWeight),
            height = height,
        )
        Box(
            Modifier
                .weight(CenterWeight)
                .fillMaxHeight()
                .clip(CapsuleShape)
                .background(Color.White.copy(alpha = FieldFillAlpha))
                .border(1.dp, if (rejected) ErrorInk else Color.White.copy(alpha = FieldBorderAlpha), CapsuleShape)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicTextField(
                value = field,
                onValueChange = { typed ->
                    val kept = typed.text.take(MAX_CHARS)
                    val changed = kept != field.text
                    field = if (kept == typed.text) typed else typed.copy(text = kept, selection = TextRange(kept.length))
                    if (changed) onEdited()
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = description },
                textStyle = FieldStyle.copy(color = if (rejected) ErrorInk else Color.White),
                singleLine = true,
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit(field.text) }),
            )
        }
        GridButton(
            label = stringResource(R.string.dj_grid_bpm_apply),
            description = stringResource(R.string.dj_grid_bpm_apply_entry_description),
            onClick = { onSubmit(field.text) },
            modifier = Modifier.weight(SideWeight),
            height = height,
            emphasised = true,
        )
    }
}

/**
 * The tempo row while the user taps the tempo on the Tap button: Cancel, the tempo measured so far (or how many taps
 * are in, until there are enough) and Set, which applies the measured tempo and is dimmed until there is one. Nothing
 * reaches the beat grid until Set, so the waveform does not jump with every tap while the estimate is still settling.
 */
@Composable
internal fun BpmTapRow(
    proposalHundredths: Int?,
    taps: Int,
    minTaps: Int,
    locale: Locale,
    onApply: (Int) -> Unit,
    onCancel: () -> Unit,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(RowGap), verticalAlignment = Alignment.CenterVertically) {
        GridButton(
            label = stringResource(R.string.dj_grid_bpm_cancel),
            description = stringResource(R.string.dj_grid_bpm_cancel_description),
            onClick = onCancel,
            modifier = Modifier.weight(SideWeight),
            height = height,
        )
        if (proposalHundredths != null) {
            val text = GridEditText.bpm(proposalHundredths / HUNDREDTHS, locale)
            BpmValuePill(
                text = text,
                description = stringResource(R.string.dj_grid_tap_result_description, text),
                modifier = Modifier.weight(CenterWeight),
            )
        } else {
            val description = stringResource(R.string.dj_grid_tap_progress_description, taps, minTaps)
            BasicText(
                stringResource(R.string.dj_grid_tap_progress, taps, minTaps),
                Modifier.weight(CenterWeight).semantics { contentDescription = description },
                style = HintStyle.copy(color = Color.White.copy(alpha = HintAlpha)),
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 16.sp, stepSize = 0.5.sp),
            )
        }
        GridButton(
            label = stringResource(R.string.dj_grid_bpm_apply),
            description = stringResource(R.string.dj_grid_bpm_apply_tap_description),
            onClick = { if (proposalHundredths != null) onApply(proposalHundredths) },
            modifier = Modifier.weight(SideWeight),
            height = height,
            enabled = proposalHundredths != null,
            emphasised = true,
        )
    }
}

/**
 * The track's tempo, large, with two decimals and tabular figures so it never jitters, read out with its unit. A tap
 * on it opens the tempo entry ([onClick]); a hairline outline says it can be tapped.
 */
@Composable
internal fun BpmValue(bpm: Double, locale: Locale, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val text = GridEditText.bpm(bpm, locale)
    BpmValuePill(
        text = text,
        description = stringResource(R.string.dj_grid_bpm_description, text),
        modifier = modifier,
        onClick = onClick,
        onClickLabel = stringResource(R.string.dj_grid_bpm_edit_label),
    )
}

/**
 * The outlined pill a tempo is shown in, the same for the track's tempo and for the tempo Tap proposes. The editor is
 * always a dark translucent surface, so the figures are white and not the label colour of the surrounding surface
 * (outside a translucent surface that is black). It is a button only when [onClick] is given.
 */
@Composable
private fun BpmValuePill(
    text: String,
    description: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    Box(
        modifier
            .fillMaxHeight()
            .clip(CapsuleShape)
            .border(1.dp, Color.White.copy(alpha = ValueBorderAlpha), CapsuleShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .semantics { contentDescription = description }
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text,
            Modifier.fillMaxWidth().clearAndSetSemantics { },
            style = ValueStyle.copy(color = Color.White),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 30.sp, stepSize = 0.5.sp),
        )
    }
}

private const val HUNDREDTHS = 100.0
private const val MAX_CHARS = 12
private const val SideWeight = 1.3f
private const val CenterWeight = 2.4f
private const val FieldFillAlpha = 0.10f
private const val FieldBorderAlpha = 0.7f
private const val ValueBorderAlpha = 0.22f
private const val HintAlpha = 0.72f
private val RowGap = 8.dp
private val ErrorInk = Color(0xFFFF8A80)
private val ValueStyle = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center).merge(TabularFigures)
private val HintStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center).merge(TabularFigures)
private val FieldStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center).merge(TabularFigures)
