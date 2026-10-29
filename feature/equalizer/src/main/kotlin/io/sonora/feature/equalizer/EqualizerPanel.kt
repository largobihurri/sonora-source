package io.sonora.feature.equalizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.TranslucentPanel
import io.sonora.core.designsystem.component.TranslucentPanelActionRow
import io.sonora.core.designsystem.component.TranslucentPanelFooterStyle
import io.sonora.core.designsystem.component.TranslucentPanelGroup
import io.sonora.core.designsystem.component.TranslucentPanelHeight
import io.sonora.core.designsystem.component.TranslucentPanelSwitchRow
import io.sonora.core.designsystem.component.scrollEdgeFade
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.text.currentLocale
import io.sonora.core.domain.equalizer.EqualizerBand
import io.sonora.core.domain.equalizer.EqualizerLayout
import io.sonora.core.domain.equalizer.EqualizerMode
import io.sonora.core.domain.equalizer.EqualizerResponse
import io.sonora.core.domain.equalizer.EqualizerSettings

/** The response curve and the preamp are drawn for this rate; the curve hardly moves with the stream's own rate. */
private const val DISPLAY_RATE = 48_000

/**
 * The equalizer, as a translucent panel over Now Playing like File Info and Speed & Pitch: an on/off switch, the band
 * layout (4, 11 or 20 bands), then one card with the response curve the player really applies, the automatic preamp
 * switch and one vertical slider per band, and a Reset row with the hint. Changes are heard while dragging. Colours
 * come from the translucent surface, so the panel reads on light and dark.
 *
 * The panel may grow almost as tall as the screen; the curve and the sliders take what the rest leaves
 * ([equalizerMetrics]), so on a phone in portrait it all fits without scrolling. Only where even their smallest sizes
 * do not fit (landscape, a small screen, very large text) does it scroll, with soft edges. The sliders share the
 * width ([bandColumns]); twenty bands scroll sideways, snapping to a band, with soft edges too.
 */
@Composable
fun EqualizerPanel(onDismiss: () -> Unit, viewModel: EqualizerViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    TranslucentPanel(stringResource(R.string.equalizer_title), onDismiss, height = TranslucentPanelHeight.Expanded) {
        BoxWithConstraints {
            val bands = EqualizerLayout.bands(settings.mode)
            val gains = settings.activeGains
            val preampLabel = preampLabel(bands, gains, settings.automaticPreamp)
            val hint = stringResource(R.string.equalizer_hint)
            val fixed = fixedHeight(maxWidth, hint, preampLabel, named = settings.mode == EqualizerMode.FOUR_BANDS)
            val available = if (constraints.hasBoundedHeight) maxHeight.value else Float.POSITIVE_INFINITY
            val metrics = equalizerMetrics(available, fixed.value)
            val columns = bandColumns(bands.size, (maxWidth - CardMargin * 2 - SliderMargin * 2).value)
            val scroll = rememberScrollState()
            Column(
                Modifier
                    .scrollEdgeFade(scroll)
                    .verticalScroll(scroll)
                    .padding(top = PanelTop, bottom = PanelBottom),
            ) {
                TranslucentPanelGroup(title = null) {
                    TranslucentPanelSwitchRow(stringResource(R.string.equalizer_title), settings.enabled, viewModel::setEnabled)
                }
                ModeControl(settings.mode, viewModel::selectMode)
                TranslucentPanelGroup(title = null) {
                    Column(Modifier.padding(vertical = CardPadding)) {
                        ResponseCurve(
                            bands,
                            gains,
                            active = settings.enabled,
                            modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(metrics.curveHeight.dp),
                        )
                        Spacer(Modifier.height(CurveGap))
                        TranslucentPanelSwitchRow(
                            label = preampLabel,
                            checked = settings.automaticPreamp,
                            onChange = viewModel::setAutomaticPreamp,
                            contentDescription = stringResource(R.string.equalizer_automatic_preamp),
                            // With the equalizer off its preamp does nothing either: the switch dims like the curve
                            // and the sliders (it still works), the label keeps its contrast.
                            dimmed = !settings.enabled,
                        )
                        BandSliders(settings, bands, viewModel, metrics.trackHeight.dp, columns, Modifier.padding(horizontal = SliderMargin))
                    }
                }
                Spacer(Modifier.height(GroupGap))
                TranslucentPanelGroup(title = null, footer = hint) {
                    TranslucentPanelActionRow(
                        stringResource(R.string.equalizer_reset),
                        SonoraIcons.Refresh,
                        enabled = !settings.isFlat,
                        onClick = viewModel::reset,
                    )
                }
            }
        }
    }
}

