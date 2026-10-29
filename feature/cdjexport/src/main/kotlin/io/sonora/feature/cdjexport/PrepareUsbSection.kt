package io.sonora.feature.cdjexport

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.TranslucentAlert
import io.sonora.core.designsystem.component.TranslucentAlertButton
import io.sonora.core.designsystem.component.TranslucentAlertText
import io.sonora.core.designsystem.component.TranslucentAlertTextField
import io.sonora.core.designsystem.component.PillButton
import io.sonora.core.designsystem.text.rememberStrings
import io.sonora.core.domain.storage.VolumeLabel
import io.sonora.core.designsystem.icon.SonoraIcons

/** Every user action of the "Prepare USB stick" section, so its composables stay stateless. */
internal class PrepareUsbActions(
    val onSelectDevice: (String) -> Unit,
    val onRetryPermission: () -> Unit,
    val onBack: () -> Unit,
    val onDismissNotice: () -> Unit,
    val onLabelChange: (String) -> Unit,
    val onRequestFormat: () -> Unit,
    val onConfirmationChange: (String) -> Unit,
    val onDismissConfirmation: () -> Unit,
    val onConfirmFormat: () -> Unit,
    val onCancelFormat: () -> Unit,
    /** Copies a technical report (plain text) to the clipboard. */
    val onCopyDetails: (String) -> Unit,
)

@Composable
internal fun PrepareUsbSection(state: PrepareUsbState, actions: PrepareUsbActions, eject: EjectControls? = null) {
    val strings = rememberStrings()
    SectionHeader(stringResource(R.string.cdj_prepare_header))
    Text(
        stringResource(R.string.cdj_prepare_intro),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.notice?.let { Notice(it.message(strings), state.noticeDetails, actions) }
        when (val step = state.step) {
            PrepareStep.ChooseDevice -> DeviceList(state.devices, actions.onSelectDevice)
            is PrepareStep.RequestingPermission ->
                Busy(stringResource(R.string.cdj_prepare_waiting_permission, step.device.displayName(strings)))
            is PrepareStep.PermissionDenied -> PermissionDenied(step, actions)
            is PrepareStep.Opening -> Busy(stringResource(R.string.cdj_prepare_opening, step.device.displayName(strings)))
            is PrepareStep.Ready -> ReadyToFormat(step, actions)
            is PrepareStep.Formatting -> Formatting(step, actions.onCancelFormat)
            is PrepareStep.Finished -> Finished(step, actions, eject)
        }
    }
}

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

