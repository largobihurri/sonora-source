package io.sonora.feature.library.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.BannerState
import io.sonora.core.designsystem.component.TranslucentAlert
import io.sonora.core.designsystem.component.TranslucentAlertButton
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.share.shareAudio
import io.sonora.core.designsystem.text.rememberStrings
import io.sonora.feature.library.LibraryMenuViewModel
import io.sonora.feature.library.MenuEvent
import io.sonora.feature.library.R
import io.sonora.feature.library.RemoveStep

/**
 * What the context menu leaves behind: banners for its outcomes, the share sheet, and the Remove from Library dialogs
 * (Remove from Sonora / Delete from Phone / Cancel, then the irreversible confirmation and, for a read-only folder,
 * picking it again).
 */
@Composable
internal fun LibraryMenuEffects(viewModel: LibraryMenuViewModel, banner: BannerState) {
    val strings = rememberStrings()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is MenuEvent.Queued -> event.action.confirmationText(strings)?.let { banner.show(it, SonoraIcons.Checkmark) }
                is MenuEvent.Share -> if (!context.shareFiles(event, strings.get(R.string.menu_share_chooser))) {
                    banner.show(strings.get(R.string.notice_no_share_app), SonoraIcons.Info)
                }
                is MenuEvent.Hidden -> banner.show(strings.get(R.string.notice_hidden), SonoraIcons.Hidden)
                is MenuEvent.PlaylistDeleted -> banner.show(strings.get(R.string.notice_playlist_deleted, event.name), SonoraIcons.Trash)
                is MenuEvent.Deleted -> banner.show(strings.plural(R.plurals.notice_deleted, event.count, event.count), SonoraIcons.Trash)
                is MenuEvent.DeleteIncomplete -> banner.show(strings.get(R.string.notice_delete_incomplete, event.deleted, event.failed), SonoraIcons.Info)
                MenuEvent.StorageRefused -> banner.show(strings.get(R.string.notice_storage_refused), SonoraIcons.Info)
                is MenuEvent.ExportQueued -> banner.show(strings.get(R.string.notice_export_queued), SonoraIcons.Usb)
                is MenuEvent.WatchSendStarted -> banner.show(WatchNotice.started(event.name, strings), SonoraIcons.Watch)
                MenuEvent.WatchBusy -> banner.show(WatchNotice.busy(strings), SonoraIcons.Info)
                is MenuEvent.WatchSendEnded -> WatchNotice.ended(event.state, strings)?.let { banner.show(it, SonoraIcons.Watch) }
                MenuEvent.NoSongs -> banner.show(strings.get(R.string.notice_no_songs), SonoraIcons.Info)
                MenuEvent.Failed -> banner.show(strings.get(R.string.notice_failed), SonoraIcons.Info)
            }
        }
    }
    val step by viewModel.removeStep.collectAsStateWithLifecycle()
    RemoveDialogs(step, viewModel)
}

/** Opens the system share sheet for the files; false when no app can take them. */
private fun Context.shareFiles(share: MenuEvent.Share, title: String): Boolean =
    shareAudio(share.uris.map(Uri::parse), share.mimeTypes, share.title, title)

@Composable
private fun RemoveDialogs(step: RemoveStep?, viewModel: LibraryMenuViewModel) {
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.accessPicked(uri.toString()) else viewModel.cancelRemove()
    }
    when (step) {
        null, is RemoveStep.Deleting -> Unit
        is RemoveStep.Choose -> RemoveChoice(
            title = step.target.title,
            playlist = step.target is MenuTarget.PlaylistItem,
            onHide = { viewModel.hide(step.target.title, step.target.subtitle) },
            onDelete = viewModel::chooseDelete,
            onCancel = viewModel::cancelRemove,
        )
        is RemoveStep.ConfirmDelete -> TranslucentAlert(
            title = pluralStringResource(R.plurals.delete_title, step.tracks.size, step.tracks.size),
            message = stringResource(R.string.delete_message),
            onDismiss = viewModel::cancelRemove,
            confirm = TranslucentAlertButton(stringResource(R.string.delete_confirm), destructive = true, onClick = viewModel::confirmDelete),
            cancel = TranslucentAlertButton(stringResource(R.string.remove_cancel), onClick = viewModel::cancelRemove),
        )
        is RemoveStep.NeedsAccess -> {
            val message = if (step.wrongFolder) R.string.delete_access_wrong_folder else R.string.delete_access_message
            TranslucentAlert(
                title = stringResource(R.string.delete_access_title),
                message = stringResource(message, step.request.folderName),
                onDismiss = viewModel::cancelRemove,
                confirm = TranslucentAlertButton(stringResource(R.string.delete_access_choose)) {
                    try {
                        pickFolder.launch(step.request.pickerStartUri.toUri())
                    } catch (_: ActivityNotFoundException) {
                        viewModel.cancelRemove()
                    }
                },
                cancel = TranslucentAlertButton(stringResource(R.string.remove_cancel), onClick = viewModel::cancelRemove),
            )
        }
    }
}

/**
 * iOS-style alert with three stacked choices: Remove from Sonora, Delete from Phone (red) and Cancel. A [playlist]
 * offers Delete Playlist instead, which keeps its songs' files.
 */
@Composable
private fun RemoveChoice(title: String, playlist: Boolean, onHide: () -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
    TranslucentAlert(
        title = stringResource(R.string.remove_title, title),
        message = stringResource(if (playlist) R.string.remove_message_playlist else R.string.remove_message),
        onDismiss = onCancel,
        confirm = TranslucentAlertButton(stringResource(R.string.remove_from_sonora), onClick = onHide),
        extra = TranslucentAlertButton(
            stringResource(if (playlist) R.string.remove_delete_playlist else R.string.remove_delete_from_phone),
            destructive = true,
            onClick = onDelete,
        ),
        cancel = TranslucentAlertButton(stringResource(R.string.remove_cancel), onClick = onCancel),
    )
}
