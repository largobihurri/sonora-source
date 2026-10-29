package io.sonora.feature.dj.controls

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.component.TranslucentAlert
import io.sonora.core.designsystem.component.TranslucentAlertButton
import io.sonora.core.designsystem.component.TranslucentAlertTextField
import io.sonora.core.designsystem.component.TranslucentPill
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.text.LocaleFormats
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.core.djmodel.CueColor
import io.sonora.core.djmodel.CuePalette
import io.sonora.core.djmodel.DjCue
import io.sonora.core.djmodel.DjCues
import io.sonora.feature.dj.R
import io.sonora.feature.dj.waveform.CueMarkerGeometry
import kotlin.math.roundToLong

/**
 * The pad panel of the DJ screen, two rows of dark translucency over Now Playing's artwork (the caller provides
 * `LocalTranslucentBackdrop`), with two faces swapped by the `Cues | Loop` switch ([mode]); the panel is [DjControlSizes.modePanel]
 * tall in both: [sizes] fixes the heights of both rows and the gap between them (D4.1b), so the screen can budget its height.
 *
 * The **Cues** face: the eight hot cue pads A to H, then the switch, the Q (quantize) switch, the previous and next memory
 * cue and "+ Memory", which adds a memory cue at the playhead (a memory loop, "+ Loop", while a loop plays). A pad that holds
 * a loop (a hot loop) is ringed; tapping it engages the loop; holding an empty pad while a loop plays saves the loop there.
 * The **Loop** face (D4.2c): IN, OUT, EXIT or RELOOP, 1/2X and 2X, then the switch, Q and the beat-length stepper
 * ([LoopTransportRow], [BeatLengthStepperRow]), driven by [loop] and [loopActions].
 *
 * A pad is lit in its cue's colour once it is set. Tapping an empty pad sets it at the playhead and tapping a set one
 * jumps to it ([onPad], which says which); holding a pad opens the [CueEditor] for its cue (name, colour, delete).
 * Holding "+ Memory" edits the memory cue at the playhead, if there is one. Every control has a spoken description and
 * the pad and memory actions are also offered as accessibility actions. [position] is read when a control is used,
 * never while drawing, so the panel does not recompose with playback.
 *
 * [enabled] is `false` until the track has an analysis: the cues need a track the store knows, and the pads and
 * "+ Memory" then dim and ignore taps (the previous and next buttons and the pads that are already set still jump).
 */
