package io.sonora.feature.converter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.ActionRow
import io.sonora.core.designsystem.component.BottomChromePadding
import io.sonora.core.designsystem.component.TranslucentBarHeight
import io.sonora.core.designsystem.component.GroupFooter
import io.sonora.core.designsystem.component.GroupHeader
import io.sonora.core.designsystem.component.GroupedCard
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.LargeTitle
import io.sonora.core.designsystem.component.NavigationValueRow
import io.sonora.core.designsystem.component.ProminentButton
import io.sonora.core.designsystem.component.ScrollToTopEffect
import io.sonora.core.designsystem.component.SegmentedControl
import io.sonora.core.designsystem.component.SwitchRow
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.theme.SonoraTheme
import io.sonora.core.designsystem.layout.ContentWidthLazyColumn
import io.sonora.core.designsystem.layout.TopChromeSpace
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.OutputSelection
import io.sonora.core.domain.conversion.OutputTarget
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.model.BatchProgress
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.ConversionBatch
import io.sonora.core.domain.model.PickedSource
import io.sonora.core.domain.port.OutputRootFolder
import io.sonora.feature.converter.BatchSnapshot
import io.sonora.feature.converter.ConverterUiState
import io.sonora.feature.converter.FormatChoice
import io.sonora.feature.converter.PendingSource
import io.sonora.feature.converter.R

/** What the main converter screen can ask for; the container wires these to the view model and the pickers. */
internal class ConverterMainActions(
    /** Opens the batch-wide output picker. */
    val onOpenBatchOutput: () -> Unit = {},
    /** Opens a source's output: the picker for a song or file, the folder's format and songs for a folder. */
    val onOpenSource: (PendingSource) -> Unit = {},
    val onChooseOutputFolder: () -> Unit = {},
    val onAddMusic: () -> Unit = {},
    val onBrowseFiles: () -> Unit = {},
    val onBrowseFolder: () -> Unit = {},
    val onRemoveSource: (PendingSource) -> Unit = {},
    val onSelectMode: (PerformanceMode) -> Unit = {},
    val onOverwriteChange: (Boolean) -> Unit = {},
    val onConvert: () -> Unit = {},
    val onCancelBatch: () -> Unit = {},
    val onReplaceOriginals: (BatchSnapshot) -> Unit = {},
    val onShowBatch: (Long) -> Unit = {},
    val onPlayConverted: (Long) -> Unit = {},
    val onDismissBatch: () -> Unit = {},
    val onOpenHistory: () -> Unit = {},
)

/**
 * The converter's main screen: the active batch (if any), then the next batch's batch-wide output format, output
 * folder, sources (each with what it converts to), performance mode and the overwrite switch, the Convert button and
 * the way into the history.
 */
@Composable
internal fun ConverterMainContent(
    state: ConverterUiState,
    actions: ConverterMainActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    ScrollToTopEffect(listState)
    ContentWidthLazyColumn(modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = BottomChromePadding)) {
        item {
            // Clear of the floating gear's bar, or of the shell's top pill when that reaches lower.
            TopChromeSpace(own = TranslucentBarHeight)
            LargeTitle(stringResource(R.string.converter_title))
        }
        state.activeBatch?.let { snapshot ->
            item(key = "active") {
                ActiveBatchCard(
                    snapshot,
                    skippedFolders = state.skippedFolders,
                    onCancel = actions.onCancelBatch,
                    onReplaceOriginals = { actions.onReplaceOriginals(snapshot) },
                    onShowDetails = { actions.onShowBatch(snapshot.batch.id) },
                    onPlayConverted = { actions.onPlayConverted(snapshot.batch.id) },
                    onDismiss = actions.onDismissBatch,
                )
            }
        }
        item { OutputSection(state.output.batchWide, actions.onOpenBatchOutput) }
        item { OutputFolderSection(state.outputFolder, actions.onChooseOutputFolder) }
        item(key = "sources") { SourcesSection(state.sources, state.output, actions) }
        item { PerformanceSection(state.mode, actions.onSelectMode) }
        item {
            GroupedCard(Modifier.padding(top = 24.dp)) {
                SwitchRow(stringResource(R.string.converter_overwrite), state.overwriteOriginals, actions.onOverwriteChange)
            }
            GroupFooter(stringResource(R.string.converter_overwrite_footer))
        }
        item {
            ProminentButton(
                text = stringResource(if (state.starting) R.string.converter_starting else R.string.converter_convert),
                onClick = actions.onConvert,
                enabled = state.canConvert,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp),
            )
        }
        item {
            val batches = state.history.sumOf { it.entries.size }
            GroupedCard(Modifier.padding(top = 24.dp)) {
                NavigationValueRow(
                    stringResource(R.string.converter_history),
                    actions.onOpenHistory,
                    icon = SonoraIcons.History,
                    value = batches.takeIf { it > 0 }?.toString(),
                )
            }
        }
    }
}

/** The batch-wide output ("all at once"): every source not set by hand converts to it. */
@Composable
private fun OutputSection(batchWide: OutputTarget, onOpen: () -> Unit) {
    GroupHeader(stringResource(R.string.converter_profile_header))
    GroupedCard {
        OutputValueRow(
            formatChoiceName(FormatChoice.of(batchWide)),
            batchWide,
            manual = false,
            onClick = onOpen,
            icon = SonoraIcons.AudioFile,
            summaryOnly = true,
        )
    }
    GroupFooter(stringResource(R.string.converter_format_batch_footer))
}

