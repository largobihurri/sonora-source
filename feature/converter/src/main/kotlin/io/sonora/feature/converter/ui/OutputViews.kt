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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.ActionRow
import io.sonora.core.designsystem.component.BackHeader
import io.sonora.core.designsystem.component.BottomChromePadding
import io.sonora.core.designsystem.component.CheckRow
import io.sonora.core.designsystem.component.GroupFooter
import io.sonora.core.designsystem.component.GroupHeader
import io.sonora.core.designsystem.component.GroupedCard
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.LargeTitle
import io.sonora.core.designsystem.component.ScrollToTopEffect
import io.sonora.core.designsystem.component.SegmentedControl
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.layout.ContentWidthLazyColumn
import io.sonora.core.designsystem.theme.SonoraTheme
import io.sonora.core.domain.conversion.Mp3Mode
import io.sonora.core.domain.conversion.OutputSelection
import io.sonora.core.domain.conversion.OutputTarget
import io.sonora.core.domain.model.AudioFormat
import io.sonora.core.domain.model.BatchSource
import io.sonora.feature.converter.FolderContents
import io.sonora.feature.converter.FormatChoice
import io.sonora.feature.converter.OutputChoices
import io.sonora.feature.converter.R

/** What an entry's output follows until it is set by hand: the batch-wide target or its folder's. */
internal enum class InheritedFrom { BATCH, FOLDER }

/**
 * The output picker: a format, then that format's settings. For the batch-wide target [inherited] is `null`; for a
 * source or a song inside a folder it names what the entry follows, shows whether it was [manual] (set by hand) and,
 * when it was, offers to follow [inheritedTarget] again ([onUseInherited]). Any pick sets the entry by hand.
 */
@Composable
internal fun OutputFormatContent(
    title: String,
    backLabel: String,
    target: OutputTarget,
    onSelect: (OutputTarget) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    inherited: InheritedFrom? = null,
    inheritedTarget: OutputTarget? = null,
    manual: Boolean = false,
    onUseInherited: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    ScrollToTopEffect(listState)
    ContentWidthLazyColumn(modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = BottomChromePadding)) {
        item {
            BackHeader(backLabel, onBack)
            LargeTitle(title)
        }
        if (inherited != null && inheritedTarget != null) {
            item { InheritanceSection(inherited, inheritedTarget, manual, onUseInherited) }
        }
        item {
            GroupHeader(stringResource(R.string.converter_format_choice_header))
            GroupedCard(Modifier.selectableGroup()) {
                FormatChoice.entries.forEachIndexed { index, choice ->
                    if (index > 0) InsetDivider(start = 16.dp)
                    CheckRow(
                        formatChoiceName(choice),
                        selected = FormatChoice.of(target) == choice,
                        onSelect = { onSelect(choice.pick(target)) },
                        subtitle = targetSummary(choice.pick(target)),
                    )
                }
            }
            if (inherited == null) GroupFooter(stringResource(R.string.converter_format_batch_footer))
        }
        item { FormatSettings(target, onSelect) }
    }
}

@Composable
private fun InheritanceSection(inherited: InheritedFrom, inheritedTarget: OutputTarget, manual: Boolean, onUseInherited: () -> Unit) {
    val folder = inherited == InheritedFrom.FOLDER
    if (manual) {
        GroupedCard(Modifier.padding(top = 8.dp)) {
            ActionRow(
                SonoraIcons.Refresh,
                stringResource(if (folder) R.string.converter_format_use_folder else R.string.converter_format_use_batch),
                onUseInherited,
            )
        }
    }
    val footer = when {
        manual && folder -> stringResource(R.string.converter_format_manual_folder)
        manual -> stringResource(R.string.converter_format_manual_batch)
        folder -> stringResource(R.string.converter_format_follows_folder, targetLabel(inheritedTarget))
        else -> stringResource(R.string.converter_format_follows_batch, targetLabel(inheritedTarget))
    }
    GroupFooter(footer, Modifier.padding(top = if (manual) 0.dp else 8.dp))
}

