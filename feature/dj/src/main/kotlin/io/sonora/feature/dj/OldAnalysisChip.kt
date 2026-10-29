package io.sonora.feature.dj

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.component.TranslucentAlert
import io.sonora.core.designsystem.component.TranslucentAlertButton
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.theme.CapsuleShape

/**
 * The small hint in the time row that the stored analysis comes from an older analyser (D2.11): a dark translucent capsule, 18 dp
 * tall like the "Edited" badge, reading "Old analysis · Update" (just "Update" when [short], next to that badge). A tap asks
 * for a new analysis. The text shrinks (down to 9 sp) before it is cut, and the accent colour says it can be tapped.
 */
@Composable
internal fun OldAnalysisChip(short: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.dj_old_analysis_chip_description)
    val label = stringResource(if (short) R.string.dj_old_analysis_chip_short else R.string.dj_old_analysis_chip)
    TranslucentSurface(
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
                .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
                .semantics { contentDescription = description }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                label,
                Modifier.clearAndSetSemantics { },
                style = ChipStyle.copy(color = LocalTranslucentContentColors.current.accent),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = ChipStyle.fontSize, stepSize = 0.5.sp),
            )
        }
    }
}

/** Whether to ask before analysing again: only a song whose beat grid the user edited (the edit is kept either way). */
fun askBeforeReanalysing(gridEdited: Boolean): Boolean = gridEdited

/**
 * Asks before a song whose beat grid the user edited is analysed again: the edit and the cues are kept, and the edited grid
 * still replaces the new analysis' one. It is a translucent alert (never a Material dialog, which cannot sample the app).
 */
@Composable
fun ReanalyseConfirm(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    TranslucentAlert(
        title = stringResource(R.string.dj_reanalyse_title),
        message = stringResource(R.string.dj_reanalyse_message),
        onDismiss = onDismiss,
        confirm = TranslucentAlertButton(stringResource(R.string.dj_reanalyse_confirm), onClick = onConfirm),
        cancel = TranslucentAlertButton(stringResource(R.string.dj_reanalyse_cancel), onClick = onDismiss),
    )
}

private val ChipHeight = 18.dp
private val ChipStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
