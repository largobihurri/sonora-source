package io.sonora.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.icon.SonoraIcons

/*
 * Building blocks for the content of a [TranslucentPanel], in the context menu's type and spacing: a compact header,
 * and rounded groups of rows split by thin lines; and, for pull-down menus, a heading, separators and checkable rows.
 * Colours come from the translucent surface ([LocalTranslucentContentColors]) so they keep their contrast over
 * whatever the panel samples, in light and dark.
 */

private val GroupShape = RoundedCornerShape(18.dp)
private const val SECONDARY_ALPHA = 0.62f
private const val DISABLED_ALPHA = 0.4f

/** The translucent label colour, and a softer one for secondary text. */
private val primary: Color @Composable get() = LocalTranslucentContentColors.current.label
private val secondary: Color @Composable get() = primary.copy(alpha = SECONDARY_ALPHA)

/** The panel's header: small artwork, the [title] and an optional [subtitle], read out as one. */
@Composable
fun TranslucentPanelHeader(artworkUri: String?, title: String, subtitle: String?) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(artworkUri, Modifier.size(56.dp), RoundedCornerShape(8.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = primary, fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, color = secondary, fontSize = 15.sp, lineHeight = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * A group footer's text: small, and broken into lines of balanced length, so a two- or three-line note never leaves
 * one short word alone on its last line (the Equalizer hint ended on "dB." by itself). Public so a panel that measures
 * its footer ahead of layout measures it the same way.
 */
val TranslucentPanelFooterStyle = TextStyle(
    fontSize = 13.sp,
    lineHeight = 17.sp,
    lineBreak = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Normal, LineBreak.WordBreak.Default),
)

/** A titled group: an optional small [title], a rounded card of rows (split them with [TranslucentPanelDivider]) and an optional [footer]. */
@Composable
fun TranslucentPanelGroup(title: String?, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        title?.let {
            Text(
                it.uppercase(),
                color = secondary,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
            )
        }
        Column(Modifier.fillMaxWidth().clip(GroupShape).background(primary.copy(alpha = 0.07f)), content = content)
        footer?.let {
            Text(it, color = secondary, style = LocalTextStyle.current.merge(TranslucentPanelFooterStyle), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
        }
    }
}

/** The thin line between two rows of a group, as between the context menu's sections. */
@Composable
fun TranslucentPanelDivider() {
    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.75.dp).background(primary.copy(alpha = 0.16f)))
}

/** A [label] (secondary) with its [value] (primary) trailing; long values wrap. [selectable] values can be copied. */
@Composable
fun TranslucentPanelValueRow(label: String, value: String, selectable: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = secondary, fontSize = 16.sp, lineHeight = 21.sp, modifier = Modifier.padding(end = 16.dp))
        val valueText = @Composable {
            Text(value, color = primary, fontSize = 16.sp, lineHeight = 21.sp, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
        }
        Box(Modifier.weight(1f)) {
            if (selectable) SelectionContainer { valueText() } else valueText()
        }
    }
}

/** An action row in the context menu's style: [icon] and [label], the whole row a button. */
@Composable
fun TranslucentPanelActionRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    TranslucentPanelActionRow(label, icon, enabled = true, onClick = onClick)
}

/** [TranslucentPanelActionRow] that is dimmed and inert while not [enabled]. */
@Composable
fun TranslucentPanelActionRow(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .heightIn(min = 46.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = primary, fontSize = 17.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f))
    }
}

/** An iOS knob stays white on either theme; only the track follows the translucent surface. */
private val SwitchKnob = Color.White

/** A dimmed switch's "on" track: the accent at this share, so it no longer reads as active (off is already a faint tint). */
private const val DIMMED_TRACK_ALPHA = 0.4f

/**
 * A [label] with an iOS-style switch trailing, the whole row one control ([checked] and [onChange]). The track takes
 * the translucent accent when on and a faint label tint when off, so it reads on light and dark translucency alike. A
 * [contentDescription] names the control when the visible label is a value rather than its name. A [dimmed] switch
 * (one whose setting has no effect right now, like the preamp while the equalizer is off) keeps working and keeps its
 * label's contrast, but its track fades so an "on" switch does not shine in the accent.
 */
@Composable
fun TranslucentPanelSwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    contentDescription: String? = null,
    dimmed: Boolean = false,
) {
    val colors = LocalTranslucentContentColors.current
    val trackAlpha = if (dimmed) DIMMED_TRACK_ALPHA else 1f
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription?.let { this.contentDescription = it } }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = primary, fontSize = 17.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.accent.copy(alpha = colors.accent.alpha * trackAlpha),
                checkedThumbColor = SwitchKnob,
                checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = colors.label.copy(alpha = 0.16f),
                uncheckedThumbColor = SwitchKnob,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** A pull-down menu's small heading ("Sort By"), in secondary text. */
@Composable
fun TranslucentMenuHeader(text: String) {
    Text(
        text,
        color = secondary,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** The thin line between a menu's sections, as in the context menu. */
@Composable
fun TranslucentMenuSeparator() {
    Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth().height(0.75.dp).background(primary.copy(alpha = 0.16f)))
}

/** A menu choice in the context menu's row style, with a check in the icon slot while [selected]. */
@Composable
fun TranslucentMenuCheckRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp)) {
            if (selected) Icon(SonoraIcons.Checkmark, contentDescription = null, tint = primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, color = primary, fontSize = 17.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f))
    }
}
