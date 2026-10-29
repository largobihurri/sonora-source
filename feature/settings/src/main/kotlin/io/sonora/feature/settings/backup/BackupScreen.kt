package io.sonora.feature.settings.backup

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.BackHeader
import io.sonora.core.designsystem.component.BottomChromePadding
import io.sonora.core.designsystem.component.TranslucentAlert
import io.sonora.core.designsystem.component.TranslucentAlertButton
import io.sonora.core.designsystem.component.TranslucentAlertText
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.LargeTitle
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.text.Strings
import io.sonora.core.designsystem.text.rememberStrings
import io.sonora.core.designsystem.layout.ContentWidthLazyColumn
import io.sonora.core.domain.backup.BackupNaming
import io.sonora.core.domain.backup.RestorePlan
import io.sonora.core.domain.backup.RestoreReport
import io.sonora.feature.settings.ActionRow
import io.sonora.feature.settings.R
import io.sonora.feature.settings.SectionFooter
import io.sonora.feature.settings.SettingsGroup
import java.time.LocalDate
import java.time.ZoneId

/** Types a picked backup may be listed under: providers often call a `.json` file plain text or octet-stream. */
private val BACKUP_TYPES = arrayOf(BackupNaming.MIME_TYPE, "text/*", "application/octet-stream")

/**
 * Settings › Backup & Restore: Back Up writes one file through the system picker; Restore reads one, previews what it
 * would add, merges it on confirmation and lists the songs it could not find. Back returns to Settings.
 */
@Composable
internal fun BackupScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastBackupMs by viewModel.lastBackupMs.collectAsStateWithLifecycle()
    val strings = rememberStrings()
    BackHandler(onBack = onBack)

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupNaming.MIME_TYPE)) { uri ->
        if (uri != null) viewModel.backUp(uri.toString())
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.read(uri.toString())
    }
    fun launching(launch: () -> Unit) {
        try {
            launch()
        } catch (_: ActivityNotFoundException) {
            viewModel.pickerMissing()
        }
    }

    ContentWidthLazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = BottomChromePadding)) {
        item(key = "header") {
            BackHeader(stringResource(R.string.settings_title), onBack)
            LargeTitle(stringResource(R.string.settings_backup))
        }
        item(key = "actions") {
            SettingsGroup {
                ActionRow(
                    SonoraIcons.SaveTo,
                    stringResource(R.string.backup_back_up),
                    onClick = {
                        val today = LocalDate.now()
                        launching { create.launch(BackupNaming.fileName(today.year, today.monthValue, today.dayOfMonth)) }
                    },
                    enabled = !state.busy,
                )
                InsetDivider(start = 52.dp)
                ActionRow(
                    SonoraIcons.History,
                    stringResource(R.string.backup_restore),
                    onClick = { launching { open.launch(BACKUP_TYPES) } },
                    enabled = !state.busy,
                )
            }
            if (state.busy) {
                Text(
                    stringResource(R.string.backup_working),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                )
                LinearProgressIndicator(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            } else {
                SectionFooter(BackupText.lastBackup(lastBackupMs, ZoneId.systemDefault(), strings))
            }
            SectionFooter(stringResource(R.string.backup_footer))
        }
    }

    state.preview?.let { plan -> PreviewDialog(plan, strings, onRestore = viewModel::confirmRestore, onCancel = viewModel::cancelRestore) }
    state.report?.let { report -> ReportDialog(report, strings, onDismiss = viewModel::dismiss) }
    state.message?.let { message ->
        // A one-line notice: its sentence is the alert's title, as on iOS.
        TranslucentAlert(
            title = BackupText.message(message, strings),
            onDismiss = viewModel::dismiss,
            confirm = TranslucentAlertButton(stringResource(R.string.backup_ok), onClick = viewModel::dismiss),
        )
    }
}

@Composable
private fun PreviewDialog(plan: RestorePlan, strings: Strings, onRestore: () -> Unit, onCancel: () -> Unit) {
    TranslucentAlert(
        title = stringResource(R.string.backup_preview_title),
        onDismiss = onCancel,
        confirm = TranslucentAlertButton(stringResource(R.string.backup_restore_confirm), onClick = onRestore),
        cancel = TranslucentAlertButton(stringResource(R.string.backup_cancel), onClick = onCancel),
    ) {
        TranslucentAlertText(BackupText.preview(plan.preview, strings), bold = true)
        TranslucentAlertText(stringResource(R.string.backup_preview_message))
    }
}

/** What the restore added, and every song it could not find; the alert scrolls when the list is long. */
@Composable
private fun ReportDialog(report: RestoreReport, strings: Strings, onDismiss: () -> Unit) {
    TranslucentAlert(
        title = stringResource(R.string.backup_restored_title),
        onDismiss = onDismiss,
        confirm = TranslucentAlertButton(stringResource(R.string.backup_ok), onClick = onDismiss),
    ) {
        TranslucentAlertText(BackupText.report(report, strings))
        for (song in report.unmatched) TranslucentAlertText(BackupText.song(song, strings))
    }
}
