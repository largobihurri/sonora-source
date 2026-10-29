package io.sonora.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.ActionRow
import io.sonora.core.designsystem.component.FormSheet
import io.sonora.core.designsystem.component.TranslucentAlert
import io.sonora.core.designsystem.component.TranslucentAlertButton
import io.sonora.core.designsystem.component.TranslucentPanel
import io.sonora.core.designsystem.component.TranslucentPanelDivider
import io.sonora.core.designsystem.component.TranslucentPanelGroup
import io.sonora.core.designsystem.component.TranslucentPanelValueRow
import io.sonora.core.designsystem.component.GroupedSheet
import io.sonora.core.designsystem.component.InsetGroup
import io.sonora.core.designsystem.component.NavigationValueRow
import io.sonora.core.designsystem.component.PasswordFieldRow
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.text.LocaleFormats
import io.sonora.core.designsystem.text.rememberStrings
import io.sonora.core.domain.archive.ArchiveImportRules
import io.sonora.core.domain.archive.ArchiveProgress
import io.sonora.core.domain.library.LibraryFolderRules

/** The one import view model of the activity, shared by Settings (the picker) and the app shell (the sheets). */
const val IMPORT_ARCHIVE_VIEW_MODEL_KEY: String = "archive-import"

/** An archive another app asked Sonora to open: its [uri], and whether the sender allowed a persistable grant. */
data class ArchiveToImport(val uri: Uri, val persistable: Boolean)

/** The MIME types a ZIP archive goes by. */
internal val ZIP_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/x-zip")

private const val TAG = "SonoraImport"

/**
 * Starts importing [archive]: keeps a persistable read grant when one is allowed (given back when the import ends),
 * so the background job can still read it after Sonora leaves the screen. Its name is read off the main thread.
 */
fun ImportArchiveViewModel.open(context: Context, archive: ArchiveToImport) {
    val kept = archive.persistable && try {
        context.contentResolver.takePersistableUriPermission(archive.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "No persistable grant on the archive", e)
        false
    }
    open(archive.uri.toString(), fileName = null, keptGrant = kept)
}

/**
 * Settings › Library Folders › Import Archive…: the system document picker for ZIP files; the returned function opens
 * it and returns false when no picker app exists.
 */
@Composable
internal fun rememberArchivePicker(viewModel: ImportArchiveViewModel): () -> Boolean {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.open(context, ArchiveToImport(uri, persistable = true))
    }
    return {
        try {
            launcher.launch(ZIP_TYPES)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}

/**
 * The sheets of an archive import, over the whole app: the archive with its password and destination, the progress
 * (closable while the job goes on), the system folder picker at the extracted folder when it is outside the library,
 * the result (a translucent panel, so the host sits inside the shell's context menu host), or what went wrong.
 */
@Composable
fun ArchiveImportHost(viewModel: ImportArchiveViewModel = hiltViewModel(key = IMPORT_ARCHIVE_VIEW_MODEL_KEY)) {
    // The library folder repository takes the persistable grant when it adds the folder.
    val pickLibraryFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.libraryFolderPicked(uri.toString()) else viewModel.libraryFolderDeclined()
    }
    val step by viewModel.step.collectAsStateWithLifecycle()
    when (val shown = step) {
        null -> Unit
        is ImportStep.ChoosingLibraryFolder -> if (!shown.pickerOpened) {
            LaunchedEffect(shown.folderUri) {
                viewModel.libraryFolderPickerOpened()
                try {
                    // Opens at the extracted folder (EXTRA_INITIAL_URI), so "Use this folder" allows just that one.
                    pickLibraryFolder.launch(shown.folderUri.toUri())
                } catch (_: ActivityNotFoundException) {
                    Log.w(TAG, "No folder picker")
                    viewModel.libraryFolderDeclined()
                }
            }
        }
        is ImportStep.Inspecting -> GroupedSheet(stringResource(R.string.import_title), onDismiss = viewModel::dismiss) {
            BusyRow(stringResource(R.string.import_opening))
        }
        is ImportStep.Ready -> ReadySheet(shown, viewModel)
        is ImportStep.Extracting -> if (!shown.hidden) {
            GroupedSheet(stringResource(R.string.import_working_title), onDismiss = viewModel::hide) {
                Column(Modifier.navigationBarsPadding().padding(bottom = 24.dp)) {
                    InsetGroup(header = null, footer = stringResource(R.string.import_working_note)) {
                        ProgressBlock(shown.name, shown.progress)
                    }
                    InsetGroup(header = null) {
                        ActionRow(SonoraIcons.Clear, stringResource(R.string.import_stop), onClick = viewModel::stop, destructive = true)
                    }
                }
            }
        }
        is ImportStep.Finishing -> if (!shown.hidden) {
            GroupedSheet(stringResource(R.string.import_working_title), onDismiss = viewModel::hide) {
                BusyRow(stringResource(R.string.import_finishing))
            }
        }
        is ImportStep.Done -> DonePanel(shown, onDone = viewModel::dismiss, onAddToLibrary = viewModel::addToLibrary)
        is ImportStep.Failed -> {
            val strings = rememberStrings()
            TranslucentAlert(
                title = stringResource(R.string.import_failed_title),
                message = ImportText.failure(shown.failure, strings),
                onDismiss = viewModel::dismiss,
                confirm = TranslucentAlertButton(stringResource(R.string.import_ok), onClick = viewModel::dismiss),
            )
        }
    }
}