/** The selected format's settings: bit depth and sample rate, bitrate, or MP3's mode; the XDJ-700 has none. */
@Composable
private fun FormatSettings(target: OutputTarget, onSelect: (OutputTarget) -> Unit) {
    if (target == OutputTarget.Xdj700) {
        GroupFooter(stringResource(R.string.converter_format_xdj_footer), Modifier.padding(top = 8.dp))
        return
    }
    GroupHeader(stringResource(R.string.converter_format_settings_header))
    GroupedCard {
        when (target) {
            is OutputTarget.Lossless -> {
                SettingRow(stringResource(R.string.converter_format_bit_depth), OutputTarget.Lossless.BIT_DEPTHS, target.bitDepth, {
                    stringResource(R.string.converter_format_bits, it)
                }) { onSelect(target.copy(bitDepth = it)) }
                InsetDivider(start = 16.dp)
                SettingRow(
                    stringResource(R.string.converter_format_sample_rate),
                    listOf<Int?>(null) + OutputTarget.Lossless.SAMPLE_RATE_CAPS_HZ,
                    target.sampleRateCapHz,
                    { cap ->
                        if (cap == null) {
                            stringResource(R.string.converter_format_rate_keep)
                        } else {
                            stringResource(R.string.converter_format_rate_khz, kilohertz(cap))
                        }
                    },
                ) { onSelect(target.copy(sampleRateCapHz = it)) }
            }
            is OutputTarget.Aac -> BitrateRow(OutputTarget.Aac.BITRATES_KBPS, target.bitrateKbps) { onSelect(target.copy(bitrateKbps = it)) }
            is OutputTarget.Opus -> BitrateRow(OutputTarget.Opus.BITRATES_KBPS, target.bitrateKbps) { onSelect(target.copy(bitrateKbps = it)) }
            is OutputTarget.Mp3 -> Mp3Settings(target, onSelect)
            OutputTarget.Xdj700 -> Unit
        }
    }
    when (target) {
        is OutputTarget.Lossless -> GroupFooter(stringResource(R.string.converter_format_rate_footer))
        is OutputTarget.Mp3 -> if (target.mode is Mp3Mode.Vbr) GroupFooter(stringResource(R.string.converter_format_vbr_footer))
        else -> Unit
    }
}

@Composable
private fun Mp3Settings(target: OutputTarget.Mp3, onSelect: (OutputTarget) -> Unit) {
    val vbr = target.mode is Mp3Mode.Vbr
    SettingRow(stringResource(R.string.converter_format_mp3_mode), listOf(false, true), vbr, {
        stringResource(if (it) R.string.converter_format_mp3_vbr else R.string.converter_format_mp3_cbr)
    }) { onSelect(OutputChoices.withMp3Vbr(target, it)) }
    InsetDivider(start = 16.dp)
    when (val mode = target.mode) {
        is Mp3Mode.Cbr -> BitrateRow(OutputTarget.Mp3.CBR_BITRATES_KBPS, mode.bitrateKbps) { onSelect(OutputTarget.Mp3(Mp3Mode.Cbr(it))) }
        is Mp3Mode.Vbr -> SettingRow(stringResource(R.string.converter_format_vbr_quality), OutputTarget.Mp3.VBR_QUALITIES, mode.quality, {
            "V$it"
        }) { onSelect(OutputTarget.Mp3(Mp3Mode.Vbr(it))) }
    }
}

@Composable
private fun BitrateRow(options: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    SettingRow(stringResource(R.string.converter_format_bitrate), options, selected, { stringResource(R.string.converter_format_kbps, it) }, onSelect)
}

/** A setting's name above its choices. */
@Composable
private fun <T> SettingRow(label: String, options: List<T>, selected: T, optionLabel: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        SegmentedControl(
            options = options,
            selected = selected,
            label = optionLabel,
            onSelect = onSelect,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
            singleLineLabels = true,
        )
    }
}

/**
 * A row naming what an entry converts to: [title] with its output ([target]) below, the "Custom" mark when it was set
 * by hand, and a chevron into the picker. With [summaryOnly] the line gives only what the target writes, for a
 * [title] that already names the format.
 */
