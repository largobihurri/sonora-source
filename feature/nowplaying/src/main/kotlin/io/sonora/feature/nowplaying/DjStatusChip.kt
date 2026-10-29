package io.sonora.feature.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.text.rememberStrings
import io.sonora.core.designsystem.theme.CapsuleShape

/**
 * The analysis of the song playing, as a small translucent capsule in Now Playing's header while DJ mode is on: "DJ" (waiting
 * or analysed), "DJ 42%" while analysing, "DJ !" when the analysis failed. It only informs, so it is not clickable.
 * Nothing is drawn while DJ mode is off.
 */
@Composable
internal fun DjStatusChip(modifier: Modifier = Modifier, viewModel: DjModeViewModel = hiltViewModel()) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    if (!enabled) return
    val status by viewModel.playingStatus.collectAsStateWithLifecycle()
    val strings = rememberStrings()
    val chip = DjStatusText.chip(status)
    val text = DjStatusText.label(chip, strings.locale)
    val description = DjStatusText.description(chip, strings)
    TranslucentSurface(
        // Always dark, like the round buttons beside it; a 32 dp chip, as the speed badge.
        modifier.height(ChipHeight),
        shape = CapsuleShape,
        elevation = 0.dp,
        darkTheme = true,
        tinted = true,
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .clip(CapsuleShape)
                .clearAndSetSemantics { contentDescription = description }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Tabular figures, so the capsule keeps its width as the percentage ticks.
            Text(
                text,
                color = LocalTranslucentContentColors.current.label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
        }
    }
}

private val ChipHeight = 32.dp

/** A record: a disc with its label, for the "…" menu's DJ Mode row. */
internal val DjIcon: ImageVector by lazy {
    ImageVector.Builder("Dj", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes(
                "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z" +
                    "M12,9a3,3 0,1 0,0.01 0z",
            ),
            fill = SolidColor(Color.Black),
        ).build()
}

/** Three bar lines, the middle one shorter, one a bar start: for the "…" menu's Edit beat grid row. */
internal val BeatGridIcon: ImageVector by lazy {
    ImageVector.Builder("BeatGrid", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes(
                "M3,4h2v16H3z" +
                    "M8,9h1.5v11H8z" +
                    "M12.5,9h1.5v11h-1.5z" +
                    "M17,4h2v16h-2z" +
                    "M21,9h1v11h-1z",
            ),
            fill = SolidColor(Color.Black),
        ).build()
}
