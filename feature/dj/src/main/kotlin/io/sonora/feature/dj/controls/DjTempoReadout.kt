package io.sonora.feature.dj.controls

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.sonora.feature.dj.R

/**
 * The tempo readout, as on a CDJ's display: the BPM large (one decimal, tabular figures, so it never jitters as it
 * changes), then the key and the tempo signed with two decimals ("+1.25%", "+0.00%"), quieter. [bpm] is the playing
 * track's BPM as heard (already times the speed), null when unknown; [key] is the key to show (shifted by the caller
 * when Master Tempo is off), null when unknown. Read out as one phrase.
 *
 * One line of text whose parts are sized relative to the BPM's: when the line does not fit its width (a narrow phone,
 * "−12.50 %"), the whole readout shrinks together, down to [MinSize], instead of a part being cut (D2.7b: the
 * Spanish "0,00 %" lost its "%", which was pushed onto a second, hidden line). The size is fitted by [ReadoutFit], once per
 * shape of text and width, so a tempo drag that changes the text on every step does not measure it again each time.
 */
@Composable
fun DjTempoReadout(
    bpm: Double?,
    key: String?,
    tempoPercent: Double,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    /** The BPM's largest size (the rest is relative to it); a compact row passes less than the 40 sp default. */
    maxSize: TextUnit = BpmStyle.fontSize,
) {
    val locale = LocalConfiguration.current.locales[0]
    val bpmText = DjTempoText.bpm(bpm, locale)
    val percentText = DjTempoText.tempoPercent(tempoPercent, locale)
    val unit = stringResource(R.string.dj_readout_bpm_unit)
    val description = listOf(
        if (bpmText == DjTempoText.NO_VALUE) stringResource(R.string.dj_readout_no_bpm) else stringResource(R.string.dj_readout_bpm, bpmText),
        if (key == null) stringResource(R.string.dj_readout_no_key) else stringResource(R.string.dj_readout_key, key),
        stringResource(R.string.dj_readout_tempo, percentText),
    ).joinToString(", ")
    val text = remember(bpmText, unit, key, percentText, color) {
        val quiet = color.copy(alpha = QuietAlpha)
        val separator = SpanStyle(color = quiet.copy(alpha = quiet.alpha * 0.6f), fontSize = DetailEm, fontWeight = FontWeight.Medium)
        val detail = SpanStyle(color = quiet, fontSize = DetailEm, fontWeight = FontWeight.Medium)
        buildAnnotatedString {
            withStyle(SpanStyle(color = color, letterSpacing = BpmLetterSpacing)) { append(bpmText) }
            withStyle(SpanStyle(color = quiet, fontSize = UnitEm)) {
                append(" ")
                append(unit)
            }
            withStyle(separator) { append(SEPARATOR) }
            withStyle(detail) { append(key ?: DjTempoText.NO_VALUE) }
            withStyle(separator) { append(SEPARATOR) }
            withStyle(detail) { append(percentText) }
        }
    }
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val sizes = remember { HashMap<Pair<String, Int>, Float>() }
    BoxWithConstraints(modifier) {
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        // Once per shape of text and width, not on every step of a drag (see ReadoutFit).
        val size = sizes.getOrPut(ReadoutFit.shapeOf(text.text) to available) {
            ReadoutFit.largestFitting(MinSize.value, maxOf(maxSize.value, MinSize.value), SizeStep, available.toFloat()) { sp ->
                measurer.measure(text, BpmStyle.copy(fontSize = sp.sp), maxLines = 1, softWrap = false, constraints = Constraints()).size.width.toFloat()
            }
        }
        BasicText(
            text,
            Modifier.semantics { contentDescription = description },
            style = BpmStyle.copy(fontSize = size.sp),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** The grid the readout's size is chosen on, in sp. */
private const val SizeStep = 0.5f

private const val SEPARATOR = " · "

/** The BPM's size; the rest is sized relative to it (13 sp and 17 sp at full size), so all of it shrinks together. */
private val BpmStyle = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.SemiBold).merge(TabularFigures)
private val BpmLetterSpacing = (-0.0125).em
private val UnitEm = (13f / 40f).em
private val DetailEm = (17f / 40f).em

/** The smallest the readout shrinks to (the details are then 12 sp). */
private val MinSize = 28.sp
private const val QuietAlpha = 0.72f
