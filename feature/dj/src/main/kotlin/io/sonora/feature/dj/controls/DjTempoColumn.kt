package io.sonora.feature.dj.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.theme.SonoraTheme
import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.feature.dj.DjTrackReadout

/**
 * The tablet's tempo section (T6): the same controls as [DjTempoSection], the same [DjTempoViewModel.controller] and the same
 * ±0.1 BPM steps, laid out as a tall column for a landscape deck. A [VerticalTempoFader], like a CDJ pitch fader, stands at the
 * trailing edge and takes the column's whole height; beside it, top to bottom, the readout, the row of the two nudges and the
 * 0 % reset, the tempo range and Master Tempo. The column must be at least [DjControlSizes.tempoColumn] tall ([sizes] fixes the
 * row heights); a taller one lets the readout stand apart from the buttons.
 *
 * Everything is dark and translucent over Now Playing's artwork backdrop (the caller provides
 * `LocalTranslucentBackdrop`), as in the horizontal section.
 */
@Composable
fun DjTempoColumn(
    trackReadout: DjTrackReadout?,
    trackBpm: Double?,
    modifier: Modifier = Modifier,
    sizes: DjControlSizes = DjControlSizes.Tablet,
    viewModel: DjTempoViewModel = hiltViewModel(),
) {
    val controller = viewModel.controller
    // The tempo changes on every pointer event of a fader drag. It is held as a State and read, per step, only by the readout's
    // text and the fader's layout and draw; what the other controls show of it (0 %, the range, Master Tempo) changes rarely, so
    // they read it through derived states. A step recomposes the readout and nothing else (it used to recompose this whole
    // column, its translucent controls included, 22-27 % of whose frames missed their deadline on the Pad 6).
    val tempo = controller.tempo.collectAsStateWithLifecycle()
    val atZero by remember(tempo) { derivedStateOf { tempo.value.tempoPercent == 0.0 } }
    val range by remember(tempo) { derivedStateOf { tempo.value.range } }
    val masterTempo by remember(tempo) { derivedStateOf { tempo.value.masterTempo } }
    val canNudge = DjTempoController.canNudge(trackBpm)
    val nudge: (Int) -> Unit = { direction -> controller.nudge(trackBpm, direction * DjTempoText.NUDGE_BPM) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(FaderGap)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.fillMaxWidth().height(sizes.control), contentAlignment = Alignment.CenterStart) {
                TempoReadoutOf(tempo, trackReadout, Modifier.fillMaxWidth(), ReadoutSize)
            }
            Column(verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
                Row(
                    Modifier.fillMaxWidth().height(sizes.control),
                    horizontalArrangement = Arrangement.spacedBy(sizes.gap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BpmNudgeButton(direction = -1, enabled = canNudge, onClick = { nudge(-1) }, size = sizes.control)
                    BpmNudgeButton(direction = 1, enabled = canNudge, onClick = { nudge(1) }, size = sizes.control)
                    TempoResetPill(
                        atZero = atZero,
                        onReset = controller::reset,
                        modifier = Modifier.weight(1f),
                        height = sizes.control,
                    )
                }
                Box(Modifier.fillMaxWidth().height(sizes.slim), contentAlignment = Alignment.Center) {
                    // Over the artwork, always dark like the pills beside it, whatever the app theme.
                    SonoraTheme(darkTheme = true) {
                        TempoRangeControl(range, controller::setRange)
                    }
                }
                MasterTempoPill(on = masterTempo, onToggle = controller::toggleMasterTempo, modifier = Modifier.fillMaxWidth(), height = sizes.slim)
            }
        }
        VerticalTempoFader(
            tempo = { tempo.value },
            onDragStart = controller::startDrag,
            onDrag = controller::dragTo,
            onRelease = controller::endDrag,
            onCancel = controller::cancelDrag,
            onSetTempoPercent = controller::setTempoPercent,
            onReset = controller::reset,
            onNudge = nudge.takeIf { canNudge },
            modifier = Modifier.fillMaxHeight(),
        )
    }
}

/** Between the controls and the fader. */
private val FaderGap = 12.dp

/** The readout's largest size in the column (it shrinks to fit the width beside the fader). */
private val ReadoutSize = 36.sp

/**
 * The readout of the track at the [tempo] now heard: the one control of the column that shows every step of it, so the
 * only one that recomposes for each.
 */
@Composable
internal fun TempoReadoutOf(
    tempo: State<PlaybackTempo>,
    trackReadout: DjTrackReadout?,
    modifier: Modifier = Modifier,
    maxSize: TextUnit,
) {
    val now = tempo.value
    val readout = remember(trackReadout, now) { trackReadout?.at(now) }
    DjTempoReadout(
        bpm = readout?.bpm,
        key = readout?.key?.alphanumeric,
        tempoPercent = now.tempoPercent,
        // Its own layer: the text is redrawn on every step of a drag, and a draw that shares its parent's layer redraws the
        // whole screen around it (measured about 8 ms of recording a frame on the Pad 6, 20 times a second more than idle).
        modifier = modifier.graphicsLayer {},
        color = Color.White,
        maxSize = maxSize,
    )
}
