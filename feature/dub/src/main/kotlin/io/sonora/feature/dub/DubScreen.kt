package io.sonora.feature.dub

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.CapsuleSlider
import io.sonora.core.designsystem.component.SegmentedControl
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.layout.LocalWindowClass
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.dubmodel.DubBypass
import io.sonora.core.dubmodel.DubClockSource
import io.sonora.core.dubmodel.DubDivision
import io.sonora.core.dubmodel.DubMeters
import io.sonora.core.dubmodel.DubPadKind
import io.sonora.core.dubmodel.DubSetup
import io.sonora.core.dubmodel.DubStageState
import io.sonora.core.dubmodel.PadBehaviour
import io.sonora.core.dubmodel.PreampWay
import io.sonora.feature.dub.controls.BeatLeds
import io.sonora.feature.dub.controls.BypassBanner
import io.sonora.feature.dub.controls.DubBypassReason
import io.sonora.feature.dub.controls.DubIndicators
import io.sonora.feature.dub.controls.DubPad
import io.sonora.feature.dub.controls.DubPadState
import io.sonora.feature.dub.controls.DubWay
import io.sonora.feature.dub.controls.LimiterLed
import io.sonora.feature.dub.controls.MiniKillRow
import io.sonora.feature.dub.controls.PadState
import io.sonora.feature.dub.controls.PeakMeter
import io.sonora.feature.dub.controls.PreampStrip
import io.sonora.feature.dub.controls.rememberDubPadState

/** The two pages of the DUB screen on a phone held upright; elsewhere both are shown side by side. */
enum class DubPage { EFFECTS, PREAMP }

/**
 * The DUB screen (design §4 and §10.2): the effects page (the XY filter, the echo time, FEEDBACK and RETURN, the five
 * preamp kills in small, the 2 x 3 pad grid, STOP and PLAY) and the preamp page (five strips). Upright on a phone the
 * pages are switched with a segmented control; in landscape or on a tablet the preamp is a column next to the effects.
 * [top] is the host's (Now Playing's header and the compact deck strip); [beatLed] is the lit beat LED, null without
 * a beat.
 *
 * Opening the screen resets the performance state ([DubViewModel.opened]): every pad released, the filter centred, no
 * way killed. While the stage is bypassed the controls are disabled and a banner says why. Nothing here asks for a
 * frame while paused: the meters stand still and the LEDs only change with the beat.
 */