/*
 * The panel's fixed metrics, shared by the layout and [fixedHeight]: what is not the curve or the sliders' tracks.
 * Rows are at least as tall as their touch target, or their text plus padding when the text is larger.
 */
private val PanelTop = 4.dp
private val PanelBottom = 12.dp
private val CardMargin = 12.dp
private val CardPadding = 10.dp
private val CurveGap = 6.dp
private val GroupGap = 12.dp
private val SliderMargin = 8.dp
private val SwitchRowMin = 48.dp
private val ActionRowMin = 46.dp
private val RowPadding = 16.dp
private val SwitchWidth = 52.dp
private val ModeControlMin = 32.dp
private val ModeControlMargin = 10.dp
private val SliderLabelGap = 4.dp
/** The safety margin against rounding: a pixel short would scroll the whole panel. */
private val FitSlack = 2.dp

private val RowTextStyle = TextStyle(fontSize = 17.sp, lineHeight = 21.sp)
internal val ModeTextStyle = TextStyle(fontSize = 13.sp, lineHeight = 16.sp)
internal val SliderLabelLineHeight = 14.sp

/**
 * The height of everything but the curve and the tracks, for a panel [width] wide: the switch, the mode control, the
 * card's padding, the preamp row ([preampLabel] may wrap at large text sizes), the sliders' labels (with the band
 * names when [named]), Reset and the [hint] under it, each measured at the current text size.
 */
@Composable
private fun fixedHeight(width: Dp, hint: String, preampLabel: String, named: Boolean): Dp {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    // Measured in the style the rows really use (the theme's body style under the panel's sizes).
    val base = LocalTextStyle.current
    return remember(width, hint, preampLabel, named, density, base) {
        with(density) {
            fun textHeight(text: String, style: TextStyle, room: Dp) =
                measurer.measure(text, base.merge(style), constraints = Constraints(maxWidth = room.roundToPx().coerceAtLeast(1))).size.height.toDp()
            val rowLine = RowTextStyle.lineHeight.toDp()
            val switchRow = maxOf(SwitchRowMin, rowLine + RowPadding)
            val inCard = width - CardMargin * 2
            val preampRow = maxOf(SwitchRowMin, textHeight(preampLabel, RowTextStyle, inCard - RowPadding * 2 - 12.dp - SwitchWidth) + RowPadding)
            val modeControl = maxOf(ModeControlMin, ModeTextStyle.lineHeight.toDp() + 12.dp) + 4.dp + ModeControlMargin * 2
            val sliderLabel = SliderLabelLineHeight.toDp()
            val sliderLabels = sliderLabel * (if (named) 3 else 2) + SliderLabelGap * 2
            val card = CardPadding * 2 + CurveGap + preampRow + sliderLabels
            val reset = maxOf(ActionRowMin, rowLine + RowPadding)
            val footer = 6.dp + textHeight(hint, TranslucentPanelFooterStyle, inCard - 32.dp)
            PanelTop + switchRow + modeControl + card + GroupGap + reset + footer + PanelBottom + FitSlack
        }
    }
}

/** The automatic preamp switch's label: on, the preamp the curve brings (0 dB without a boost); off, the limiter. */
@Composable
private fun preampLabel(bands: List<EqualizerBand>, gains: List<Float>, automatic: Boolean): String {
    if (!automatic) return stringResource(R.string.equalizer_limiter)
    val preamp = remember(bands, gains) { EqualizerResponse.preampDb(bands, gains, DISPLAY_RATE).toFloat() }
    return stringResource(R.string.equalizer_preamp, EqualizerText.gain(EqualizerSettings.quantize(preamp), currentLocale()))
}

