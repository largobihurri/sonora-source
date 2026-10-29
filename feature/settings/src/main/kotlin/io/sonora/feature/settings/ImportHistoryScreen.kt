package io.sonora.feature.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.BackHeader
import io.sonora.core.designsystem.component.BottomChromePadding
import io.sonora.core.designsystem.component.TranslucentAlert
import io.sonora.core.designsystem.component.TranslucentAlertButton
import io.sonora.core.designsystem.component.TranslucentPanel
import io.sonora.core.designsystem.component.TranslucentPanelDivider
import io.sonora.core.designsystem.component.TranslucentPanelGroup
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.LargeTitle
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.text.Strings
import io.sonora.core.designsystem.text.rememberStrings
import io.sonora.core.designsystem.layout.ContentWidthLazyColumn
import io.sonora.core.domain.archive.ArchiveImportEntry
import java.time.ZoneId

/**
 * Settings › Library Folders › Import History: every archive imported, newest first (Back returns to Settings). An
 * entry opens as a floating translucent panel with Delete from Phone, Remove from History and its songs; deleting asks
 * first, and a read-only folder is picked again to allow it, as the library's "Delete from Phone" does.
 */
@Composable
internal fun ImportHistoryScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: ImportHistoryViewModel = hiltViewModel()) {
    val imports by viewModel.imports.collectAsStateWithLifecycle()
    val opened by viewModel.opened.collectAsStateWithLifecycle()
    val deletion by viewModel.deletion.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val strings = rememberStrings()
    val zone = ZoneId.systemDefault()
    BackHandler(onBack = onBack)
    ContentWidthLazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = BottomChromePadding)) {
        item(key = "header") {
            BackHeader(stringResource(R.string.settings_title), onBack)
            LargeTitle(stringResource(R.string.settings_import_history))
            Text(
                stringResource(R.string.import_history_footer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (imports.isEmpty()) {
                Text(
                    stringResource(R.string.import_history_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 48.dp),
                )
            }
        }
        items(imports, key = { it.id }) { entry ->
            ImportRow(entry, ImportHistoryText.line(entry, zone, strings), onOpen = { viewModel.open(entry.id) })
        }
    }

    opened?.let { shown -> ImportPanel(shown, ImportHistoryText.line(shown.entry, zone, strings), viewModel) }
    DeletionDialogs(deletion, viewModel)
    notice?.let { NoticeDialog(it, strings, onDismiss = viewModel::dismissNotice) }
}

@Composable
private fun ImportRow(entry: ArchiveImportEntry, line: String, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.archiveName, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(line, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(SonoraIcons.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
        }
        InsetDivider(start = 20.dp)
    }
}

/** One import as a translucent panel: its name and date, the two ways to delete it, and every song it brought. */
@Composable
private fun ImportPanel(shown: OpenedImport, line: String, viewModel: ImportHistoryViewModel) {
    TranslucentPanel(shown.entry.archiveName, onDismiss = viewModel::close) {
        Column {
            ImportPanelTitle(shown.entry.archiveName, line)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 16.dp)) {
                TranslucentPanelGroup(title = null, footer = stringResource(R.string.import_history_remove_note)) {
                    ImportPanelAction(
                        stringResource(R.string.import_history_delete_from_phone),
                        SonoraIcons.Trash,
                        onClick = viewModel::chooseDelete,
                        destructive = true,
                    )
                    TranslucentPanelDivider()
                    ImportPanelAction(stringResource(R.string.import_history_remove), SonoraIcons.Clear, onClick = viewModel::removeFromHistory)
                }
                ImportedSongsGroup(shown.songs)
            }
        }
    }
}

@Composable
private fun DeletionDialogs(deletion: HistoryDeletion?, viewModel: ImportHistoryViewModel) {
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.accessPicked(uri.toString()) else viewModel.cancelDelete()
    }
    when (deletion) {
        null, is HistoryDeletion.Deleting -> Unit
        is HistoryDeletion.Confirm -> TranslucentAlert(
            title = stringResource(R.string.import_history_delete_title, deletion.entry.archiveName),
            message = stringResource(R.string.import_history_delete_message),
            onDismiss = viewModel::cancelDelete,
            confirm = TranslucentAlertButton(stringResource(R.string.import_history_delete_confirm), destructive = true, onClick = viewModel::confirmDelete),
            cancel = TranslucentAlertButton(stringResource(R.string.import_history_cancel), onClick = viewModel::cancelDelete),
        )
        is HistoryDeletion.NeedsAccess -> {
            val message = if (deletion.wrongFolder) R.string.import_history_access_wrong_folder else R.string.import_history_access_message
            TranslucentAlert(
                title = stringResource(R.string.import_history_access_title),
                message = stringResource(message, deletion.request.folderName),
                onDismiss = viewModel::cancelDelete,
                confirm = TranslucentAlertButton(stringResource(R.string.import_history_access_choose)) {
                    try {
                        pickFolder.launch(deletion.request.pickerStartUri.toUri())
                    } catch (_: ActivityNotFoundException) {
                        viewModel.cancelDelete()
                    }
                },
                cancel = TranslucentAlertButton(stringResource(R.string.import_history_cancel), onClick = viewModel::cancelDelete),
            )
        }
    }
}

@Composable
private fun NoticeDialog(notice: HistoryNotice, strings: Strings, onDismiss: () -> Unit) {
    val text = ImportHistoryText.notice(notice, strings)
    TranslucentAlert(
        title = text.title,
        message = text.message,
        onDismiss = onDismiss,
        confirm = TranslucentAlertButton(stringResource(R.string.import_history_ok), onClick = onDismiss),
    )
}
