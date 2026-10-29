package io.sonora.feature.cdjexport

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.Artwork
import io.sonora.core.designsystem.component.TranslucentAlert
import io.sonora.core.designsystem.component.TranslucentAlertButton
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.PillButton
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.text.rememberStrings

/** Every user action of the "Export to Rekordbox" section, so its composables stay stateless. */
internal class ExportToStickActions(
    val onAddFiles: () -> Unit,
    val onAddFolder: () -> Unit,
    val onRemoveTrack: (String) -> Unit,
    val onClearTracks: () -> Unit,
    val onPickStick: () -> Unit,
    /** Chooses one of several connected volumes, by its id. */
    val onPickVolume: (String) -> Unit,
    val onPlaylistNameChange: (String) -> Unit,
    val onExport: () -> Unit,
    val onConfirmReplace: () -> Unit,
    val onDismissReplace: () -> Unit,
    val onCancel: () -> Unit,
    val onDone: () -> Unit,
    /** Copies a technical report (plain text) to the clipboard. */
    val onCopyDetails: (String) -> Unit,
)

@Composable
internal fun ExportToStickSection(
    state: ExportToStickState,
    actions: ExportToStickActions,
    eject: EjectControls? = null,
    /** The library's artwork of the listed tracks, by track URI (see [ExportWashViewModel]). */
    artwork: Map<String, String> = emptyMap(),
) {
    val strings = rememberStrings()
    SectionHeader(stringResource(R.string.cdj_export_header))
    Text(
        exportIntro(state.profile, strings),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        notAffiliatedNotice(strings),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (val step = state.step) {
            ExportStep.Setup, is ExportStep.ConfirmReplace -> {
                Setup(state, actions, artwork)
                if (step is ExportStep.ConfirmReplace) ReplaceConfirmation(step, actions)
            }
            is ExportStep.Running -> Running(step, actions.onCancel)
            is ExportStep.Done -> {
                Done(step, actions)
                eject?.let { Eject(it) }
            }
        }
    }
}