@Composable
fun DjCuePanel(
    cues: List<DjCue>,
    quantize: Boolean,
    enabled: Boolean,
    position: () -> Double,
    onPad: (slot: Int, positionMs: Double) -> Unit,
    onQuantize: (Boolean) -> Unit,
    onAddMemory: (positionMs: Double) -> Unit,
    onPreviousMemory: (positionMs: Double) -> Unit,
    onNextMemory: (positionMs: Double) -> Unit,
    onRename: (id: String, name: String) -> Unit,
    onRecolour: (id: String, color: CueColor) -> Unit,
    onDelete: (id: String) -> Unit,
    modifier: Modifier = Modifier,
    /** The row heights: [DjControlSizes.control] for the pads, [DjControlSizes.slim] for the Q and memory row. */
    sizes: DjControlSizes = DjControlSizes.Comfortable,
    /** Which face is shown. */
    mode: DjPadMode = DjPadMode.CUES,
    onMode: (DjPadMode) -> Unit = {},
    /** What the loop buttons show and can do, and what they call. */
    loop: DjLoopPanelState = DjLoopPanelState.OFF,
    loopActions: DjLoopActions = DjLoopActions.NONE,
) {
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val panel = stringResource(R.string.dj_cue_panel_description)
    val loopPanel = stringResource(R.string.dj_loop_panel_description)
    Column(
        modifier.fillMaxWidth().semantics { paneTitle = if (mode == DjPadMode.CUES) panel else loopPanel },
        verticalArrangement = Arrangement.spacedBy(sizes.gap),
    ) {
        if (mode == DjPadMode.CUES) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
                for (slot in 0 until DjCue.HOT_SLOTS) {
                    val cue = DjCues.hotCue(cues, slot)
                    HotCuePad(
                        slot = slot,
                        cue = cue,
                        enabled = enabled || cue != null,
                        onClick = { onPad(slot, position()) },
                        onLongClick = { if (cue != null) editingId = cue.id },
                        canSaveLoop = loop.canSave && cue == null,
                        onSaveLoop = { loopActions.onSaveToPad(slot) },
                        height = sizes.control,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            LoopTransportRow(loop, loopActions, sizes)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sizes.gap), verticalAlignment = Alignment.CenterVertically) {
            PadModeSwitch(mode, onMode, sizes.slim)
            QuantizeButton(quantize, onQuantize, sizes.slim)
            if (mode == DjPadMode.CUES) {
                val weights = DjPanelLayout.CueRowWeights
                GridButton(
                    label = stringResource(R.string.dj_cue_memory_previous_label),
                    description = stringResource(R.string.dj_cue_memory_previous),
                    onClick = { onPreviousMemory(position()) },
                    modifier = Modifier.weight(weights[0]),
                    height = sizes.slim,
                )
                MemoryButton(
                    enabled = enabled,
                    loop = loop.canSave,
                    onAdd = { onAddMemory(position()) },
                    onEdit = { DjCues.nearestMemory(cues, position(), MEMORY_EDIT_TOLERANCE_MS)?.let { editingId = it.id } },
                    height = sizes.slim,
                    modifier = Modifier.weight(weights[1]),
                )
                GridButton(
                    label = stringResource(R.string.dj_cue_memory_next_label),
                    description = stringResource(R.string.dj_cue_memory_next),
                    onClick = { onNextMemory(position()) },
                    modifier = Modifier.weight(weights[2]),
                    height = sizes.slim,
                )
            } else {
                BeatLengthStepperRow(loop, loopActions, sizes.slim, sizes.gap, Modifier.weight(1f))
            }
        }
    }
    val editing = editingId?.let { id -> cues.firstOrNull { it.id == id } }
    if (editing != null) {
        CueEditor(
            cue = editing,
            onDismiss = { editingId = null },
            onSave = { name, color ->
                if (name.trim() != editing.name) onRename(editing.id, name)
                if (color != editing.color) onRecolour(editing.id, color)
                editingId = null
            },
            onDelete = {
                onDelete(editing.id)
                editingId = null
            },
        )
    } else if (editingId != null) {
        // The cue went away (deleted, or the track changed) while its editor was open.
        editingId = null
    }
}

