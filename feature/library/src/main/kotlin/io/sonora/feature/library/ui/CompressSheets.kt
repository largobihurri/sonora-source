package io.sonora.feature.library.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.ActionRow
import io.sonora.core.designsystem.component.BannerState
import io.sonora.core.designsystem.component.FormSheet
import io.sonora.core.designsystem.component.TranslucentAlert
import io.sonora.core.designsystem.component.TranslucentAlertButton
import io.sonora.core.designsystem.component.GroupedSheet
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.InsetGroup
import io.sonora.core.designsystem.component.PasswordFieldRow
import io.sonora.core.designsystem.component.ValueRow
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.text.LocaleFormats
import io.sonora.core.designsystem.text.Strings
import io.sonora.core.designsystem.text.rememberStrings
import io.sonora.core.domain.archive.ArchiveFailure
import io.sonora.core.domain.archive.ArchiveProgress
import io.sonora.core.domain.archive.CompressedArchive
import io.sonora.core.domain.archive.PasswordGenerator
import io.sonora.core.domain.archive.PasswordStrength
import io.sonora.feature.library.CompressEvent
import io.sonora.feature.library.CompressStep
import io.sonora.feature.library.CompressViewModel
import io.sonora.feature.library.R

private const val ZIP_MIME = "application/zip"

/**
 * The sheets of "Compress": the password, the progress (which can be closed while the job goes on), the finished
 * archive with Share and Save, or what went wrong; [banner] confirms saving and reports targets without songs.
 */
@Composable
internal fun CompressHost(viewModel: CompressViewModel, banner: BannerState) {
    val strings = rememberStrings()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                CompressEvent.NoSongs -> banner.show(strings.get(R.string.compress_nothing), SonoraIcons.Info)
                CompressEvent.ReadFailed -> banner.show(strings.get(R.string.compress_read_failed), SonoraIcons.Info)
                CompressEvent.Saved -> banner.show(strings.get(R.string.compress_saved), SonoraIcons.Checkmark)
                CompressEvent.SaveFailed -> banner.show(strings.get(R.string.compress_save_failed), SonoraIcons.Info)
            }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ZIP_MIME)) { uri ->
        if (uri != null) viewModel.save(uri.toString())
    }
    val step by viewModel.step.collectAsStateWithLifecycle()
    when (val shown = step) {
        null -> Unit
        is CompressStep.Password -> PasswordSheet(
            name = shown.target.name,
            songCount = shown.tracks.size,
            onCancel = viewModel::dismiss,
            onCompress = viewModel::compress,
        )
        is CompressStep.Working -> if (!shown.hidden) WorkingSheet(shown.name, shown.progress, viewModel::hide, viewModel::stop)
        is CompressStep.Finished -> FinishedSheet(
            archive = shown.archive,
            onShare = {
                val shared = viewModel.shareUri()?.let { context.shareArchive(it, strings.get(R.string.menu_share_chooser)) } ?: false
                if (!shared) banner.show(strings.get(R.string.notice_no_share_app), SonoraIcons.Info)
            },
            onSave = {
                try {
                    save.launch(shown.archive.fileName)
                } catch (_: ActivityNotFoundException) {
                    banner.show(strings.get(R.string.compress_save_failed), SonoraIcons.Info)
                }
            },
            onDone = viewModel::dismiss,
        )
        is CompressStep.Failed -> TranslucentAlert(
            title = stringResource(R.string.compress_failed_title),
            message = shown.failure.compressMessage(strings),
            onDismiss = viewModel::dismiss,
            confirm = TranslucentAlertButton(stringResource(R.string.compress_ok), onClick = viewModel::dismiss),
        )
    }
}

/**
 * The optional password: typed or generated, shown or hidden, copied to send apart, with the strength rule's hint.
 * It lives in this sheet's memory only (never in saved state) until the job takes it.
 */
@Composable
private fun PasswordSheet(name: String, songCount: Int, onCancel: () -> Unit, onCompress: (CharArray?) -> Unit) {
    val strings = rememberStrings()
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val confirm = { onCompress(password.toCharArray().takeIf { it.isNotEmpty() }) }
    FormSheet(
        title = stringResource(R.string.compress_title),
        cancelLabel = stringResource(R.string.compress_cancel),
        confirmLabel = stringResource(R.string.compress_confirm),
        confirmEnabled = true,
        busy = false,
        dismissible = password.isEmpty(),
        onCancel = onCancel,
        onConfirm = confirm,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            InsetGroup(header = null) {
                ValueRow(name, songCount(songCount, strings))
            }
            InsetGroup(header = stringResource(R.string.compress_password_header), footer = stringResource(R.string.compress_hint_none)) {
                val hint = passwordHint(password)
                PasswordFieldRow(
                    value = password,
                    onValueChange = {
                        password = it
                        copied = false
                    },
                    visible = visible,
                    onVisibleChange = { visible = it },
                    placeholder = stringResource(R.string.compress_password_placeholder),
                    showLabel = stringResource(R.string.compress_password_show),
                    hideLabel = stringResource(R.string.compress_password_hide),
                    error = hint == PasswordHint.TOO_SHORT || hint == PasswordHint.TOO_FEW_KINDS,
                    onDone = confirm,
                )
                hintText(hint, strings)?.let { text ->
                    val weak = hint != PasswordHint.STRONG
                    Text(
                        text,
                        fontSize = 13.sp,
                        color = if (weak) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                    )
                }
                InsetDivider(start = 16.dp)
                ActionRow(SonoraIcons.Key, stringResource(R.string.compress_generate), onClick = {
                    val generated = PasswordGenerator.generate()
                    password = String(generated)
                    generated.fill('\u0000')
                    visible = true
                    copied = false
                })
                InsetDivider(start = 52.dp)
                ActionRow(
                    if (copied) SonoraIcons.Checkmark else SonoraIcons.Copy,
                    stringResource(if (copied) R.string.compress_copied else R.string.compress_copy),
                    enabled = password.isNotEmpty(),
                    onClick = {
                        context.copySecret(password, strings.get(R.string.compress_password_header))
                        copied = true
                    },
                )
            }
        }
    }
}

