package io.sonora.feature.converter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.ActionRow
import io.sonora.core.designsystem.component.GroupedCard
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.NavigationValueRow
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.core.domain.model.BatchCounts
import io.sonora.core.domain.model.BatchItemState
import io.sonora.core.domain.model.BatchStatus
import io.sonora.core.domain.model.ConversionBatchItem
import io.sonora.feature.converter.BatchSnapshot
import io.sonora.feature.converter.R

/**
 * The batch shown above the converter form: live progress with Cancel while it converts (or replaces originals), then
 * its summary with the follow-up actions.
 */
@Composable
internal fun ActiveBatchCard(
    snapshot: BatchSnapshot,
    skippedFolders: Int,
    onCancel: () -> Unit,
    onReplaceOriginals: () -> Unit,
    onShowDetails: () -> Unit,
    onDismiss: () -> Unit,
    onPlayConverted: () -> Unit = {},
) {
    GroupedCard(Modifier.padding(top = 8.dp)) {
        if (skippedFolders > 0) SkippedFoldersNote(skippedFolders)
        if (snapshot.isRunning) {
            BatchProgressBlock(snapshot)
            if (snapshot.isConverting) {
                InsetDivider(start = 16.dp)
                ActionRow(SonoraIcons.Clear, stringResource(R.string.converter_batch_cancel), onCancel, destructive = true)
            }
        } else {
            BatchSummaryBlock(snapshot)
            // Replaced by the batch itself or afterwards, through "Replace Originals".
            if (snapshot.replacedCount > 0) {
                InsetDivider(start = 16.dp)
                ReplacedList(snapshot.replacedCount, snapshot.replacedItems)
            }
            if (snapshot.playableCount > 0) {
                InsetDivider(start = 16.dp)
                ActionRow(SonoraIcons.Play, stringResource(R.string.converter_play_converted), onPlayConverted)
            }
            if (snapshot.replaceableCount > 0) {
                InsetDivider(start = 16.dp)
                ActionRow(SonoraIcons.Refresh, stringResource(R.string.converter_replace_originals), onReplaceOriginals)
            }
            InsetDivider(start = 16.dp)
            NavigationValueRow(stringResource(R.string.converter_show_details), onShowDetails)
            InsetDivider(start = 16.dp)
            ActionRow(SonoraIcons.Checkmark, stringResource(R.string.converter_batch_done), onDismiss)
        }
    }
}

/** Title, progress bar, "3 of 12" and the file being converted. */
@Composable
internal fun BatchProgressBlock(snapshot: BatchSnapshot) {
    val colors = MaterialTheme.colorScheme
    val progress = snapshot.progress
    val title = stringResource(if (snapshot.isReplacing) R.string.converter_batch_replacing else R.string.converter_batch_converting)
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
        Spacer(Modifier.height(10.dp))
        if (progress != null) {
            LinearProgressIndicator(progress = { progress.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        val status = if (progress == null) {
            stringResource(R.string.converter_batch_preparing)
        } else {
            stringResource(R.string.converter_batch_progress, progress.finished, progress.total)
        }
        Text(status, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        progress?.currentFileName?.takeIf { progress.finished < progress.total }?.let { name ->
            Text(name, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BatchSummaryBlock(snapshot: BatchSnapshot) {
    val colors = MaterialTheme.colorScheme
    val batch = snapshot.batch
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(summaryTitle(batch.status), style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
        val detail = when (batch.status) {
            BatchStatus.FAILED -> batch.failureReason
            BatchStatus.TIMED_OUT -> stringResource(R.string.converter_batch_timed_out_detail)
            else -> null
        }
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
        Spacer(Modifier.height(12.dp))
        BatchCountsRow(batch.counts)
    }
}

/** Converted, failed, skipped and "without tags" side by side. */
@Composable
internal fun BatchCountsRow(counts: BatchCounts, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        CountColumn(counts.converted, stringResource(R.string.converter_count_converted), Modifier.weight(1f))
        CountColumn(counts.failed, stringResource(R.string.converter_count_failed), Modifier.weight(1f), alert = counts.failed > 0)
        CountColumn(counts.skipped, stringResource(R.string.converter_count_skipped), Modifier.weight(1f))
        CountColumn(counts.withoutTags, stringResource(R.string.converter_count_without_tags), Modifier.weight(1f))
    }
}

@Composable
private fun CountColumn(value: Int, label: String, modifier: Modifier, alert: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    // Read as one phrase ("2 Failed") rather than two stops.
    Column(modifier.clearAndSetSemantics { contentDescription = "$value $label" }, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.headlineSmall, color = if (alert) colors.error else colors.onSurface)
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "2 folders couldn't be read, so their files were left out." above the batch they were left out of. */
@Composable
private fun SkippedFoldersNote(count: Int) {
    Text(
        pluralStringResource(R.plurals.converter_skipped_folders, count, count),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
    )
}

/** "2 Originals Replaced", for a batch's summary and its detail. */
@Composable
internal fun ReplacedCount(count: Int, modifier: Modifier = Modifier) {
    Text(
        pluralStringResource(R.plurals.converter_replaced_count, count, count),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

@Composable
private fun ReplacedList(count: Int, items: List<ConversionBatchItem>) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        ReplacedCount(count)
        for (item in items) {
            Text(
                if (item.suffixedName) stringResource(R.string.converter_item_suffixed, item.outputName.orEmpty()) else item.sourceName,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * One file of a batch: name, state (and why it failed), its format, "No tags" badge, and where a replaced original went. With
 * [onPlay] (a converted file) the row plays it and shows a play glyph; other rows are not tappable.
 */
@Composable
internal fun BatchItemRow(item: ConversionBatchItem, onPlay: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    // Like the batch's "Without Tags" count: a file kept after a failed replacement is converted too.
    val converted = item.isConverted
    val tappable = if (onPlay != null) {
        Modifier.clickable(onClickLabel = stringResource(R.string.converter_item_play), role = Role.Button, onClick = onPlay)
    } else {
        Modifier
    }
    Column(Modifier.fillMaxWidth().then(tappable).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.sourceName,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (converted && !item.tagsWritten) NoTagsBadge(Modifier.padding(start = 8.dp))
            if (onPlay != null) {
                Icon(SonoraIcons.Play, contentDescription = null, tint = colors.primary, modifier = Modifier.padding(start = 8.dp).size(18.dp))
            }
        }
        val state = when {
            item.state == BatchItemState.REPLACED_ORIGINAL && item.suffixedName ->
                stringResource(R.string.converter_item_suffixed, item.outputName.orEmpty())
            else -> itemStateLabel(item.state)
        }
        Text(state, style = MaterialTheme.typography.bodySmall, color = if (item.state == BatchItemState.FAILED) colors.error else colors.onSurfaceVariant)
        // Each file converts to its own format; a batch can mix several.
        Text(profileLabel(item.profileId), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        item.failureReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
    }
}

/** Marks a file converted without tags (the source had none, or they could not be read). */
@Composable
internal fun NoTagsBadge(modifier: Modifier = Modifier) {
    CapsuleBadge(stringResource(R.string.converter_badge_no_tags), stringResource(R.string.converter_badge_no_tags_description), modifier)
}

/** A small capsule label, read out as [description]. */
@Composable
internal fun CapsuleBadge(text: String, description: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceVariant,
        modifier = modifier
            .clip(CapsuleShape)
            .background(colors.onSurface.copy(alpha = 0.08f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .semantics { contentDescription = description },
    )
}