/**
 * One hot cue pad: dark translucency washed in the cue's colour when set, lettered A to H. Holding a set pad calls
 * [onLongClick] (the cue editor); a null [onLongClick] offers no hold on a set pad (where there is no editor).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HotCuePad(
    slot: Int,
    cue: DjCue?,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    canSaveLoop: Boolean,
    onSaveLoop: () -> Unit,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val letter = DjCue.slotLabel(slot).toString()
    val start = cue?.let { LocaleFormats.duration(it.positionMs.roundToLong()) }
    val end = cue?.loopEndMs?.let { LocaleFormats.duration(it.roundToLong()) }
    val description = when {
        cue == null -> stringResource(R.string.dj_cue_pad_empty, letter)
        end != null && cue.name.isBlank() -> stringResource(R.string.dj_loop_pad_set, letter, start!!, end)
        end != null -> stringResource(R.string.dj_loop_pad_set_named, letter, cue.name, start!!, end)
        cue.name.isBlank() -> stringResource(R.string.dj_cue_pad_set, letter, start!!)
        else -> stringResource(R.string.dj_cue_pad_set_named, letter, cue.name, start!!)
    }
    val clickLabel = stringResource(
        when {
            cue == null -> R.string.dj_cue_pad_set_action
            cue.isLoop -> R.string.dj_loop_pad_engage_action
            else -> R.string.dj_cue_pad_jump_action
        },
        letter,
    )
    val longClickLabel = when {
        cue != null && onLongClick != null -> stringResource(R.string.dj_cue_pad_edit_action, letter)
        cue != null -> null
        canSaveLoop -> stringResource(R.string.dj_loop_pad_save_action, letter)
        else -> null
    }
    val argb = cue?.let { OPAQUE or it.color.rgb }
    val color = argb?.let(::Color)
    val currentColor by rememberUpdatedState(color)
    val shape = PadShape
    TranslucentSurface(
        modifier.height(height),
        shape = shape,
        elevation = 0.dp,
        darkTheme = true,
        tinted = false,
        wash = { currentColor?.copy(alpha = PadWashAlpha) ?: Color.Transparent },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .semantics {
                    contentDescription = description
                    if (!enabled) disabled()
                }
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClickLabel = clickLabel,
                    onLongClickLabel = longClickLabel,
                    onLongClick = when {
                        cue != null -> onLongClick
                        canSaveLoop -> onSaveLoop
                        else -> null
                    },
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val ink = argb?.let(CueMarkerGeometry::inkFor)
            // A hot loop is ringed in the ink of its letter, so a loop pad reads apart from a cue at a glance.
            if (cue?.isLoop == true && ink != null) {
                Box(Modifier.fillMaxSize().padding(3.dp).border(1.5.dp, Color(ink).copy(alpha = LoopRingAlpha), RoundedCornerShape(9.dp)))
            }
            Text(
                letter,
                color = when {
                    ink != null -> Color(ink)
                    enabled -> LocalTranslucentContentColors.current.label.copy(alpha = EmptyLetterAlpha)
                    else -> LocalTranslucentContentColors.current.label.copy(alpha = DisabledAlpha)
                },
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/** The Q pill: lit (accent) while quantize is on, announced as a switch with its state. */
@Composable
private fun QuantizeButton(on: Boolean, onChange: (Boolean) -> Unit, height: Dp) {
    val description = stringResource(R.string.dj_cue_quantize_description)
    val state = stringResource(if (on) R.string.dj_cue_quantize_on else R.string.dj_cue_quantize_off)
    TranslucentPill(
        onClick = { onChange(!on) },
        modifier = Modifier.width(DjPanelLayout.QuantizeWidth),
        height = height,
        onClickLabel = description,
        startPadding = 4.dp,
        endPadding = 4.dp,
        darkTheme = true,
        tinted = false,
        contentModifier = Modifier.semantics {
            contentDescription = description
            stateDescription = state
            selected = on
        },
    ) {
        val colors = LocalTranslucentContentColors.current
        Text(
            stringResource(R.string.dj_cue_quantize_label),
            Modifier.weight(1f).reflected(),
            color = if (on) colors.accent else colors.label.copy(alpha = EmptyLetterAlpha),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * "+ Memory": a tap adds a memory cue at the playhead, a hold edits the memory cue there (to rename, recolour or delete it).
 * While a loop plays ([loop]) it reads "+ Loop" and a tap saves the loop as a memory loop.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoryButton(enabled: Boolean, loop: Boolean, onAdd: () -> Unit, onEdit: () -> Unit, height: Dp, modifier: Modifier) {
    val description = stringResource(if (loop) R.string.dj_loop_memory_add_description else R.string.dj_cue_memory_add_description)
    val editLabel = stringResource(R.string.dj_cue_memory_edit_action)
    val label = stringResource(if (loop) R.string.dj_loop_memory_add else R.string.dj_cue_memory_add)
    val shape = CapsuleShape
    TranslucentSurface(modifier.height(height), shape = shape, elevation = 0.dp, darkTheme = true, tinted = false) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .semantics {
                    contentDescription = description
                    if (!enabled) disabled()
                }
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = description,
                    onLongClickLabel = editLabel,
                    onLongClick = onEdit,
                    onClick = { if (enabled) onAdd() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            val colors = LocalTranslucentContentColors.current
            Text(
                label,
                color = (if (enabled) colors.accent else colors.label).copy(alpha = if (enabled) 1f else DisabledAlpha),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/**
 * The editor of one cue, an alert on translucency: its name, its colour (the sixteen of [CuePalette], the selected one
 * ringed and announced) and Save, Cancel and Delete. A colour that is not in the palette (a cue from another
 * program) shows as an extra swatch so it is not lost by opening and saving.
 */
@Composable
private fun CueEditor(cue: DjCue, onDismiss: () -> Unit, onSave: (String, CueColor) -> Unit, onDelete: () -> Unit) {
    var name by rememberSaveable(cue.id) { mutableStateOf(cue.name) }
    var color by remember(cue.id) { mutableStateOf(cue.color) }
    val title = when {
        cue.label != null && cue.isLoop -> stringResource(R.string.dj_loop_edit_title_hot, cue.label.toString())
        cue.label != null -> stringResource(R.string.dj_cue_edit_title_hot, cue.label.toString())
        cue.isLoop -> stringResource(R.string.dj_loop_edit_title_memory)
        else -> stringResource(R.string.dj_cue_edit_title_memory)
    }
    TranslucentAlert(
        title = title,
        onDismiss = onDismiss,
        confirm = TranslucentAlertButton(stringResource(R.string.dj_cue_edit_save)) { onSave(name, color) },
        cancel = TranslucentAlertButton(stringResource(R.string.dj_cue_edit_cancel), onClick = onDismiss),
        extra = TranslucentAlertButton(stringResource(R.string.dj_cue_edit_delete), destructive = true, onClick = onDelete),
    ) {
        TranslucentAlertTextField(
            value = name,
            onValueChange = { name = it.take(DjCue.MAX_NAME_LENGTH) },
            label = stringResource(R.string.dj_cue_name_label),
        )
        val swatches = if (color.paletteIndex == 0) CuePalette.COLORS + color else CuePalette.COLORS
        for (row in swatches.chunked(SwatchesPerRow)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (swatch in row) Swatch(swatch, selected = swatch == color, onClick = { color = swatch })
                // Short last row: keep the columns of the rows above.
                repeat(SwatchesPerRow - row.size) { Box(Modifier.size(SwatchSize)) }
            }
        }
    }
}

@Composable
private fun Swatch(color: CueColor, selected: Boolean, onClick: () -> Unit) {
    val name = colourName(color)
    val chosen = stringResource(R.string.dj_cue_colour_selected)
    val fill = Color(OPAQUE or color.rgb)
    Box(
        Modifier
            .size(SwatchSize)
            .clip(CircleShape)
            .background(fill)
            .border(if (selected) 3.dp else 0.dp, if (selected) Color.White else Color.Transparent, CircleShape)
            .semantics {
                contentDescription = name
                this.selected = selected
                if (selected) stateDescription = chosen
            }
            .combinedClickableSwatch(onClick),
    )
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableSwatch(onClick: () -> Unit): Modifier =
    combinedClickable(role = Role.RadioButton, onClick = onClick)

@Composable
private fun colourName(color: CueColor): String = stringResource(
    when (color.paletteIndex) {
        1 -> R.string.dj_cue_color_1
        2 -> R.string.dj_cue_color_2
        3 -> R.string.dj_cue_color_3
        4 -> R.string.dj_cue_color_4
        5 -> R.string.dj_cue_color_5
        6 -> R.string.dj_cue_color_6
        7 -> R.string.dj_cue_color_7
        8 -> R.string.dj_cue_color_8
        9 -> R.string.dj_cue_color_9
        10 -> R.string.dj_cue_color_10
        11 -> R.string.dj_cue_color_11
        12 -> R.string.dj_cue_color_12
        13 -> R.string.dj_cue_color_13
        14 -> R.string.dj_cue_color_14
        15 -> R.string.dj_cue_color_15
        16 -> R.string.dj_cue_color_16
        else -> R.string.dj_cue_color_custom
    },
)

/** How far from the playhead a hold on "+ Memory" still finds the memory cue to edit. */
private const val MEMORY_EDIT_TOLERANCE_MS = 400.0

private const val LoopRingAlpha = 0.85f
private const val PadWashAlpha = 0.6f
private const val EmptyLetterAlpha = 0.6f
private const val DisabledAlpha = 0.38f
private const val SwatchesPerRow = 8
private const val OPAQUE: Int = 0xFF000000.toInt()

private val PadShape = RoundedCornerShape(12.dp)
private val SwatchSize = 28.dp