/** iOS segmented control on the translucent surface: equal segments, the chosen one on a raised, lighter thumb. */
@Composable
private fun ModeControl(selected: EqualizerMode, onSelect: (EqualizerMode) -> Unit) {
    val label = LocalTranslucentContentColors.current.label
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CardMargin, vertical = ModeControlMargin)
            .clip(RoundedCornerShape(9.dp))
            .background(label.copy(alpha = 0.12f))
            .padding(2.dp)
            .selectableGroup(),
    ) {
        for (mode in EqualizerMode.entries) {
            val isSelected = mode == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = ModeControlMin)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (isSelected) label.copy(alpha = 0.22f) else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(mode) })
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    pluralStringResource(R.plurals.equalizer_bands, mode.bandCount, mode.bandCount),
                    color = label,
                    style = ModeTextStyle,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The curve the player applies (without its preamp), computed from the same biquads: 0 dB in the middle, ±15 dB at
 * the edges so overlapping boosts still fit, with a dot on each band's frequency. It sits in a clean translucent inset
 * (a faint fill lit from the top, a hairline rim and a subtle grid), the curve in the translucent accent; with the
 * equalizer off ([active] false) only the curve dims, so the card still reads as translucent rather than grey.
 */
@Composable
private fun ResponseCurve(bands: List<EqualizerBand>, gains: List<Float>, active: Boolean, modifier: Modifier) {
    val colors = LocalTranslucentContentColors.current
    val label = colors.label
    val accent = colors.accent
    val darkTranslucent = label.luminance() > 0.5f
    val faint = label.copy(alpha = GRID_ALPHA)
    val frequencies = remember { EqualizerText.curveFrequencies(CURVE_POINTS) }
    val response = remember(bands, gains) {
        FloatArray(CURVE_POINTS) { EqualizerResponse.responseDb(bands, gains, frequencies[it], DISPLAY_RATE).toFloat() }
    }
    val dots = remember(bands, gains) {
        bands.map { EqualizerText.position(it.frequencyHz) to EqualizerResponse.responseDb(bands, gains, it.frequencyHz, DISPLAY_RATE).toFloat() }
    }
    val curveAlpha = if (active) 1f else INACTIVE_ALPHA
    Canvas(modifier.clip(CurveShape)) {
        val corner = CornerRadius(CurveCorner.toPx())
        // The inset: a faint fill, lighter at the top as translucency catching the light, and a hairline rim.
        drawRoundRect(label.copy(alpha = INSET_FILL_ALPHA), cornerRadius = corner)
        drawRoundRect(
            Brush.verticalGradient(listOf(InsetLight.copy(alpha = if (darkTranslucent) 0.07f else 0.32f), InsetLight.copy(alpha = 0f))),
            size = Size(size.width, size.height * 0.6f),
            cornerRadius = corner,
        )
        val hairline = 0.75.dp.toPx()
        drawRoundRect(
            label.copy(alpha = INSET_RIM_ALPHA),
            topLeft = Offset(hairline / 2, hairline / 2),
            size = Size(size.width - hairline, size.height - hairline),
            cornerRadius = CornerRadius(CurveCorner.toPx() - hairline / 2),
            style = Stroke(hairline),
        )

        val mid = size.height / 2
        fun y(db: Float) = mid - db.coerceIn(-CURVE_RANGE_DB, CURVE_RANGE_DB) / CURVE_RANGE_DB * mid
        for (db in listOf(-12f, -6f, 6f, 12f)) drawLine(faint, Offset(0f, y(db)), Offset(size.width, y(db)), hairline)
        for (hz in listOf(100.0, 1_000.0, 10_000.0)) {
            val x = EqualizerText.position(hz) * size.width
            drawLine(faint, Offset(x, 0f), Offset(x, size.height), hairline)
        }
        drawLine(label.copy(alpha = ZERO_LINE_ALPHA), Offset(0f, mid), Offset(size.width, mid), hairline)

        val curve = Path()
        response.forEachIndexed { i, db ->
            val x = i / (CURVE_POINTS - 1f) * size.width
            if (i == 0) curve.moveTo(x, y(db)) else curve.lineTo(x, y(db))
        }
        val fill = Path().apply {
            addPath(curve)
            lineTo(size.width, mid)
            lineTo(0f, mid)
            close()
        }
        drawPath(
            fill,
            Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.05f), accent.copy(alpha = 0.35f))),
            alpha = curveAlpha,
        )
        drawPath(curve, accent, alpha = curveAlpha, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        for ((position, db) in dots) {
            if (position in 0f..1f) drawCircle(accent, 2.5.dp.toPx(), Offset(position * size.width, y(db)), alpha = curveAlpha)
        }
    }
}

