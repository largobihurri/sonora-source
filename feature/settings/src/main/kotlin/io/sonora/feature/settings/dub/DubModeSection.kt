package io.sonora.feature.settings.dub

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.ActionRow
import io.sonora.core.designsystem.component.SegmentedControl
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.dubmodel.PadBehaviour
import io.sonora.feature.settings.R
import io.sonora.feature.settings.SectionFooter
import io.sonora.feature.settings.SectionHeader
import io.sonora.feature.settings.SettingsGroup
import io.sonora.feature.settings.SwitchRow

/**
 * Settings › DUB Mode (design §5), after DJ Mode: the switch with a footer that says honestly what it does (effects
 * are off for bit-perfect output and remote playback; controls other than THROW answer about 0.2 s after the touch),
 * how the pads answer a touch, and a reset of the stored effect setup.
 */
@Composable
internal fun DubModeSection(viewModel: DubModeSettingsViewModel = hiltViewModel()) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val pads by viewModel.padBehaviour.collectAsStateWithLifecycle()
    SectionHeader(stringResource(R.string.settings_dub_mode_header))
    SettingsGroup {
        SwitchRow(stringResource(R.string.settings_dub_mode), enabled, viewModel::setEnabled)
    }
    SectionFooter(stringResource(R.string.settings_dub_mode_footer))
    SectionHeader(stringResource(R.string.settings_dub_pads_header))
    SettingsGroup {
        SegmentedControl(
            options = PadBehaviour.entries,
            selected = pads,
            label = {
                stringResource(
                    when (it) {
                        PadBehaviour.HOLD_DOUBLE_TAP_LATCH -> R.string.settings_dub_pads_hold
                        PadBehaviour.TOGGLE -> R.string.settings_dub_pads_toggle
                    },
                )
            },
            onSelect = viewModel::setPadBehaviour,
            modifier = Modifier.padding(12.dp),
        )
        ActionRow(SonoraIcons.Refresh, stringResource(R.string.settings_dub_reset), onClick = viewModel::resetSetup)
    }
    SectionFooter(stringResource(R.string.settings_dub_pads_footer))
}
