package io.sonora.feature.settings.dj

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.SegmentedControl
import io.sonora.core.domain.conversion.PerformanceMode
import io.sonora.feature.settings.R
import io.sonora.feature.settings.SectionFooter
import io.sonora.feature.settings.SectionHeader
import io.sonora.feature.settings.SettingsGroup
import io.sonora.feature.settings.SwitchRow

/**
 * Settings › DJ Mode: the switch with a footer saying what it does (while it is on, the song playing and the next ones
 * are analysed in the background: beats, tempo, key, waveform), and the analysis performance, the converter's three
 * modes in the same segmented control with a line saying what the chosen one does.
 */
@Composable
internal fun DjModeSection(viewModel: DjModeSettingsViewModel = hiltViewModel()) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val performance by viewModel.performance.collectAsStateWithLifecycle()
    SectionHeader(stringResource(R.string.settings_dj_mode_header))
    SettingsGroup {
        SwitchRow(stringResource(R.string.settings_dj_mode), enabled, viewModel::setEnabled)
    }
    SectionFooter(stringResource(R.string.settings_dj_mode_footer))
    SectionHeader(stringResource(R.string.settings_dj_performance_header))
    SettingsGroup {
        SegmentedControl(
            options = PerformanceMode.entries,
            selected = performance,
            label = { performanceLabel(it) },
            onSelect = viewModel::setPerformance,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp),
        )
        Text(
            performanceDescription(performance),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
    SectionFooter(stringResource(R.string.settings_dj_performance_footer))
}

@Composable
private fun performanceLabel(mode: PerformanceMode): String = stringResource(
    when (mode) {
        PerformanceMode.LOW_RESOURCES -> R.string.settings_dj_performance_low
        PerformanceMode.NORMAL -> R.string.settings_dj_performance_normal
        PerformanceMode.HIGH_PERFORMANCE -> R.string.settings_dj_performance_high
    },
)

@Composable
private fun performanceDescription(mode: PerformanceMode): String = stringResource(
    when (mode) {
        PerformanceMode.LOW_RESOURCES -> R.string.settings_dj_performance_low_description
        PerformanceMode.NORMAL -> R.string.settings_dj_performance_normal_description
        PerformanceMode.HIGH_PERFORMANCE -> R.string.settings_dj_performance_high_description
    },
)
