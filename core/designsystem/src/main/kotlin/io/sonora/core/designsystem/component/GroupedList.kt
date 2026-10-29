package io.sonora.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.translucent.TranslucentContentColors
import io.sonora.core.designsystem.translucent.TranslucentMaterial
import io.sonora.core.designsystem.translucent.TranslucentTier
import io.sonora.core.designsystem.translucent.LocalTranslucentBackdrop
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.translucent.LocalTranslucentPolicy
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.motion.prefersReducedMotion
import io.sonora.core.designsystem.theme.CapsuleShape
import io.sonora.core.designsystem.theme.SonoraTheme
import kotlinx.coroutines.flow.collectLatest

/*
 * iOS inset grouped list for full screens (Settings-style): an uppercase header, a rounded card of rows on the grouped
 * background, and a footer note. Rows take the whole width of the card and the accent tints their controls.
 */

/** Uppercase caption above a [GroupedCard]. */
@Composable
fun GroupHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 36.dp, end = 36.dp, top = 24.dp, bottom = 6.dp).semantics { heading() },
    )
}

/** Explanatory note under a [GroupedCard]. */
@Composable
fun GroupFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 36.dp, end = 36.dp, top = 6.dp),
    )
}

/** Rounded card holding a section's rows; separate rows with [InsetDivider]. */
@Composable
fun GroupedCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        content = content,
    )
}

/** A tappable row in the accent colour (or the error colour when [destructive]) with a leading icon. */
@Composable
fun ActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val tint = when {
        !enabled -> colors.onSurfaceVariant
        destructive -> colors.error
        else -> colors.primary
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

/** A row that opens another screen: title, optional trailing [value] and a chevron. */
@Composable
fun NavigationValueRow(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, value: String? = null) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Icon(SonoraIcons.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
    }
}

/** A row with a trailing iOS-style switch; the whole row toggles it. */
@Composable
fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = null, // the row handles the toggle, so the switch and the label act as one control
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.primary,
                checkedThumbColor = Color.White,
                checkedBorderColor = Color.Transparent,
                uncheckedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = colors.outline,
            ),
        )
    }
}

/** A row of a single-choice list: a trailing checkmark marks the chosen one. */
@Composable
fun CheckRow(title: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (selected) {
            Icon(SonoraIcons.Checkmark, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * iOS segmented control: equal segments on a plain track, the chosen one under a translucent capsule that springs
 * between segments (and jumps when animations are removed); a tap draws no ripple, the moving capsule is the feedback.
 * Labels may wrap to two lines; with [singleLineLabels] each stays on one line instead and shrinks (down to
 * [SingleLineMinSize]) until it fits its segment, so a long word never breaks inside itself ("Am" / "plio").
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.labelMedium,
    singleLineLabels: Boolean = false,
) {
    val context = LocalContext.current
    val thumb = remember { SegmentThumbState() }
    val selectedIndex by rememberUpdatedState(options.indexOf(selected))
    LaunchedEffect(thumb) {
        snapshotFlow { selectedIndex to segmentThumbFrame(selectedIndex, thumb.segmentWidths) }
            .collectLatest { (index, frame) -> thumb.moveTo(index, frame, context.prefersReducedMotion()) }
    }
    Box(
        modifier
            .fillMaxWidth()
            .clip(CapsuleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .padding(2.dp),
    ) {
        // Dark translucency alone would sit darker than the track; a light wash raises it, as the iOS dark thumb does.
        val wash = if (SonoraTheme.isDark) DarkThumbWash else Color.Transparent
        TranslucentSurface(Modifier.matchParentSize().segmentThumb(thumb), shape = CapsuleShape, elevation = 0.dp, wash = { wash }) {}
        CompositionLocalProvider(LocalTranslucentContentColors provides segmentLabelColors()) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup()) {
                options.forEachIndexed { index, option ->
                    val isSelected = option == selected
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 28.dp)
                            .fillMaxHeight()
                            .onSizeChanged { thumb.segmentMeasured(index, it.width.toFloat()) }
                            .clip(CapsuleShape)
                            // No ripple: drawn above the thumb, it read as a second thumb on the light track (U14).
                            // The sliding thumb is the press feedback, as in iOS; the radio semantics stay.
                            .selectable(
                                selected = isSelected,
                                interactionSource = null,
                                indication = null,
                                role = Role.RadioButton,
                                onClick = { onSelect(option) },
                            )
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val weight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        val color = LocalTranslucentContentColors.current.label
                        if (singleLineLabels) {
                            BasicText(
                                label(option),
                                style = textStyle.copy(color = color, fontWeight = weight, textAlign = TextAlign.Center),
                                maxLines = 1,
                                softWrap = false,
                                autoSize = TextAutoSize.StepBased(minFontSize = SingleLineMinSize, maxFontSize = textStyle.fontSize, stepSize = 0.25.sp),
                            )
                        } else {
                            Text(
                                label(option),
                                style = textStyle,
                                fontWeight = weight,
                                color = color,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The smallest a [SegmentedControl] label with `singleLineLabels` shrinks to. */
private val SingleLineMinSize = 9.sp

/** Lifts the dark thumb above the track (white over dark translucency). */
private val DarkThumbWash = Color.White.copy(alpha = 0.16f)

/**
 * The colours [TranslucentSurface] gives its own content, for labels drawn above the thumb rather than inside it: the
 * same material, so the chosen label reads on the translucent thumb and the others on the plain track.
 */
@Composable
private fun segmentLabelColors(): TranslucentContentColors {
    val policy = LocalTranslucentPolicy.current
    val dark = SonoraTheme.isDark
    val samples = LocalTranslucentBackdrop.current?.layer != null && policy.samplesBackdrop
    val tier = if (samples) policy.tier else TranslucentTier.FLAT
    val accent = if (dark) SonoraTheme.accentOnDark else MaterialTheme.colorScheme.primary
    return remember(dark, tier, policy.highContrast, accent) {
        val material = TranslucentMaterial.of(dark, tier, policy.highContrast)
        TranslucentContentColors(Color(material.label), Color(material.contentColor(accent.toArgb())), tier)
    }
}
