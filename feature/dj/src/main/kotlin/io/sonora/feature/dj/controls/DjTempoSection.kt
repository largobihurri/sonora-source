package io.sonora.feature.dj.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.theme.SonoraTheme
import io.sonora.feature.dj.DjTrackReadout

/**
 * The CDJ tempo section of the DJ layout ([sizes] fixes its row heights), in three rows: the readout ([trackReadout] at the tempo heard, the BPM as heard and the key as it
 * sounds, from the DJ view model) with the reset pill; the fader between the −0.1 and +0.1 BPM buttons; the range and
 * Master Tempo. Nothing else shares the readout's row, so it never loses its tempo percent to a neighbour (the
 * "Edited" marker of a changed beat grid sits on the waveform instead, see `DjTimeline`). Every control acts through [DjTempoViewModel.controller]; the BPM nudges need the playing track's own
 * BPM ([trackBpm], at its own speed, null when unknown) and are disabled without it.
 *
 * Everything here is dark and translucent over Now Playing's artwork backdrop (the caller provides
 * `LocalTranslucentBackdrop`): the range control, which otherwise follows the app theme, is drawn in the dark theme so
 * it matches the pills around it.
 */
@Composable
fun DjTempoSection(
    trackReadout: DjTrackReadout?,
    trackBpm: Double?,
    modifier: Modifier = Modifier,
    /** The row heights and gaps: the section is [DjControlSizes.tempoSection] tall (D4.1b). */
    sizes: DjControlSizes = DjControlSizes.Comfortable,
    viewModel: DjTempoViewModel = hiltViewModel(),
) {
    val controller = viewModel.controller
    // As in DjTempoColumn: a fader step recomposes the readout and nothing else.
    val tempo = controller.tempo.collectAsStateWithLifecycle()
    val atZero by remember(tempo) { derivedStateOf { tempo.value.tempoPercent == 0.0 } }
    val range by remember(tempo) { derivedStateOf { tempo.value.range } }
    val masterTempo by remember(tempo) { derivedStateOf { tempo.value.masterTempo } }
    val canNudge = DjTempoController.canNudge(trackBpm)
    val nudge: (Int) -> Unit = { direction -> controller.nudge(trackBpm, direction * DjTempoText.NUDGE_BPM) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
        Row(Modifier.fillMaxWidth().height(sizes.control), verticalAlignment = Alignment.CenterVertically) {
            TempoReadoutOf(tempo, trackReadout, Modifier.weight(1f), readoutSize(sizes.control))
            TempoResetPill(atZero = atZero, onReset = controller::reset, height = sizes.control)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ItemGap), verticalAlignment = Alignment.CenterVertically) {
            BpmNudgeButton(direction = -1, enabled = canNudge, onClick = { nudge(-1) }, size = sizes.control)
            TempoFader(
                tempo = { tempo.value },
                onDragStart = controller::startDrag,
                onDrag = controller::dragTo,
                onRelease = controller::endDrag,
                onCancel = controller::cancelDrag,
                onSetTempoPercent = controller::setTempoPercent,
                onReset = controller::reset,
                onNudge = nudge.takeIf { canNudge },
                modifier = Modifier.weight(1f),
                height = sizes.control,
            )
            BpmNudgeButton(direction = 1, enabled = canNudge, onClick = { nudge(1) }, size = sizes.control)
        }
        Row(Modifier.fillMaxWidth().height(sizes.slim), horizontalArrangement = Arrangement.spacedBy(ItemGap), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                // Over the artwork, always dark like the pills beside it, whatever the app theme.
                SonoraTheme(darkTheme = true) {
                    TempoRangeControl(range, controller::setRange)
                }
            }
            MasterTempoPill(on = masterTempo, onToggle = controller::toggleMasterTempo, height = sizes.slim)
        }
    }
}

private val ItemGap = 10.dp

/** The readout's largest size: 40 sp in a 44 dp row, 32 sp (a line of about 38 dp) in a compact one. */
private fun readoutSize(control: Dp): TextUnit = if (control >= ComfortableRow) 40.sp else 32.sp

private val ComfortableRow = 44.dp
