package io.sonora.feature.converter.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.sonora.core.designsystem.component.TranslucentAlert
import io.sonora.core.designsystem.component.TranslucentAlertButton
import io.sonora.core.designsystem.component.TranslucentAlertText
import io.sonora.feature.converter.ConverterNotice
import io.sonora.feature.converter.R
import io.sonora.feature.converter.ReplacePrompt

/**
 * The irreversible-replacement warning, before a batch (overwrite switch) and after it: how many lossless originals a
 * lossy file would replace (after it), no trash, lossy sources grow in a lossless format, originals go only after their
 * converted file was checked.
 */
@Composable
internal fun ReplaceOriginalsDialog(prompt: ReplacePrompt, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val after = prompt as? ReplacePrompt.AfterBatch
    TranslucentAlert(
        title = stringResource(if (after != null) R.string.converter_replace_after_title else R.string.converter_replace_before_title),
        onDismiss = onDismiss,
        confirm = TranslucentAlertButton(
            stringResource(if (after != null) R.string.converter_replace_confirm_after else R.string.converter_replace_confirm_before),
            destructive = true,
            onClick = onConfirm,
        ),
        cancel = TranslucentAlertButton(stringResource(R.string.converter_cancel), onClick = onDismiss),
    ) {
        after?.let { TranslucentAlertText(pluralStringResource(R.plurals.converter_replace_count, it.count, it.count)) }
        after?.lossyCount?.takeIf { it > 0 }?.let {
            TranslucentAlertText(pluralStringResource(R.plurals.converter_lossy_count, it, it), bold = true)
        }
        TranslucentAlertText(stringResource(R.string.converter_replace_irreversible))
        TranslucentAlertText(stringResource(R.string.converter_replace_growth))
        TranslucentAlertText(stringResource(R.string.converter_replace_verified))
    }
}

/**
 * Before a batch that overwrites originals: [count] songs would replace a lossless original with a lossy file, which
 * cannot be undone. Confirming starts the batch; cancelling goes back to its setup.
 */
@Composable
internal fun LossyOverwriteDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    TranslucentAlert(
        title = stringResource(R.string.converter_lossy_title),
        onDismiss = onDismiss,
        confirm = TranslucentAlertButton(stringResource(R.string.converter_lossy_confirm), destructive = true, onClick = onConfirm),
        cancel = TranslucentAlertButton(stringResource(R.string.converter_cancel), onClick = onDismiss),
    ) {
        TranslucentAlertText(pluralStringResource(R.plurals.converter_lossy_count, count, count), bold = true)
        TranslucentAlertText(stringResource(R.string.converter_lossy_message))
    }
}

@Composable
internal fun DeleteFromHistoryDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    TranslucentAlert(
        title = stringResource(R.string.converter_delete_title),
        message = stringResource(R.string.converter_delete_message),
        onDismiss = onDismiss,
        confirm = TranslucentAlertButton(stringResource(R.string.converter_delete_confirm), destructive = true, onClick = onConfirm),
        cancel = TranslucentAlertButton(stringResource(R.string.converter_cancel), onClick = onDismiss),
    )
}

@Composable
internal fun NoticeDialog(notice: ConverterNotice, onDismiss: () -> Unit) {
    val (title, message) = noticeText(notice)
    TranslucentAlert(
        title = stringResource(title),
        message = stringResource(message),
        onDismiss = onDismiss,
        confirm = TranslucentAlertButton(stringResource(R.string.converter_ok), onClick = onDismiss),
    )
}
