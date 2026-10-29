package io.sonora.feature.dj.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import io.sonora.core.djmodel.DjCue
import io.sonora.core.djmodel.DjCues
import io.sonora.feature.dj.R

/**
 * The pad panel ([DjCuePanel]) folded into one row of [DjControlSizes.control] height, for a deck strip with room
 * across but not down (the DUB layout in landscape and on a tablet): the `Cues | Loop` switch, then either the eight
 * hot cue pads or the loop transport row (IN, OUT, EXIT or RELOOP, 1/2X, 2X). The same pads and buttons as the panel, with
 * the same actions: tapping an empty pad sets a cue at the playhead, a set one jumps to it, a hot loop engages, and
 * holding an empty pad while a loop plays saves the loop there. Naming, recolouring and memory cues stay in the DJ
 * layout's panel. Use it only where [DjPanelLayout.deckRowFits] the width it gets.
 */
@Composable
fun DjDeckRow(
    cues: List<DjCue>,
    enabled: Boolean,
    position: () -> Double,
    onPad: (slot: Int, positionMs: Double) -> Unit,
    mode: DjPadMode,
    onMode: (DjPadMode) -> Unit,
    loop: DjLoopPanelState,
    loopActions: DjLoopActions,
    sizes: DjControlSizes,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(if (mode == DjPadMode.CUES) R.string.dj_cue_panel_description else R.string.dj_loop_panel_description)
    Row(
        modifier.fillMaxWidth().semantics { paneTitle = title },
        horizontalArrangement = Arrangement.spacedBy(sizes.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PadModeSwitch(mode, onMode, sizes.control)
        if (mode == DjPadMode.CUES) {
            for (slot in 0 until DjCue.HOT_SLOTS) {
                val cue = DjCues.hotCue(cues, slot)
                HotCuePad(
                    slot = slot,
                    cue = cue,
                    enabled = enabled || cue != null,
                    onClick = { onPad(slot, position()) },
                    onLongClick = null,
                    canSaveLoop = loop.canSave && cue == null,
                    onSaveLoop = { loopActions.onSaveToPad(slot) },
                    height = sizes.control,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            LoopTransportRow(loop, loopActions, sizes, Modifier.weight(1f))
        }
    }
}