private fun hintText(hint: PasswordHint, strings: Strings): String? = when (hint) {
    PasswordHint.NONE -> null
    PasswordHint.STRONG -> strings.get(R.string.compress_hint_strong)
    PasswordHint.TOO_SHORT -> strings.plural(R.plurals.compress_hint_short, PasswordStrength.MIN_LENGTH, PasswordStrength.MIN_LENGTH)
    PasswordHint.TOO_FEW_KINDS -> strings.get(R.string.compress_hint_kinds, PasswordStrength.MIN_KINDS)
}

/** Copies a password, marked sensitive so Android 13+ hides it from the clipboard preview. */
private fun Context.copySecret(secret: String, label: String) {
    val clip = ClipData.newPlainText(label, secret)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

@Composable
private fun WorkingSheet(name: String, progress: ArchiveProgress?, onHide: () -> Unit, onStop: () -> Unit) {
    GroupedSheet(title = stringResource(R.string.compress_working_title), onDismiss = onHide) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 24.dp)) {
            InsetGroup(header = null, footer = stringResource(R.string.compress_working_note)) {
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
                        progressText(progress),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            InsetGroup(header = null) {
                ActionRow(SonoraIcons.Clear, stringResource(R.string.compress_stop), onClick = onStop, destructive = true)
            }
        }
    }
}

@Composable
private fun progressText(progress: ArchiveProgress?): String =
    if (progress == null) {
        stringResource(R.string.compress_preparing)
    } else {
        stringResource(R.string.compress_progress, progress.entriesDone, progress.entriesTotal, (progress.fraction * 100).toInt())
    }

@Composable
private fun FinishedSheet(archive: CompressedArchive, onShare: () -> Unit, onSave: () -> Unit, onDone: () -> Unit) {
    val strings = rememberStrings()
    GroupedSheet(title = stringResource(R.string.compress_ready_title), onDismiss = onDone) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            InsetGroup(header = null) {
                ValueRow(stringResource(R.string.compress_name), archive.fileName)
                InsetDivider(start = 16.dp)
                ValueRow(stringResource(R.string.compress_size), LocaleFormats.fileSize(archive.sizeBytes, strings))
                InsetDivider(start = 16.dp)
                ValueRow(stringResource(R.string.compress_songs), LocaleFormats.integer(archive.entryCount, strings.locale))
                InsetDivider(start = 16.dp)
                ValueRow(
                    stringResource(R.string.compress_protection),
                    stringResource(if (archive.encrypted) R.string.compress_protection_aes else R.string.compress_protection_none),
                )
            }
            InsetGroup(
                header = null,
                footer = stringResource(if (archive.encrypted) R.string.compress_password_note else R.string.compress_plain_note),
            ) {
                ActionRow(SonoraIcons.Share, stringResource(R.string.compress_share), onClick = onShare)
                InsetDivider(start = 52.dp)
                ActionRow(SonoraIcons.SaveTo, stringResource(R.string.compress_save), onClick = onSave)
            }
        }
    }
}

/** Opens the system share sheet for the archive at [uri] (the app's FileProvider); false when no app takes it. */
private fun Context.shareArchive(uri: String, title: String): Boolean {
    val content = uri.toUri()
    val send = Intent(Intent.ACTION_SEND)
        .setType(ZIP_MIME)
        .putExtra(Intent.EXTRA_STREAM, content)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The clip carries the read grant to whichever app is chosen.
    send.clipData = ClipData.newRawUri(null, content)
    return try {
        startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** The sentence a failed compression shows. */
internal fun ArchiveFailure.compressMessage(strings: Strings): String = when (this) {
    is ArchiveFailure.UnreadableSource -> strings.get(R.string.compress_error_unreadable, name)
    is ArchiveFailure.NotEnoughSpace -> strings.get(
        R.string.compress_error_no_space,
        LocaleFormats.fileSize(neededBytes, strings),
        LocaleFormats.fileSize(availableBytes, strings),
    )
    ArchiveFailure.PasswordNeededAgain -> strings.get(R.string.compress_error_password_again)
    ArchiveFailure.DestinationUnwritable -> strings.get(R.string.compress_error_unwritable)
    // Reading failures never come from compressing; anything else is unexpected.
    ArchiveFailure.PasswordRequired, ArchiveFailure.WrongPassword, ArchiveFailure.Damaged, ArchiveFailure.NoMusic,
    ArchiveFailure.Unexpected,
    -> strings.get(R.string.compress_error_unexpected)
}
