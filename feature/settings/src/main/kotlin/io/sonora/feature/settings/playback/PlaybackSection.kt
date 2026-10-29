package io.sonora.feature.settings.playback

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.TranslucentAlert
import io.sonora.core.designsystem.component.TranslucentAlertButton
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.text.rememberStrings
import io.sonora.core.domain.playback.Crossfade
import io.sonora.core.domain.playback.ReplayGainMode
import io.sonora.core.domain.playback.ReplayGainSettings
import io.sonora.feature.settings.ChoiceRow
import io.sonora.feature.settings.FolderNotice
import io.sonora.feature.settings.R
import io.sonora.feature.settings.SectionFooter
import io.sonora.feature.settings.SectionHeader
import io.sonora.feature.settings.SettingsGroup
import io.sonora.feature.settings.SettingsText
import kotlin.math.roundToInt

/**
 * Settings › Playback: volume normalisation (ReplayGain), a mode choice and a pre-amp slider, then the crossfade
 * length. Each part shows once its stored settings are read. While [locked] (bit-perfect USB output is working) they
 * are shown faded and cannot be changed: the player holds them off. Otherwise the player follows every saved change live.
 */
@Composable
internal fun PlaybackSection(locked: Boolean = false, viewModel: PlaybackSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val crossfadeSeconds by viewModel.crossfadeSeconds.collectAsStateWithLifecycle()
    val saveFailed by viewModel.saveFailed.collectAsStateWithLifecycle()
    val current = settings ?: return
    val strings = rememberStrings()

    SectionHeader(stringResource(R.string.playback_header))
    SettingsGroup {
        ReplayGainMode.entries.forEachIndexed { index, mode ->
            if (index > 0) InsetDivider(start = 16.dp)
            ChoiceRow(
                title = PlaybackSettingsText.modeTitle(mode, strings),
                subtitle = PlaybackSettingsText.modeSubtitle(mode, strings),
                selected = current.mode == mode,
                onSelect = { viewModel.setMode(mode) },
                enabled = !locked,
            )
        }
    }
    SectionFooter(stringResource(R.string.playback_normalisation_footer))
    Spacer(Modifier.height(16.dp))
    SettingsGroup { PreampRow(current, viewModel::setPreamp, locked) }
    SectionFooter(stringResource(R.string.playback_preamp_footer))
    crossfadeSeconds?.let { seconds ->
        Spacer(Modifier.height(16.dp))
        SettingsGroup { CrossfadeRow(seconds, viewModel::setCrossfadeSeconds, locked) }
        SectionFooter(stringResource(R.string.crossfade_footer))
    }
    if (locked) SectionFooter(stringResource(R.string.playback_locked_footer))

    if (saveFailed) {
        val text = SettingsText.notice(FolderNotice.SaveFailed, strings)
        TranslucentAlert(
            title = text.title,
            message = text.message,
            onDismiss = viewModel::dismissSaveFailed,
            confirm = TranslucentAlertButton(stringResource(R.string.settings_ok), onClick = viewModel::dismissSaveFailed),
        )
    }
}

/** The pre-amp: its value while dragging is local, and saved once the drag ends. Off, it is shown but disabled. */
@Composable
private fun PreampRow(settings: ReplayGainSettings, onPreamp: (Float) -> Unit, locked: Boolean) {
    val strings = rememberStrings()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: settings.preampDb
    val label = stringResource(R.string.playback_preamp)
    val value = PlaybackSettingsText.preamp(shown, strings)
    val enabled = settings.mode != ReplayGainMode.OFF && !locked
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let(onPreamp)
                dragging = null
            },
            enabled = enabled,
            valueRange = ReplayGainSettings.MIN_PREAMP_DB..ReplayGainSettings.MAX_PREAMP_DB,
            steps = PREAMP_STEPS,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = value
            },
        )
    }
}

/** The crossfade title and length over a slider in whole seconds; the length is stored when the drag ends. */
@Composable
private fun CrossfadeRow(seconds: Int, onChange: (Int) -> Unit, locked: Boolean) {
    var dragged by remember { mutableStateOf<Float?>(null) }
    val shown = dragged?.roundToInt() ?: seconds
    val title = stringResource(R.string.crossfade_title)
    val label = CrossfadeText.label(shown, rememberStrings())
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = dragged ?: seconds.toFloat(),
            onValueChange = { dragged = it },
            onValueChangeFinished = {
                dragged?.let { onChange(it.roundToInt()) }
                dragged = null
            },
            enabled = !locked,
            valueRange = Crossfade.MIN_SECONDS.toFloat()..Crossfade.MAX_SECONDS.toFloat(),
            // One stop per whole second between the ends.
            steps = Crossfade.MAX_SECONDS - Crossfade.MIN_SECONDS - 1,
            modifier = Modifier.semantics {
                contentDescription = title
                stateDescription = label
            },
        )
    }
}

/** Positions between the ends of the slider, one per half decibel. */
private val PREAMP_STEPS =
    ((ReplayGainSettings.MAX_PREAMP_DB - ReplayGainSettings.MIN_PREAMP_DB) / PlaybackSettingsViewModel.PREAMP_STEP_DB).toInt() - 1
