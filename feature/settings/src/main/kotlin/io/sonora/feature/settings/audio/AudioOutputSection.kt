package io.sonora.feature.settings.audio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.TranslucentPanel
import io.sonora.core.designsystem.component.TranslucentPanelDivider
import io.sonora.core.designsystem.component.TranslucentPanelGroup
import io.sonora.core.designsystem.component.TranslucentPanelValueRow
import io.sonora.core.designsystem.component.NavigationValueRow
import io.sonora.core.designsystem.text.Strings
import io.sonora.core.designsystem.text.rememberStrings
import io.sonora.core.domain.playback.output.AudioOutputDevice
import io.sonora.core.domain.playback.output.AudioOutputSnapshot
import io.sonora.core.domain.playback.output.MixerOption
import io.sonora.feature.settings.ImportPanelTitle
import io.sonora.feature.settings.R
import io.sonora.feature.settings.SectionFooter
import io.sonora.feature.settings.SectionHeader
import io.sonora.feature.settings.SettingsGroup
import io.sonora.feature.settings.SwitchRow

/**
 * Settings › Audio output: the device the sound goes to as a row; tapping it opens a translucent panel with what every
 * output device reports (rates, bit depths, channels) and, on Android 14+, the mixer formats it offers and whether
 * bit-perfect is among them. Shown once the first read arrives.
 */
@Composable
internal fun AudioOutputSection(viewModel: AudioOutputViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ui = state ?: return
    val current = ui.outputs
    val strings = rememberStrings()
    var details by rememberSaveable { mutableStateOf(false) }

    SectionHeader(stringResource(R.string.audio_output_header))
    SettingsGroup {
        NavigationValueRow(
            title = stringResource(R.string.audio_output_device),
            onClick = { details = true },
            value = AudioOutputText.summary(current, strings),
        )
    }
    SectionFooter(stringResource(R.string.audio_output_footer))
    if (ui.bitPerfectSupported) {
        Spacer(Modifier.height(16.dp))
        SettingsGroup { SwitchRow(stringResource(R.string.audio_output_bit_perfect_switch), ui.bitPerfectEnabled, viewModel::setBitPerfect) }
        SectionFooter(AudioOutputText.bitPerfectSwitchFooter(ui.bitPerfectEnabled, current, strings))
    }
    if (details) AudioOutputPanel(current, strings, onDismiss = { details = false })
}

/**
 * The device that is heard, first and in full with the bit-perfect explanation, then, only when there are any, a list of
 * the other media outputs the user could switch to.
 */
@Composable
private fun AudioOutputPanel(snapshot: AudioOutputSnapshot, strings: Strings, onDismiss: () -> Unit) {
    val title = stringResource(R.string.audio_output_details_title)
    val heard = snapshot.heard
    val others = snapshot.otherDevices
    TranslucentPanel(title, onDismiss = onDismiss) {
        Column {
            ImportPanelTitle(title, heard?.let { AudioOutputText.title(it, strings) })
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 16.dp)) {
                if (heard == null) {
                    TranslucentPanelGroup(title = null) { TranslucentPanelValueRow(stringResource(R.string.audio_output_device), stringResource(R.string.audio_output_none)) }
                } else {
                    DeviceGroup(heard, snapshot, strings)
                    if (heard.mixerOptions.isNotEmpty()) MixerGroup(heard, strings)
                    heard.activeMixer?.let { ActiveGroup(it, strings) }
                }
                if (others.isNotEmpty()) OtherDevicesGroup(others, strings)
            }
        }
    }
}

/** The device that is heard: its title, its facts and the bit-perfect explanation under them. */
@Composable
private fun DeviceGroup(device: AudioOutputDevice, snapshot: AudioOutputSnapshot, strings: Strings) {
    TranslucentPanelGroup(title = AudioOutputText.title(device, strings), footer = AudioOutputText.bitPerfectFooter(snapshot, strings)) {
        TranslucentPanelValueRow(stringResource(R.string.audio_output_row_rates), AudioOutputText.sampleRates(device, strings))
        TranslucentPanelDivider()
        TranslucentPanelValueRow(stringResource(R.string.audio_output_row_depths), AudioOutputText.bitDepths(device, strings))
        TranslucentPanelDivider()
        TranslucentPanelValueRow(stringResource(R.string.audio_output_row_channels), AudioOutputText.channels(device, strings))
        TranslucentPanelDivider()
        TranslucentPanelValueRow(stringResource(R.string.audio_output_row_bit_perfect), AudioOutputText.deviceBitPerfect(device, snapshot.mixerApi, strings))
    }
}

/** The other media outputs, one row each: the device's title and what it can play. */
@Composable
private fun OtherDevicesGroup(devices: List<AudioOutputDevice>, strings: Strings) {
    TranslucentPanelGroup(title = stringResource(R.string.audio_output_others_header)) {
        devices.forEachIndexed { index, device ->
            if (index > 0) TranslucentPanelDivider()
            TranslucentPanelValueRow(AudioOutputText.title(device, strings), AudioOutputText.otherSummary(device, strings))
        }
    }
}

/** The mixer formats the device offers (Android 14+), each marked bit-perfect or default. */
@Composable
private fun MixerGroup(device: AudioOutputDevice, strings: Strings) {
    TranslucentPanelGroup(title = stringResource(R.string.audio_output_mixer_header)) {
        AudioOutputText.sortedMixerOptions(device).forEachIndexed { index, option ->
            if (index > 0) TranslucentPanelDivider()
            TranslucentPanelValueRow(AudioOutputText.mixerFormat(option, strings), AudioOutputText.mixerBehaviour(option, strings))
        }
    }
}

/** The mixer format currently preferred for the device. */
@Composable
private fun ActiveGroup(option: MixerOption, strings: Strings) {
    TranslucentPanelGroup(title = stringResource(R.string.audio_output_active_header)) {
        TranslucentPanelValueRow(AudioOutputText.mixerFormat(option, strings), AudioOutputText.mixerBehaviour(option, strings))
    }
}
