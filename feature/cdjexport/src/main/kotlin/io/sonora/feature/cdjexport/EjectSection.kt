package io.sonora.feature.cdjexport

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.PillButton
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.text.rememberStrings
import io.sonora.feature.cdjexport.export.EjectState

/** Where "Eject stick" shows, if anywhere. */
internal enum class EjectPlace { NONE, FORMAT, EXPORT }

/**
 * Where "Eject stick" shows: under a finished export's result, else under a successful format; nowhere while an export
 * or a format is still writing to a stick (ejecting then would cut it short, and a stick Android does not list while
 * Sonora formats it would read as safe to remove).
 */
internal fun ejectPlace(prepare: PrepareStep, export: ExportStep): EjectPlace = when {
    export is ExportStep.Running || prepare is PrepareStep.Formatting -> EjectPlace.NONE
    export is ExportStep.Done -> EjectPlace.EXPORT
    prepare is PrepareStep.Finished && prepare.outcome is FormatOutcome.Formatted -> EjectPlace.FORMAT
    else -> EjectPlace.NONE
}

/** "Eject stick" where an export or a format has finished: its [state] and actions. */
internal class EjectControls(
    val state: EjectState,
    val onEject: () -> Unit,
    val onDismiss: () -> Unit,
)

/** The eject button, then where the eject is, until Android says the stick can be unplugged. */
@Composable
internal fun Eject(controls: EjectControls) {
    val state = controls.state
    val message = ejectMessage(state, rememberStrings())
    if (state is EjectState.SafeToRemove) {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(SonoraIcons.Checkmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.cdj_eject_safe_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
        }
        PillButton(stringResource(R.string.cdj_done), icon = null, onClick = controls.onDismiss)
        return
    }
    message?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    PillButton(
        stringResource(if (state is EjectState.Waiting) R.string.cdj_eject_again else R.string.cdj_eject),
        icon = null,
        onClick = controls.onEject,
        enabled = state !is EjectState.OpenScreen,
    )
}