@Composable
private fun Setup(state: ExportToStickState, actions: ExportToStickActions, artwork: Map<String, String>) {
    val strings = rememberStrings()
    Tracks(state, actions, artwork)
    if (state.convertCount > 0) {
        Text(
            conversionNotice(state.convertCount, state.profile, strings),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (state.rejected.isNotEmpty()) Rejected(state)
    Stick(state, actions)
    OutlinedTextField(
        value = state.playlistName,
        onValueChange = actions.onPlaylistNameChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.cdj_playlist_field)) },
        singleLine = true,
        supportingText = { Text(stringResource(R.string.cdj_playlist_hint)) },
    )
    val free = state.stick?.freeBytes
    if (state.lacksSpace && free != null) {
        Text(
            spaceProblemText(state.neededBytes, free, strings),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    state.notice?.let {
        Text(it.message(strings), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    val count = state.tracks.size
    PillButton(
        pluralStringResource(R.plurals.cdj_export_button, count, count),
        icon = null,
        onClick = actions.onExport,
        enabled = state.canExport,
    )
}

@Composable
private fun Tracks(state: ExportToStickState, actions: ExportToStickActions, artwork: Map<String, String>) {
    val strings = rememberStrings()
    Card {
        Text(stringResource(R.string.cdj_tracks_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (state.tracks.isEmpty()) {
            Text(
                stringResource(R.string.cdj_tracks_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                pluralStringResource(
                    R.plurals.cdj_tracks_ready,
                    state.tracks.size,
                    state.tracks.size,
                    formatCapacity(state.totalBytes, strings.locale),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TrackList(state.tracks.take(MAX_LISTED), artwork, actions.onRemoveTrack)
            if (state.tracks.size > MAX_LISTED) {
                Text(pluralStringResource(R.plurals.cdj_more, state.tracks.size - MAX_LISTED, state.tracks.size - MAX_LISTED), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.inspecting) Busy(stringResource(R.string.cdj_checking_files))
    }
    // A flow row, so a button that does not fit goes to the next line instead of being squeezed.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton(stringResource(R.string.cdj_add_files), icon = SonoraIcons.Add, onClick = actions.onAddFiles, enabled = !state.inspecting)
        PillButton(stringResource(R.string.cdj_add_folder), icon = SonoraIcons.Folder, onClick = actions.onAddFolder, enabled = !state.inspecting)
        if (state.tracks.isNotEmpty() || state.rejected.isNotEmpty()) {
            PillButton(stringResource(R.string.cdj_clear), icon = null, onClick = actions.onClearTracks)
        }
    }
}

/**
 * The listed tracks on the card's translucent surface, as many rows as fit [TrackListMaxHeight] and the rest by
 * scrolling: the translucency draws over its own area, so a list that grew with its tracks would make it ever more
 * expensive.
 */
@Composable
private fun TrackList(tracks: List<PickedTrack>, artwork: Map<String, String>, onRemove: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = TrackListMaxHeight)) {
        itemsIndexed(tracks, key = { _, track -> track.uri }) { index, track ->
            TrackRow(track, artwork[track.uri], onRemove = { onRemove(track.uri) })
            if (index < tracks.lastIndex) InsetDivider(start = TrackArtworkSize + TrackGap)
        }
    }
}

@Composable
private fun TrackRow(track: PickedTrack, artworkUri: String?, onRemove: () -> Unit) {
    val strings = rememberStrings()
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(artworkUri, Modifier.size(TrackArtworkSize), RoundedCornerShape(10.dp))
        Spacer(Modifier.width(TrackGap))
        Column(Modifier.weight(1f)) {
            Text(track.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(trackDetail(track.file, strings), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRemove) {
            Icon(SonoraIcons.Clear, contentDescription = stringResource(R.string.cdj_remove_track, track.name))
        }
    }
}

private val TrackArtworkSize = 44.dp
private val TrackGap = 12.dp

/** About six rows and a peek of the next, so it shows the list scrolls. */
private val TrackListMaxHeight = 360.dp

@Composable
private fun Rejected(state: ExportToStickState) {
    val rejected = state.rejected
    val strings = rememberStrings()
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(colors.errorContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(pluralStringResource(R.plurals.cdj_rejected_title, rejected.size, rejected.size), style = MaterialTheme.typography.titleMedium, color = colors.onErrorContainer)
        rejected.take(MAX_LISTED).forEach {
            Text(stringResource(R.string.cdj_name_reason, it.name, it.reason.message(state.profile, strings)), style = MaterialTheme.typography.bodySmall, color = colors.onErrorContainer)
        }
        if (rejected.size > MAX_LISTED) {
            Text(pluralStringResource(R.plurals.cdj_more, rejected.size - MAX_LISTED, rejected.size - MAX_LISTED), style = MaterialTheme.typography.bodySmall, color = colors.onErrorContainer)
        }
    }
}

@Composable
private fun Stick(state: ExportToStickState, actions: ExportToStickActions) {
    val stick = state.stick
    val strings = rememberStrings()
    Card {
        Text(stringResource(R.string.cdj_stick_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        when {
            state.openingStick -> Busy(stringResource(R.string.cdj_opening_stick))
            stick == null -> {
                val connected = state.attached.singleOrNull()
                Text(
                    if (connected != null) {
                        stringResource(R.string.cdj_stick_connected, volumeSummary(connected, strings))
                    } else {
                        stringResource(R.string.cdj_pick_stick_hint)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                DetailRow(stringResource(R.string.cdj_detail_name), stick.name ?: stringResource(R.string.cdj_stick_title))
                state.stickSize?.let { DetailRow(stringResource(R.string.cdj_detail_size), formatCapacity(it, strings.locale)) }
                DetailRow(
                    stringResource(R.string.cdj_detail_free_space),
                    stick.freeBytes?.let { formatCapacity(it, strings.locale) } ?: stringResource(R.string.cdj_unknown),
                )
                if (state.tracks.isNotEmpty()) {
                    DetailRow(
                        stringResource(R.string.cdj_detail_export_needs),
                        stringResource(R.string.cdj_about_size, formatCapacity(state.neededBytes, strings.locale)),
                    )
                }
                if (stick.problem != null) {
                    Text(stick.problem.message(strings), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(
                        stick.library.description(strings),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (state.attached.size > 1) {
        // A stick and an SD card look alike to apps: the user says which one is the stick.
        Text(
            stringResource(R.string.cdj_sticks_connected),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.attached.forEach { volume ->
                PillButton(
                    volumeSummary(volume, strings),
                    icon = null,
                    onClick = { actions.onPickVolume(volume.uuid) },
                    enabled = !state.openingStick,
                )
            }
        }
    }
    PillButton(
        stringResource(if (stick == null) R.string.cdj_choose_stick else R.string.cdj_choose_another_stick),
        icon = null, onClick = actions.onPickStick, enabled = !state.openingStick)
}

@Composable
private fun ReplaceConfirmation(step: ExportStep.ConfirmReplace, actions: ExportToStickActions) {
    TranslucentAlert(
        title = stringResource(R.string.cdj_replace_title),
        message = step.library.replaceWarning(rememberStrings()),
        onDismiss = actions.onDismissReplace,
        confirm = TranslucentAlertButton(stringResource(R.string.cdj_replace_confirm), destructive = true, onClick = actions.onConfirmReplace),
        cancel = TranslucentAlertButton(stringResource(R.string.cdj_cancel), onClick = actions.onDismissReplace),
    )
}

@Composable
private fun Running(step: ExportStep.Running, onCancel: () -> Unit) {
    Card {
        Text(stringResource(R.string.cdj_exporting_to, step.stickName), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        val progress = step.progress
        if (progress == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { progress.overallFraction }, modifier = Modifier.fillMaxWidth())
        }
        Text(exportProgressText(step, rememberStrings()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.cdj_export_keep),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    PillButton(
        stringResource(if (step.cancelling) R.string.cdj_cancelling else R.string.cdj_cancel),
        icon = null,
        onClick = onCancel,
        enabled = !step.cancelling,
    )
}

@Composable
private fun Done(step: ExportStep.Done, actions: ExportToStickActions) {
    val summary = summarize(step.result, rememberStrings())
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!summary.isError) {
                Icon(SonoraIcons.Checkmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                summary.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (summary.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(summary.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Listing(stringResource(R.string.cdj_skipped), summary.skipped)
        Listing(stringResource(R.string.cdj_not_exported), summary.failed)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton(stringResource(R.string.cdj_done), icon = null, onClick = actions.onDone)
        step.technicalDetails?.let { details ->
            PillButton(stringResource(R.string.cdj_copy_details), icon = null, onClick = { actions.onCopyDetails(details) })
        }
    }
}

@Composable
private fun Listing(title: String, lines: List<String>) {
    if (lines.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    lines.take(MAX_LISTED).forEach {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (lines.size > MAX_LISTED) Text(pluralStringResource(R.plurals.cdj_more, lines.size - MAX_LISTED, lines.size - MAX_LISTED), style = MaterialTheme.typography.bodySmall)
}

/** Long lists show their first entries only; the screen scrolls as a whole. */
internal const val MAX_LISTED = 50
