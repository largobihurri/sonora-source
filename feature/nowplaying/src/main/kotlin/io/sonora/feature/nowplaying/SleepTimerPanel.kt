package io.sonora.feature.nowplaying

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.component.TranslucentMenuCheckRow
import io.sonora.core.designsystem.component.TranslucentPanel
import io.sonora.core.designsystem.component.TranslucentPanelActionRow
import io.sonora.core.designsystem.component.TranslucentPanelDivider
import io.sonora.core.designsystem.component.TranslucentPanelGroup
import io.sonora.core.designsystem.component.TranslucentPanelValueRow
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.text.rememberStrings
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.core.domain.playback.SleepCustomDuration
import io.sonora.core.domain.playback.SleepTimerOption
import io.sonora.core.domain.playback.SleepTimerState

/** The options the panel lists, in order: the fixed durations, then the end of the song and of the album. */
private val SleepOptions: List<SleepTimerOption> =
    SleepTimerOption.PRESET_MINUTES.map(SleepTimerOption::Minutes) + SleepTimerOption.EndOfSong + SleepTimerOption.EndOfAlbum

/**
 * "Sleep Timer" as a floating translucent panel over the blurred app, like File Info. While a [timer] runs it shows
 * the time left, "Add 10 Minutes" (the panel stays) and "Turn Off Timer"; below, the options (the running one
 * checked) and a custom duration of [customMinutes], stepped five minutes at a time. Choosing an option or turning the
 * timer off closes the panel; tapping outside and Back call [onDismiss].
 */
@Composable
internal fun SleepTimerPanel(
    timer: SleepTimerState?,
    customMinutes: Int,
    actions: SleepTimerViewModel,
    onDismiss: () -> Unit,
) {
    val strings = rememberStrings()
    TranslucentPanel(stringResource(R.string.sleep_timer_title), onDismiss) {
        Column(Modifier.animateContentSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            if (timer != null) {
                TranslucentPanelGroup(title = SleepTimerText.option(timer.option, strings)) {
                    TranslucentPanelValueRow(stringResource(R.string.sleep_timer_time_left), SleepTimerText.countdown(timer.remainingMs))
                    TranslucentPanelDivider()
                    val extend = SleepTimerViewModel.EXTEND_MINUTES
                    TranslucentPanelActionRow(pluralStringResource(R.plurals.sleep_timer_extend, extend, extend), SonoraIcons.Add, actions::extend)
                    TranslucentPanelDivider()
                    TranslucentPanelActionRow(stringResource(R.string.sleep_timer_turn_off), SonoraIcons.Clear) {
                        actions.cancel()
                        onDismiss()
                    }
                }
            }
            TranslucentPanelGroup(title = stringResource(R.string.sleep_timer_stop_playing)) {
                SleepOptions.forEachIndexed { index, option ->
                    if (index > 0) TranslucentPanelDivider()
                    TranslucentMenuCheckRow(SleepTimerText.option(option, strings), selected = timer?.option == option) {
                        actions.start(option)
                        onDismiss()
                    }
                }
            }
            TranslucentPanelGroup(title = stringResource(R.string.sleep_timer_custom)) {
                CustomDurationStepper(customMinutes, actions::stepCustom)
                TranslucentPanelDivider()
                TranslucentPanelActionRow(stringResource(R.string.sleep_timer_start), MoonIcon) {
                    actions.startCustom()
                    onDismiss()
                }
            }
        }
    }
}

/** "−  20 Minutes  +": the custom duration and its two steps, each a 44 dp button. */
@Composable
private fun CustomDurationStepper(minutes: Int, onStep: (Int) -> Unit) {
    val label = LocalTranslucentContentColors.current.label
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", stringResource(R.string.sleep_timer_shorter), enabled = minutes > SleepCustomDuration.MIN_MINUTES) { onStep(-1) }
        Text(
            pluralStringResource(R.plurals.sleep_timer_minutes, minutes, minutes),
            color = label,
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        StepButton("+", stringResource(R.string.sleep_timer_longer), enabled = minutes < SleepCustomDuration.MAX_MINUTES) { onStep(+1) }
    }
}

@Composable
private fun StepButton(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val label = LocalTranslucentContentColors.current.label
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = label.copy(alpha = if (enabled) 1f else 0.35f), fontSize = 24.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Now Playing's countdown while a sleep timer runs: a small dark translucent capsule with a moon and the time left, as tall
 * as the header's round buttons beside it; a tap opens the panel ([onClick]).
 */
@Composable
internal fun SleepTimerBadge(remainingMs: Long?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val time = SleepTimerText.countdown(remainingMs)
    val description = stringResource(R.string.sleep_timer_badge, time)
    // Always dark here, whatever the app theme, like the round buttons beside it.
    TranslucentSurface(modifier.height(BadgeHeight), shape = CapsuleShape, elevation = 0.dp, darkTheme = true, tinted = false) {
        val label = LocalTranslucentContentColors.current.label
        Row(
            Modifier
                .fillMaxHeight()
                .clip(CapsuleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = description }
                // The moon sits as far from the leading edge as from the top and the bottom.
                .padding(start = (BadgeHeight - BadgeGlyph) / 2, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(MoonIcon, contentDescription = null, tint = label, modifier = Modifier.size(BadgeGlyph))
            // Tabular figures, so the capsule keeps its width as the seconds tick.
            Text(time, color = label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, style = TextStyle(fontFeatureSettings = "tnum"))
        }
    }
}

/** The header's round buttons are 36 dp ([GlyphButton] of 20 dp plus its padding); the badge matches them. */
private val BadgeHeight = 36.dp
private val BadgeGlyph = 16.dp

/** A crescent moon, the sleep timer's sign. */
internal val MoonIcon: ImageVector by lazy {
    ImageVector.Builder("Moon", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes("M12.34,2.02C6.59,1.82 2,6.42 2,12c0,5.52 4.48,10 10,10c3.71,0 6.93,-2.02 8.66,-5.02C13.15,16.73 8.57,8.55 12.34,2.02z"),
            fill = SolidColor(Color.Black),
        ).build()
}