/** Why the flow went back to the list; [details] (a technical report) adds a "Copy technical details" action. */
@Composable
private fun Notice(text: String, details: String?, actions: PrepareUsbActions) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(colors.errorContainer)
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = colors.onErrorContainer)
            TextButton(onClick = actions.onDismissNotice) { Text(stringResource(R.string.cdj_ok), color = colors.onErrorContainer) }
        }
        if (details != null) {
            TextButton(onClick = { actions.onCopyDetails(details) }) {
                Text(stringResource(R.string.cdj_copy_details), color = colors.onErrorContainer, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DeviceList(devices: List<DeviceItem>, onSelect: (String) -> Unit) {
    if (devices.isEmpty()) {
        Card {
            Text(stringResource(R.string.cdj_no_stick_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                stringResource(R.string.cdj_no_stick_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val strings = rememberStrings()
    devices.forEach { device ->
        val enabled = device.isSelectable
        TranslucentCard {
            // The row owns the tap and clips it to the translucent surface's shape.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PanelShape)
                    .clickable(enabled = enabled, role = Role.Button) { onSelect(device.id) }
                    .padding(PanelInset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(ExportTabIcon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f)
                    Text(device.displayName(strings), style = MaterialTheme.typography.titleMedium, color = textColor)
                    Text(device.detail(strings), style = MaterialTheme.typography.bodySmall, color = textColor)
                }
                if (enabled) Icon(SonoraIcons.ChevronRight, contentDescription = null, tint = tint)
            }
        }
    }
}

@Composable
internal fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun PermissionDenied(step: PrepareStep.PermissionDenied, actions: PrepareUsbActions) {
    Card {
        Text(stringResource(R.string.cdj_permission_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            stringResource(R.string.cdj_permission_message, step.device.displayName(rememberStrings())),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton(stringResource(R.string.cdj_try_again), icon = SonoraIcons.Refresh, onClick = actions.onRetryPermission)
        PillButton(stringResource(R.string.cdj_back), icon = null, onClick = actions.onBack)
    }
}

@Composable
private fun ReadyToFormat(step: PrepareStep.Ready, actions: PrepareUsbActions) {
    val strings = rememberStrings()
    val details = step.details
    Card {
        Text(details.displayName(strings), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        DetailRow(stringResource(R.string.cdj_detail_usb_device), details.usbName ?: stringResource(R.string.cdj_unknown))
        DetailRow(stringResource(R.string.cdj_detail_capacity), details.capacityText(strings))
        DetailRow(stringResource(R.string.cdj_detail_block_size), formatExactSize(details.blockSize.toLong(), strings))
        DetailRow(
            stringResource(R.string.cdj_detail_write_protection),
            stringResource(if (details.isWriteProtected) R.string.cdj_on else R.string.cdj_off),
        )
    }
    OutlinedTextField(
        value = step.label,
        onValueChange = actions.onLabelChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.cdj_label_field)) },
        singleLine = true,
        isError = step.labelError != null,
        supportingText = {
            Text(step.labelError?.message(strings) ?: stringResource(R.string.cdj_label_hint))
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
    )
    step.formatBlockedReason?.let { Text(it.message(strings), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton(stringResource(R.string.cdj_format_button), icon = null, onClick = actions.onRequestFormat, enabled = step.canFormat)
        PillButton(stringResource(R.string.cdj_back), icon = null, onClick = actions.onBack)
    }
    if (step.confirmation != null) EraseConfirmation(step, actions)
}

/** The only way to start a format: the user types [CONFIRMATION_WORD] to enable the destructive button. */
@Composable
private fun EraseConfirmation(step: PrepareStep.Ready, actions: PrepareUsbActions) {
    val strings = rememberStrings()
    TranslucentAlert(
        title = stringResource(R.string.cdj_erase_title),
        onDismiss = actions.onDismissConfirmation,
        confirm = TranslucentAlertButton(
            stringResource(R.string.cdj_erase_confirm),
            destructive = true,
            enabled = step.canConfirm,
            onClick = actions.onConfirmFormat,
        ),
        cancel = TranslucentAlertButton(stringResource(R.string.cdj_cancel), onClick = actions.onDismissConfirmation),
    ) {
        TranslucentAlertText(
            stringResource(
                R.string.cdj_erase_message,
                step.details.summary(strings),
                VolumeLabel.ofOrNull(step.label)?.text ?: step.label,
            ),
        )
        TranslucentAlertText(stringResource(R.string.cdj_erase_type_word, CONFIRMATION_WORD), bold = true)
        TranslucentAlertTextField(
            value = step.confirmation.orEmpty(),
            onValueChange = actions.onConfirmationChange,
            label = null,
            placeholder = CONFIRMATION_WORD,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        )
    }
}

@Composable
private fun Formatting(step: PrepareStep.Formatting, onCancel: () -> Unit) {
    val strings = rememberStrings()
    Card {
        Text(
            stringResource(R.string.cdj_formatting_title, step.details.summary(strings)),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        LinearProgressIndicator(progress = { step.fraction }, modifier = Modifier.fillMaxWidth())
        Text(
            formattingProgressText(step, strings),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.cdj_formatting_keep),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (step.backBlocked) {
        Text(
            stringResource(R.string.cdj_formatting_back_blocked),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    PillButton(
        stringResource(if (step.cancelling) R.string.cdj_cancelling else R.string.cdj_cancel),
        icon = null,
        onClick = onCancel,
        enabled = !step.cancelling,
    )
}

@Composable
private fun Finished(step: PrepareStep.Finished, actions: PrepareUsbActions, eject: EjectControls?) {
    val strings = rememberStrings()
    when (val outcome = step.outcome) {
        is FormatOutcome.Formatted -> Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(SonoraIcons.Checkmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.cdj_stick_ready), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            DetailRow(stringResource(R.string.cdj_detail_label), outcome.label)
            DetailRow(stringResource(R.string.cdj_detail_file_system), FILE_SYSTEM_NAME)
            DetailRow(stringResource(R.string.cdj_detail_size), formatCapacity(outcome.volumeBytes, strings.locale))
            Text(
                stringResource(R.string.cdj_replug_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is FormatOutcome.Failed -> Card {
            val text = outcome.text(strings)
            Text(text.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text(text.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton(stringResource(R.string.cdj_done), icon = null, onClick = actions.onBack)
        step.technicalDetails?.let { details ->
            PillButton(stringResource(R.string.cdj_copy_details), icon = null, onClick = { actions.onCopyDetails(details) })
        }
    }
    if (eject != null && step.outcome is FormatOutcome.Formatted) Eject(eject)
}

@Composable
internal fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.weight(0.4f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(0.6f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A grouped card: a [TranslucentCard] whose text defaults to the surface's own label colour. */
@Composable
internal fun Card(content: @Composable () -> Unit) {
    TranslucentCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(PanelInset),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

/** The corner of the notices that are not translucent (an error stays a plain tinted box). */
internal val CardShape = RoundedCornerShape(14.dp)

/** Material Symbols "usb" (Apache License 2.0), on the 24 dp grid the in-house icons use. */
internal fun usbIcon(): ImageVector = ImageVector.Builder(
    name = "Usb",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = addPathNodes(
        "M15,7v4h1v2h-3V5h2l-3,-4 -3,4h2v8H8v-2.07c0.7,-0.37 1.2,-1.08 1.2,-1.93 0,-1.21 -0.99,-2.2 -2.2,-2.2" +
            "s-2.2,0.99 -2.2,2.2c0,0.85 0.5,1.56 1.2,1.93V13c0,1.11 0.89,2 2,2h3v3.05c-0.71,0.37 -1.2,1.1 -1.2,1.95" +
            "c0,1.22 0.99,2.2 2.2,2.2s2.2,-0.98 2.2,-2.2c0,-0.85 -0.49,-1.58 -1.2,-1.95V15h3c1.11,0 2,-0.89 2,-2v-2" +
            "h1V7h-4z",
    ),
    fill = SolidColor(Color.Black),
).build()