@Composable
fun DubScreen(
    viewModel: DubViewModel,
    modifier: Modifier = Modifier,
    beatLed: Int? = null,
    reducedMotion: Boolean = false,
    top: @Composable ColumnScope.() -> Unit = {},
) {
    LaunchedEffect(viewModel) { viewModel.opened() }
    val setup by viewModel.setup.collectAsStateWithLifecycle()
    val stage by viewModel.stage.collectAsStateWithLifecycle()
    val performance by viewModel.performance.collectAsStateWithLifecycle()
    val meters by viewModel.meters.collectAsStateWithLifecycle(DubMeters())
    val enabled = stage.hosted
    var page by rememberSaveable { mutableStateOf(DubPage.EFFECTS) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val sideBySide = maxWidth > maxHeight || maxWidth >= SIDE_BY_SIDE_MIN_WIDTH
        val compact = maxHeight < COMPACT_BELOW_HEIGHT
        if (sideBySide) {
            Column(Modifier.fillMaxSize().padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(Gap)) {
                top()
                StatusRow(stage, meters, beatLed, reducedMotion)
                BypassBanner(stage.bypass?.toReason())
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
                    EffectsPage(viewModel, setup, stage, performance, enabled, compact, Modifier.weight(1f).fillMaxHeight())
                    PreampPage(viewModel, setup, performance, enabled, Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else {
            val effects = page == DubPage.EFFECTS
            UprightLayout(
                viewport = maxHeight,
                minFlex = if (effects) XyMinSize else StripMinHeight,
                maxSquare = if (effects) XyMaxSize else null,
                above = {
                    Column(verticalArrangement = Arrangement.spacedBy(Gap)) {
                        top()
                        StatusRow(stage, meters, beatLed, reducedMotion)
                        BypassBanner(stage.bypass?.toReason())
                        SegmentedControl(
                            options = DubPage.entries,
                            selected = page,
                            label = { stringResource(if (it == DubPage.EFFECTS) R.string.dub_page_effects else R.string.dub_page_preamp) },
                            onSelect = { page = it },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                flex = {
                    if (effects) {
                        XyPad(viewModel, setup, performance, enabled, Modifier.fillMaxSize())
                    } else {
                        PreampPage(viewModel, setup, performance, enabled, Modifier.fillMaxSize())
                    }
                },
                below = {
                    if (effects) EffectsControls(viewModel, setup, stage, performance, enabled, compact)
                },
            )
        }
    }
}

/**
 * The DUB screen on a phone held upright: [above] (the host's deck, the status row and the page switch), then [flex] in
 * the room left, then [below]. [flex] is a square no larger than [maxSquare] and the width, centred in its band, or, with
 * [maxSquare] null, the full width and the whole band. It never gets less than [minFlex]: on a screen too short for
 * that the whole screen scrolls ([PortraitFit]), so no control is drawn over another, cut off or out of reach.
 */
@Composable
private fun UprightLayout(
    viewport: Dp,
    minFlex: Dp,
    maxSquare: Dp?,
    above: @Composable () -> Unit,
    flex: @Composable () -> Unit,
    below: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(above, flex, below),
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Gutter),
    ) { (aboveMeasurables, flexMeasurables, belowMeasurables), constraints ->
        val width = constraints.maxWidth
        val loose = Constraints(maxWidth = width)
        val aboveRows = aboveMeasurables.map { it.measure(loose) }
        val belowRows = belowMeasurables.map { it.measure(loose) }
        val gap = Gap.roundToPx()
        val gaps = if (belowRows.isEmpty()) gap else 2 * gap
        val fit = PortraitFit.of(
            viewport = viewport.roundToPx(),
            above = aboveRows.sumOf { it.height },
            below = belowRows.sumOf { it.height },
            gaps = gaps,
            minFlex = minFlex.roundToPx(),
            maxFlex = maxSquare?.let { minOf(it.roundToPx(), width) } ?: Int.MAX_VALUE,
        )
        val flexWidth = if (maxSquare != null) fit.flex else width
        val flexRows = flexMeasurables.map { it.measure(Constraints.fixed(flexWidth, fit.flex)) }
        layout(width, fit.height) {
            var y = 0
            aboveRows.forEach { it.place(0, y); y += it.height }
            y += gap
            flexRows.forEach { it.place((width - it.width) / 2, y + (fit.band - it.height) / 2) }
            y += fit.band
            if (belowRows.isNotEmpty()) {
                y += gap
                belowRows.forEach { it.place(0, y); y += it.height }
            }
        }
    }
}

@Composable
private fun StatusRow(stage: DubStageState, meters: DubMeters, beatLed: Int?, reducedMotion: Boolean) {
    val locale = LocalConfiguration.current.locales[0]
    val bpm = String.format(locale, "%.1f", stage.bpm)
    val clock = when (stage.clockSource) {
        DubClockSource.GRID -> stringResource(R.string.dub_clock_grid, bpm)
        DubClockSource.TAP -> stringResource(R.string.dub_clock_tap, bpm)
        DubClockSource.NONE -> stringResource(R.string.dub_clock_none, bpm)
    }
    Row(Modifier.fillMaxWidth().height(StatusHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gap)) {
        Text(
            stringResource(R.string.dub_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = StageLabel,
        )
        Text(clock, style = MaterialTheme.typography.labelMedium, color = StageLabel.copy(alpha = SecondaryAlpha))
        BeatLeds(lit = beatLed, reducedMotion = reducedMotion)
        Box(Modifier.weight(1f)) { PeakMeter(peak = { meters.peak }, modifier = Modifier.fillMaxWidth()) }
        LimiterLed(lit = DubIndicators.limiterLit(meters.gainReductionDb))
    }
}

/**
 * The effects page next to the preamp: the XY pad centred in the room the fixed rows leave above them, or, on a large
 * screen where that room is too short ([EffectsFit]), a square beside the rows (the pad on the left, the rows on the
 * right, both centred in the height).
 */
@Composable
private fun EffectsPage(
    viewModel: DubViewModel,
    setup: DubSetup,
    stage: DubStageState,
    performance: DubPerformance,
    enabled: Boolean,
    compact: Boolean,
    modifier: Modifier,
) {
    val besideAllowed = LocalWindowClass.current.isLarge
    val controlsMinWidth = maxOf(ControlsMinWidth, (if (compact) CompactPad else ComfortablePad) * 3 + Gap * 2)
    Layout(
        contents = listOf(
            { XyPad(viewModel, setup, performance, enabled, Modifier) },
            { EffectsControls(viewModel, setup, stage, performance, enabled, compact) },
        ),
        modifier = modifier,
    ) { (padMeasurables, controlsMeasurables), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val gap = Gap.roundToPx()
        val controls = controlsMeasurables.first()
        // Only a wide area can move the pad aside: there the rows' height is asked first and they are measured once,
        // at the width the fit gives them; elsewhere they are measured at full width, as before.
        val probe = besideAllowed && width > height
        val stacked = if (probe) null else controls.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = height))
        val fit = EffectsFit.of(
            width = width,
            height = height,
            controlsHeight = stacked?.height ?: controls.maxIntrinsicHeight(width),
            gap = gap,
            minPad = XyBesideBelow.roundToPx(),
            maxPad = XyMaxSize.roundToPx(),
            controlsMinWidth = controlsMinWidth.roundToPx(),
            besideAllowed = besideAllowed,
        )
        val rows = stacked ?: controls.measure(Constraints(minWidth = fit.controlsWidth, maxWidth = fit.controlsWidth, maxHeight = height))
        val pad = padMeasurables.map { it.measure(Constraints.fixed(fit.pad, fit.pad)) }
        layout(width, height) {
            if (fit.beside) {
                pad.forEach { it.place(0, (height - fit.pad) / 2) }
                rows.place(fit.pad + gap, ((height - rows.height) / 2).coerceAtLeast(0))
            } else {
                val band = (height - rows.height - gap).coerceAtLeast(0)
                pad.forEach { it.place((width - fit.pad) / 2, (band - fit.pad) / 2) }
                rows.place(0, band + gap)
            }
        }
    }
}

@Composable
private fun XyPad(viewModel: DubViewModel, setup: DubSetup, performance: DubPerformance, enabled: Boolean, modifier: Modifier) {
    io.sonora.feature.dub.controls.XyFilterPad(
        position = performance.filter,
        onPositionChange = viewModel::onFilter,
        locked = setup.lock,
        onLockedChange = viewModel::onLock,
        enabled = enabled,
        modifier = modifier.alpha(if (enabled) 1f else DisabledAlpha),
    )
}

/** The effects' fixed rows under the XY pad: the division, FEEDBACK and RETURN, the mini kills, the pads, STOP and PLAY. */
@Composable
private fun EffectsControls(
    viewModel: DubViewModel,
    setup: DubSetup,
    stage: DubStageState,
    performance: DubPerformance,
    enabled: Boolean,
    compact: Boolean,
) {
    val padSize = if (compact) CompactPad else ComfortablePad
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else DisabledAlpha), verticalArrangement = Arrangement.spacedBy(Gap)) {
        SegmentedControl(
            options = DubDivision.entries,
            selected = setup.division,
            label = { "${it.numerator}/${it.denominator}" },
            onSelect = { if (enabled) viewModel.onDivision(it) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
            LabelledSlider(
                label = stringResource(R.string.dub_feedback),
                value = setup.feedback / DubSetup.MAX_FEEDBACK,
                onChange = { v, final -> viewModel.onFeedback(v * DubSetup.MAX_FEEDBACK, final) },
                enabled = enabled,
            )
            LabelledSlider(
                label = stringResource(R.string.dub_return),
                value = setup.returnLevel,
                onChange = viewModel::onReturn,
                enabled = enabled,
            )
        }
        MiniKillRow(
            killed = performance.killed.mapTo(HashSet()) { DubWay.entries[it.ordinal] },
            onKilledChange = { way, killed -> viewModel.onKill(PreampWay.entries[way.ordinal], killed) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        PadGrid(viewModel, setup.padBehaviour, enabled, padSize)
        TransportRow(stage, viewModel)
    }
}

@Composable
private fun RowScope.LabelledSlider(label: String, value: Float, onChange: (Float, Boolean) -> Unit, enabled: Boolean) {
    val percent = "${(value * 100).toInt()} %"
    Column(Modifier.weight(1f)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = StageLabel.copy(alpha = SecondaryAlpha))
        CapsuleSlider(
            value = value,
            onValueChange = { if (enabled) onChange(it, false) },
            onValueChangeFinished = { if (enabled) onChange(it, true) },
            contentDescription = label,
            stateDescription = percent,
            modifier = Modifier.fillMaxWidth().height(SliderHeight),
        )
    }
}

/** THROW / DROP / CUT over FREEZE / RUNAWAY / TAP (design §4); in TOGGLE mode every tap latches or unlatches. */
@Composable
private fun PadGrid(viewModel: DubViewModel, behaviour: PadBehaviour, enabled: Boolean, padSize: Dp) {
    val rows = listOf(
        listOf(DubPadKind.THROW, DubPadKind.DROP, DubPadKind.CUT),
        listOf(DubPadKind.FREEZE, DubPadKind.RUNAWAY, null),
    )
    Column(verticalArrangement = Arrangement.spacedBy(Gap)) {
        for (row in rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap)) {
                for (pad in row) {
                    if (pad == null) {
                        TapPad(viewModel, enabled, Modifier.weight(1f).height(padSize))
                    } else {
                        val state = rememberDubPadState()
                        DubPad(
                            label = stringResource(pad.label()),
                            onEngagedChange = { engaged, time -> onPadEdge(viewModel, pad, state, behaviour, engaged, time) },
                            state = state,
                            enabled = enabled,
                            minSize = padSize,
                            modifier = Modifier.weight(1f).height(padSize),
                        )
                    }
                }
            }
        }
    }
}

private fun onPadEdge(viewModel: DubViewModel, pad: DubPadKind, state: DubPadState, behaviour: PadBehaviour, engaged: Boolean, time: Long) {
    // TOGGLE: a press latches at once (the lift then leaves it on), and the next tap lets it go.
    if (behaviour == PadBehaviour.TOGGLE && engaged && state.state == PadState.HELD) state.latch(time)
    viewModel.onPad(pad, engaged, time)
}

/** TAP: each tap sets the echo's tempo; a long press drops it and the echo follows the grid again. */
@Composable
private fun TapPad(viewModel: DubViewModel, enabled: Boolean, modifier: Modifier) {
    val description = stringResource(R.string.dub_tap_description)
    val label = stringResource(R.string.dub_pad_tap)
    TranslucentSurface(
        modifier
            .alpha(if (enabled) 1f else DisabledAlpha)
            .semantics {
                role = Role.Button
                contentDescription = "$label. $description"
                onClick { viewModel.onTap(android.os.SystemClock.uptimeMillis()); true }
                onLongClick { viewModel.onClearTap(); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = { viewModel.onTap(android.os.SystemClock.uptimeMillis()) },
                    onLongPress = { viewModel.onClearTap() },
                )
            },
        shape = RoundedCornerShape(PadCorner),
        darkTheme = true,
    ) {
        Text(
            label,
            modifier = Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = LocalTranslucentContentColors.current.label,
        )
    }
}

@Composable
private fun TransportRow(stage: DubStageState, viewModel: DubViewModel) {
    Row(Modifier.fillMaxWidth().height(TransportHeight), horizontalArrangement = Arrangement.spacedBy(Gap)) {
        TransportButton(
            text = stringResource(if (stage.stopping) R.string.dub_stopping else R.string.dub_stop),
            onClick = viewModel::onStop,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        TransportButton(text = stringResource(R.string.dub_play), onClick = viewModel::onPlay, modifier = Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun TransportButton(text: String, onClick: () -> Unit, modifier: Modifier) {
    TranslucentSurface(
        modifier
            .semantics {
                role = Role.Button
                onClick { onClick(); true }
            }
            .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) },
        shape = RoundedCornerShape(PadCorner),
        darkTheme = true,
    ) {
        Text(
            text,
            modifier = Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.titleSmall,
            color = LocalTranslucentContentColors.current.label,
        )
    }
}

@Composable
private fun PreampPage(viewModel: DubViewModel, setup: DubSetup, performance: DubPerformance, enabled: Boolean, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly) {
        for (way in PreampWay.entries) {
            val ways = setup.preamp[way]
            PreampStrip(
                way = DubWay.entries[way.ordinal],
                highPassHz = ways.highPassHz,
                lowPassHz = ways.lowPassHz,
                levelDb = ways.levelDb.toDouble(),
                killed = way in performance.killed,
                onHighPassChange = { hz, final -> viewModel.onHighPass(way, hz, final) },
                onLowPassChange = { hz, final -> viewModel.onLowPass(way, hz, final) },
                onLevelChange = { db, final -> viewModel.onLevel(way, db, final) },
                onKilledChange = { viewModel.onKill(way, it) },
                enabled = enabled,
                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = StripMinHeight).padding(horizontal = 2.dp),
                width = StripWidth,
            )
        }
    }
}

private fun DubPadKind.label(): Int = when (this) {
    DubPadKind.THROW -> R.string.dub_pad_throw
    DubPadKind.CUT -> R.string.dub_pad_cut
    DubPadKind.DROP -> R.string.dub_pad_drop
    DubPadKind.FREEZE -> R.string.dub_pad_freeze
    DubPadKind.RUNAWAY -> R.string.dub_pad_runaway
}

/** The banner's reason for a bypass the stage reports. */
fun DubBypass.toReason(): DubBypassReason = when (this) {
    DubBypass.BIT_PERFECT -> DubBypassReason.BIT_PERFECT
    DubBypass.REMOTE_RECEIVER -> DubBypassReason.REMOTE_RECEIVER
    DubBypass.TOO_MANY_CHANNELS -> DubBypassReason.TOO_MANY_CHANNELS
    DubBypass.UNSUPPORTED_FORMAT -> DubBypassReason.UNSUPPORTED_FORMAT
}

private val Gutter = 16.dp
private val Gap = 8.dp
private val StatusHeight = 28.dp
private val SliderHeight = 28.dp
private val TransportHeight = 48.dp
private val CompactPad = 72.dp
private val ComfortablePad = 96.dp
private val PadCorner = 20.dp
private val XyMaxSize = 420.dp
private val XyMinSize = 160.dp
private val XyBesideBelow = 200.dp
private val ControlsMinWidth = 260.dp
private val StripWidth = 64.dp
private val StripMinHeight = 240.dp
private val SIDE_BY_SIDE_MIN_WIDTH = 840.dp
private val COMPACT_BELOW_HEIGHT = 760.dp
private const val DisabledAlpha = 0.45f
private const val SecondaryAlpha = 0.7f

/** Text drawn straight on the stage, which is always dark (outside a translucent surface, whose content colours apply). */
private val StageLabel = Color.White