@Composable
private fun BandSliders(
    settings: EqualizerSettings,
    bands: List<EqualizerBand>,
    viewModel: EqualizerViewModel,
    trackHeight: Dp,
    columns: BandColumns,
    modifier: Modifier = Modifier,
) {
    val locale = currentLocale()
    val names = listOf(
        stringResource(R.string.equalizer_sub_bass),
        stringResource(R.string.equalizer_bass),
        stringResource(R.string.equalizer_mids),
        stringResource(R.string.equalizer_treble),
    )
    val slider: @Composable (Int, Modifier) -> Unit = { index, sliderModifier ->
        val band = bands[index]
        val label = EqualizerText.frequency(band.nominalHz, locale)
        val name = names.getOrNull(index)?.takeIf { settings.mode == EqualizerMode.FOUR_BANDS }
        val description = name ?: if (band.nominalHz < 1_000) {
            stringResource(R.string.equalizer_band_hz, label)
        } else {
            stringResource(R.string.equalizer_band_khz, label.removeSuffix("k"))
        }
        BandSlider(
            gain = settings.activeGains[index],
            frequencyLabel = label,
            name = name,
            description = description,
            active = settings.enabled,
            trackHeight = trackHeight,
            knob = DpSize(columns.knobWidth.dp, columns.knobHeight.dp),
            compactLabels = columns.compactLabels,
            onChange = { viewModel.dragBand(index, it) },
            onFinished = { viewModel.releaseBand(index, it) },
            onCancelled = { original -> viewModel.cancelBand(index, original) },
            onReset = { viewModel.resetBand(index) },
            modifier = sliderModifier,
        )
    }
    if (columns.scrolls) {
        // Too many bands for the width: they scroll sideways, snapping to a band, a half band peeking at the edge
        // under a soft fade so it is clear there is more.
        val rowState = rememberLazyListState()
        LazyRow(
            state = rowState,
            flingBehavior = rememberSnapFlingBehavior(rowState, SnapPosition.Start),
            modifier = modifier.fillMaxWidth().scrollEdgeFade(rowState, Orientation.Horizontal),
        ) {
            items(bands.size) { index -> slider(index, Modifier.width(columns.columnWidth.dp)) }
        }
    } else {
        Row(modifier.fillMaxWidth()) {
            for (index in bands.indices) slider(index, Modifier.weight(1f))
        }
    }
}

private const val CURVE_POINTS = 160
private const val CURVE_RANGE_DB = 15f
private const val GRID_ALPHA = 0.09f
private const val ZERO_LINE_ALPHA = 0.22f
private const val INSET_FILL_ALPHA = 0.04f
private const val INSET_RIM_ALPHA = 0.1f
private val CurveCorner = 12.dp
private val CurveShape = RoundedCornerShape(CurveCorner)
/** The light an inset catches at its top edge: white light, as the translucency's own sheen. */
private val InsetLight = Color.White

/** With the equalizer off its controls dim this far; labels and values keep their full contrast. */
internal const val INACTIVE_ALPHA = 0.6f