@Composable
private fun OutputFolderSection(folder: OutputRootFolder?, onChoose: () -> Unit) {
    GroupHeader(stringResource(R.string.converter_output_header))
    GroupedCard {
        if (folder == null) {
            ActionRow(SonoraIcons.Folder, stringResource(R.string.converter_output_choose), onChoose)
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(SonoraIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.converter_output_location, folder.displayName),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            InsetDivider(start = 52.dp)
            ActionRow(SonoraIcons.Refresh, stringResource(R.string.converter_output_change), onChoose)
        }
    }
    GroupFooter(
        if (folder == null) {
            stringResource(R.string.converter_output_none_footer)
        } else {
            stringResource(R.string.converter_output_footer, folder.displayName)
        },
    )
}

/**
 * The sources of the next batch, with "Add Music…" (the library, the main way in) and, below as secondary options, the
 * system pickers for files and folders on the device.
 */
@Composable
private fun SourcesSection(sources: List<PendingSource>, output: OutputSelection<OutputTarget>, actions: ConverterMainActions) {
    GroupHeader(stringResource(R.string.converter_sources_header))
    GroupedCard {
        for (source in sources) {
            SourceRow(
                source,
                target = output.resolve(source.pick.uri),
                manual = output.isManual(source.pick.uri),
                ownFormats = output.manualChildCount(source.pick.uri),
                onOpen = { actions.onOpenSource(source) },
                onRemove = { actions.onRemoveSource(source) },
            )
            InsetDivider(start = 52.dp)
        }
        ActionRow(SonoraIcons.Add, stringResource(R.string.converter_add_music), actions.onAddMusic)
    }
    GroupFooter(stringResource(R.string.converter_sources_footer))
    GroupedCard(Modifier.padding(top = 16.dp)) {
        ActionRow(SonoraIcons.AudioFile, stringResource(R.string.converter_browse_files), actions.onBrowseFiles)
        InsetDivider(start = 52.dp)
        ActionRow(SonoraIcons.Folder, stringResource(R.string.converter_browse_folder), actions.onBrowseFolder)
    }
    GroupFooter(stringResource(R.string.converter_browse_footer))
}

/**
 * A picked folder (one row for everything inside), file or library song, with what it converts to (marked when set
 * by hand), for a folder how many of its songs have their own format ([ownFormats]), and its remove button. Tapping it
 * opens its output: the picker, or for a folder its format and songs.
 */
@Composable
private fun SourceRow(
    source: PendingSource,
    target: OutputTarget,
    manual: Boolean,
    ownFormats: Int,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val (icon, kind) = when (source.pick) {
        is PickedSource.Folder -> SonoraIcons.Folder to R.string.converter_source_folder
        is PickedSource.File -> SonoraIcons.AudioFile to R.string.converter_source_file
        is PickedSource.Track -> SonoraIcons.MusicNote to R.string.converter_source_song
    }
    val openLabel = stringResource(if (source.isFolder) R.string.converter_folder_open else R.string.converter_format_change)
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(source.displayName, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                source.detail ?: stringResource(kind),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            OutputLine(target, manual)
            if (ownFormats > 0) {
                Text(
                    pluralStringResource(R.plurals.converter_folder_own_formats, ownFormats, ownFormats),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                SonoraIcons.RemoveCircle,
                contentDescription = stringResource(R.string.converter_remove_source, source.displayName),
                tint = colors.error,
            )
        }
    }
}

@Composable
private fun PerformanceSection(mode: PerformanceMode, onSelect: (PerformanceMode) -> Unit) {
    GroupHeader(stringResource(R.string.converter_performance_header))
    GroupedCard {
        SegmentedControl(
            options = PerformanceMode.entries,
            selected = mode,
            label = { modeLabel(it) },
            onSelect = onSelect,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp),
        )
        Text(
            modeDescription(mode),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
    GroupFooter(stringResource(R.string.converter_performance_footer))
}

private val previewBatch = ConversionBatch(
    id = 1,
    profileId = ConversionProfiles.XDJ_700.id,
    performanceMode = PerformanceMode.NORMAL,
    createdAtMs = 0,
    outputFolderName = "2026-10-03 14.05",
    outputFolderUri = null,
    overwriteOriginals = false,
    status = BatchStatus.RUNNING,
)

private val previewState = ConverterUiState(
    outputFolder = OutputRootFolder("content://tree/music", "Music"),
    sources = listOf(
        PendingSource(PickedSource.Folder("content://tree/dj"), "DJ Sets"),
        PendingSource(PickedSource.File("content://doc/1"), "Intro.mp3"),
        PendingSource(PickedSource.Track("content://tree/music/document/a"), "Midnight City", detail = "M83"),
    ),
    activeBatch = BatchSnapshot(previewBatch, emptyList(), BatchProgress(3, 12, "Track 04.flac", 0.3f)),
)

@Preview(name = "Converter, dark", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ConverterMainDarkPreview() {
    SonoraTheme(darkTheme = true) { ConverterMainContent(previewState, ConverterMainActions()) }
}

@Preview(name = "Converter, light, empty", showBackground = true)
@Composable
private fun ConverterMainLightPreview() {
    SonoraTheme(darkTheme = false) { ConverterMainContent(ConverterUiState(), ConverterMainActions()) }
}
