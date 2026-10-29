package io.sonora.feature.library.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.sonora.core.designsystem.component.TranslucentBarCircle
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.feature.library.R

/**
 * The translucent button in the bar of Albums, Artists and Playlists that opens that list as a Cover Flow. Shown only
 * on large screens (see `hasCoverFlowButton`), where the Cover Flow is a view the user opens, not the sideways turn.
 */
@Composable
internal fun CoverFlowButton(onClick: () -> Unit) {
    TranslucentBarCircle(SonoraIcons.CoverFlow, stringResource(R.string.coverflow_open), onClick)
}