@Composable
internal fun OutputValueRow(
    title: String,
    target: OutputTarget,
    manual: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClickLabel: String = stringResource(R.string.converter_format_change),
    summaryOnly: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            OutputLine(target, manual, summaryOnly = summaryOnly)
        }
        Icon(SonoraIcons.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
    }
}

/** What an entry converts to, with the "Custom" mark when it was set by hand. */
@Composable
internal fun OutputLine(target: OutputTarget, manual: Boolean, modifier: Modifier = Modifier, summaryOnly: Boolean = false) {
    Row(modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (summaryOnly) targetSummary(target) else targetLabel(target),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (manual) {
            CapsuleBadge(
                stringResource(R.string.converter_format_badge),
                stringResource(R.string.converter_format_badge_description),
                Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * A picked folder: its own format (by default the batch-wide one), then its songs, each with what it converts to; a
 * song opens the picker to be set on its own ([onOpenSong]).
 */
@Composable
internal fun FolderContent(
    name: String,
    folderUri: String,
    output: OutputSelection<OutputTarget>,
    contents: FolderContents?,
    onBack: () -> Unit,
    onOpenFolderFormat: () -> Unit,
    onOpenSong: (BatchSource) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    ScrollToTopEffect(listState)
    ContentWidthLazyColumn(modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = BottomChromePadding)) {
        item {
            BackHeader(stringResource(R.string.converter_title), onBack)
            LargeTitle(name)
        }
        item {
            GroupHeader(stringResource(R.string.converter_folder_format_header))
            GroupedCard {
                OutputValueRow(
                    stringResource(R.string.converter_format_choice_header),
                    output.resolve(folderUri),
                    output.isManual(folderUri),
                    onOpenFolderFormat,
                )
            }
            GroupFooter(stringResource(R.string.converter_folder_format_footer))
        }
        item {
            GroupHeader(stringResource(R.string.converter_folder_songs_header))
            GroupedCard {
                when (contents) {
                    null, FolderContents.Loading -> FolderMessage(stringResource(R.string.converter_folder_loading))
                    FolderContents.Failed -> {
                        FolderMessage(stringResource(R.string.converter_folder_failed), error = true)
                        InsetDivider(start = 52.dp)
                        ActionRow(SonoraIcons.Refresh, stringResource(R.string.converter_folder_retry), onRetry)
                    }
                    is FolderContents.Listed -> {
                        if (contents.files.isEmpty()) FolderMessage(stringResource(R.string.converter_folder_empty))
                        contents.files.forEachIndexed { index, file ->
                            if (index > 0) InsetDivider(start = 16.dp)
                            OutputValueRow(
                                file.displayName,
                                output.resolve(folderUri, file.uri),
                                output.isManual(folderUri, file.uri),
                                onClick = { onOpenSong(file) },
                            )
                        }
                    }
                }
            }
            (contents as? FolderContents.Listed)?.skippedFolders?.takeIf { it > 0 }?.let { skipped ->
                GroupFooter(pluralStringResource(R.plurals.converter_skipped_folders, skipped, skipped))
            }
        }
    }
}

@Composable
private fun FolderMessage(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Preview(name = "Output picker, dark", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun OutputFormatPreview() {
    SonoraTheme(darkTheme = true) {
        OutputFormatContent(
            title = "Midnight City",
            backLabel = "Converter",
            target = OutputTarget.Lossless(AudioFormat.FLAC, 24, 48_000),
            onSelect = {},
            onBack = {},
            inherited = InheritedFrom.BATCH,
            inheritedTarget = OutputTarget.Xdj700,
            manual = true,
        )
    }
}

@Preview(name = "Folder, light", showBackground = true)
@Composable
private fun FolderPreview() {
    val folder = "content://tree/dj"
    val files = listOf(BatchSource("content://doc/a", "Peak Time.flac", folder), BatchSource("content://doc/b", "Intro.wav", folder))
    val output = OutputSelection<OutputTarget>(OutputTarget.Xdj700).withChild(folder, "content://doc/b", OutputTarget.Mp3())
    SonoraTheme(darkTheme = false) {
        FolderContent("DJ Sets", folder, output, FolderContents.Listed(files, 1), {}, {}, {}, {})
    }
}
