package io.sonora.player.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Text
import io.sonora.player.wear.R

/**
 * Asks to connect Bluetooth headphones: the watch's speaker is never used, so playback waits for an output. The button
 * opens the watch's Bluetooth settings; a swipe right dismisses the prompt.
 */
@Composable
fun HeadphonesPrompt(
    visible: Boolean,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        edgeButton = { EdgeButton(onClick = onOpenSettings) { Text(stringResource(R.string.wear_headphones_action)) } },
        title = { Text(stringResource(R.string.wear_headphones_title)) },
        text = { Text(stringResource(R.string.wear_headphones_body)) },
    )
}
