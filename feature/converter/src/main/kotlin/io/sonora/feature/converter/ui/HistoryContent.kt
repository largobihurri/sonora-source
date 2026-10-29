package io.sonora.feature.converter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.ActionRow
import io.sonora.core.designsystem.component.BackHeader
import io.sonora.core.designsystem.component.BottomChromePadding
import io.sonora.core.designsystem.component.GroupHeader
import io.sonora.core.designsystem.component.GroupedCard
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.LargeTitle
import io.sonora.core.designsystem.component.ScrollToTopEffect
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.theme.SonoraTheme
import io.sonora.core.designsystem.layout.ContentWidthLazyColumn
import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.core.domain.conversion.batch.HistorySection
import io.sonora.core.domain.model.BatchCounts
import io.sonora.core.domain.model.BatchItemState
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.ConversionBatch
import io.sonora.core.domain.model.ConversionBatchItem
import io.sonora.feature.converter.BatchSnapshot
import io.sonora.feature.converter.ConverterRules
import io.sonora.feature.converter.HistoryEntry
import io.sonora.feature.converter.HistorySectionUi
import io.sonora.feature.converter.R

/** Past batches in date sections (Today, Yesterday, Last 7 Days, Last 30 Days, Older); a row opens its detail. */
@Composable
internal fun HistoryContent(history: List<HistorySectionUi>, onBack: () -> Unit, onOpenBatch: (Long) -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    ScrollToTopEffect(listState)
    ContentWidthLazyColumn(modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = BottomChromePadding)) {
        item {
            BackHeader(stringResource(R.string.converter_title), onBack)
            LargeTitle(stringResource(R.string.converter_history))
        }
        if (history.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.converter_history_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                )
            }
        }
        for (group in history) {
            item(key = group.section.name) {
                GroupHeader(sectionTitle(group.section))
                GroupedCard {
                    group.entries.forEachIndexed { index, entry ->
                        if (index > 0) InsetDivider(start = 16.dp)
                        HistoryRow(entry, group.section, onClick = { onOpenBatch(entry.batch.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, section: HistorySection, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val batch = entry.batch
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(batchTime(batch.createdAtMs, section), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            Text(
                stringResource(R.string.converter_history_line, batchFormat(batch), countsLine(batch.counts)),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (batch.status != BatchStatus.COMPLETED) {
            Text(
                statusLabel(batch.status),
                style = MaterialTheme.typography.bodySmall,
                color = if (batch.status == BatchStatus.FAILED) colors.error else colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Icon(SonoraIcons.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
    }
}

/** A batch's format: "Several Formats" when its files convert to more than one, else the one they all share. */
@Composable
private fun batchFormat(batch: ConversionBatch): String =
    ConverterRules.batchProfileId(batch)?.let { profileLabel(it) } ?: stringResource(R.string.converter_formats_mixed)

/**
 * One batch: when and how it ran, its counts, live progress while it runs, "Play All" (its converted files) and
 * "Replace Originals" while outputs can still replace their sources, "Delete from History" once it ended, and every
 * file with its state; a converted file's row plays it.
 */
@Composable
internal fun BatchDetailContent(
    snapshot: BatchSnapshot,
    onBack: () -> Unit,
    onReplaceOriginals: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayAll: () -> Unit = {},
    onPlayItem: (ConversionBatchItem) -> Unit = {},
) {
    val batch = snapshot.batch
    val listState = rememberLazyListState()
    ScrollToTopEffect(listState)
    ContentWidthLazyColumn(modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = BottomChromePadding)) {
        item {
            BackHeader(stringResource(R.string.converter_history), onBack)
            Text(
                batchTime(batch.createdAtMs),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        item {
            GroupedCard(Modifier.padding(top = 8.dp)) {
                InfoRow(stringResource(R.string.converter_detail_profile), batchFormat(batch))
                InsetDivider(start = 16.dp)
                InfoRow(stringResource(R.string.converter_detail_mode), modeLabel(batch.performanceMode))
                InsetDivider(start = 16.dp)
                InfoRow(stringResource(R.string.converter_detail_folder), batch.outputFolderName)
                InsetDivider(start = 16.dp)
                InfoRow(stringResource(R.string.converter_detail_status), statusLabel(batch.status))
                batch.failureReason?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                    )
                }
                InsetDivider(start = 16.dp)
                if (snapshot.isRunning) {
                    BatchProgressBlock(snapshot)
                } else {
                    BatchCountsRow(batch.counts, Modifier.padding(vertical = 12.dp))
                    if (snapshot.replacedCount > 0) {
                        InsetDivider(start = 16.dp)
                        ReplacedCount(snapshot.replacedCount, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                }
            }
        }
        if (!snapshot.isRunning) {
            item {
                GroupedCard(Modifier.padding(top = 24.dp)) {
                    if (snapshot.playableCount > 0) {
                        ActionRow(SonoraIcons.Play, stringResource(R.string.converter_play_all), onPlayAll)
                        InsetDivider(start = 52.dp)
                    }
                    if (snapshot.replaceableCount > 0) {
                        ActionRow(SonoraIcons.Refresh, stringResource(R.string.converter_replace_originals), onReplaceOriginals)
                        InsetDivider(start = 52.dp)
                    }
                    ActionRow(SonoraIcons.Trash, stringResource(R.string.converter_detail_delete), onDelete, destructive = true)
                }
            }
        }
        item { GroupHeader(stringResource(R.string.converter_detail_files)) }
        item {
            GroupedCard {
                snapshot.items.forEachIndexed { index, item ->
                    if (index > 0) InsetDivider(start = 16.dp)
                    BatchItemRow(item, onPlay = if (ConverterRules.isPlayable(item)) ({ onPlayItem(item) }) else null)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, modifier = Modifier.padding(end = 16.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

private val previewBatch = ConversionBatch(
    id = 1,
    profileId = ConversionProfiles.XDJ_700.id,
    performanceMode = PerformanceMode.NORMAL,
    createdAtMs = 1_790_000_000_000,
    outputFolderName = "2026-10-03 14.05",
    outputFolderUri = null,
    overwriteOriginals = false,
    status = BatchStatus.COMPLETED,
    counts = BatchCounts(total = 3, done = 2, failed = 1, withoutTags = 1),
)

@Preview(name = "History, dark", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun HistoryPreview() {
    val sections = listOf(
        HistorySectionUi(HistorySection.TODAY, listOf(HistoryEntry(previewBatch, "Pioneer XDJ-700"))),
        HistorySectionUi(HistorySection.OLDER, listOf(HistoryEntry(previewBatch.copy(id = 2, status = BatchStatus.CANCELLED), "Pioneer XDJ-700"))),
    )
    SonoraTheme(darkTheme = true) { HistoryContent(sections, onBack = {}, onOpenBatch = {}) }
}

@Preview(name = "Batch detail, light", showBackground = true)
@Composable
private fun BatchDetailPreview() {
    val items = listOf(
        ConversionBatchItem(1, 1, 0, "u0", "Intro.mp3", "p", BatchItemState.DONE, tagsWritten = false, profileId = "xdj-700"),
        ConversionBatchItem(2, 1, 1, "u1", "Peak Time.flac", "p", BatchItemState.REPLACED_ORIGINAL, outputName = "Peak Time (2).aiff", tagsWritten = true, suffixedName = true, profileId = "xdj-700"),
        ConversionBatchItem(3, 1, 2, "u2", "Broken.wav", "p", BatchItemState.FAILED, failureReason = "The file is damaged", profileId = "xdj-700"),
    )
    SonoraTheme(darkTheme = false) {
        BatchDetailContent(BatchSnapshot(previewBatch, items, null), onBack = {}, onReplaceOriginals = {}, onDelete = {})
    }
}