/** The archive, its password when it has one, and where it goes; Import starts the job. */
@Composable
private fun ReadySheet(ready: ImportStep.Ready, viewModel: ImportArchiveViewModel) {
    val strings = rememberStrings()
    val context = LocalContext.current
    // In this sheet's memory only, never in saved state, until the job takes it.
    var password by remember(ready.archiveUri) { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Kept so the job can write there even if Sonora leaves the screen; given back once nothing uses it.
            val kept = try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                true
            } catch (e: SecurityException) {
                Log.w(TAG, "No persistable grant on the folder", e)
                false
            }
            viewModel.chooseDestination(uri.toString(), keptGrant = kept)
        }
    }
    val chooseFolder: () -> Unit = {
        try {
            pickFolder.launch((ready.destination?.treeUri ?: LibraryFolderRules.SUGGESTED_START_URI).toUri())
        } catch (_: ActivityNotFoundException) {
            Log.w(TAG, "No folder picker")
        }
    }
    val canImport = ready.canImport(passwordTyped = password.isNotEmpty())
    val confirm = { if (canImport) viewModel.importArchive(password.toCharArray().takeIf { it.isNotEmpty() }) }
    FormSheet(
        title = stringResource(R.string.import_title),
        cancelLabel = stringResource(R.string.import_cancel),
        confirmLabel = stringResource(R.string.import_confirm),
        confirmEnabled = canImport,
        busy = false,
        dismissible = password.isEmpty(),
        onCancel = viewModel::dismiss,
        onConfirm = confirm,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            val playlist = ArchiveImportRules.playlistName(ready.summary.manifest)
            InsetGroup(header = null, footer = playlist?.let { stringResource(R.string.import_playlist_note, it) }) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(ready.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(
                        ImportText.summary(ready.summary, strings),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (ready.summary.encrypted) {
                InsetGroup(
                    header = stringResource(R.string.import_password_header),
                    footer = ImportText.passwordFooter(ready.passwordError, strings),
                ) {
                    PasswordFieldRow(
                        value = password,
                        onValueChange = { password = it },
                        visible = visible,
                        onVisibleChange = { visible = it },
                        placeholder = stringResource(R.string.import_password_placeholder),
                        showLabel = stringResource(R.string.import_password_show),
                        hideLabel = stringResource(R.string.import_password_hide),
                        error = ready.passwordError != null && password.isEmpty(),
                        onDone = confirm,
                    )
                }
            }
            InsetGroup(
                header = stringResource(R.string.import_destination_header),
                footer = ImportText.destinationFooter(ready.coverage.takeIf { ready.destination != null }, strings),
            ) {
                val destination = ready.destination
                if (destination == null) {
                    ActionRow(SonoraIcons.Folder, stringResource(R.string.import_choose_folder), onClick = chooseFolder)
                } else {
                    NavigationValueRow(destination.name, onClick = chooseFolder, icon = SonoraIcons.Folder, value = stringResource(R.string.import_change))
                }
            }
        }
    }
}

/**
 * The result as a floating translucent panel over the blurred app: the archive's name, how many songs came, the notes
 * (playlist, songs not listed yet, files left out, where the music stands in the library), Add to Library while it is
 * outside it, Done, and every song the archive brought; the whole panel scrolls for a long archive. Tapping outside
 * and Back close it too.
 */
@Composable
private fun DonePanel(done: ImportStep.Done, onDone: () -> Unit, onAddToLibrary: () -> Unit) {
    val strings = rememberStrings()
    val title = stringResource(R.string.import_done_title)
    TranslucentPanel(title, onDismiss = onDone) {
        Column {
            ImportPanelTitle(title, done.name)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 16.dp)) {
                val notes = ImportText.resultNotes(done.outcome, done.skipped, strings, done.wrongFolder)
                TranslucentPanelGroup(title = null, footer = notes.joinToString("\n").ifEmpty { null }) {
                    TranslucentPanelValueRow(stringResource(R.string.import_done_songs), LocaleFormats.integer(done.fileCount, strings.locale))
                }
                TranslucentPanelGroup(title = null) {
                    if (done.addable != null) {
                        ImportPanelAction(stringResource(R.string.import_add_to_library), SonoraIcons.Folder, onAddToLibrary)
                        TranslucentPanelDivider()
                    }
                    ImportPanelAction(stringResource(R.string.import_done), SonoraIcons.Checkmark, onDone)
                }
                ImportedSongsGroup(done.songs)
            }
        }
    }
}

@Composable
private fun ProgressBlock(name: String, progress: ArchiveProgress?) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
        Spacer(Modifier.height(12.dp))
        if (progress == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (progress == null) {
                stringResource(R.string.import_preparing)
            } else {
                stringResource(R.string.import_progress, progress.entriesDone, progress.entriesTotal, (progress.fraction * 100).toInt())
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BusyRow(text: String) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 24.dp)) {
        InsetGroup(header = null) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(12.dp))
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
