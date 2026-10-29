package io.sonora.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.translucent.TranslucentBackdrop
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.translucent.LocalTranslucentPolicy
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.theme.SonoraTheme

/**
 * One context menu entry; [destructive] ones are red (Remove from Library). A row that toggles a mode says whether it
 * is on with [checked] (a trailing checkmark, announced as a switch); null for a plain action.
 */
@Immutable
data class ContextMenuItem(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val checked: Boolean? = null,
    val onClick: () -> Unit,
)

/** The menu and its preview card share this shape (about 12 % smaller than iOS's 26 pt, like the rest of the menu). */
private val MenuShape = RoundedCornerShape(22.dp)

/** Rows stay at least a touch target tall. */
private val RowMinHeight = 44.dp
private val IconSize = 20.dp
private val RowLabelSize = 15.sp
private val RowLabelLineHeight = 19.sp

/** iOS red, readable on dark and light translucency. */
private val DestructiveDark = Color(0xFFFF6961)
private val DestructiveLight = Color(0xFFD70015)

private const val DISABLED_ALPHA = 0.38f

/**
 * The translucent panel a menu (and its preview card) sits on, sampling the blurred app ([translucent]); its specular
 * light follows [lightShift] (see [overlayLightShift]). Over flat translucency it stands on an opaque backing
 * ([overlayPanelBacking]).
 */
@Composable
internal fun ContextMenuTranslucent(translucent: TranslucentBackdrop, lightShift: () -> Float, content: @Composable () -> Unit) {
    val backing = overlayPanelBacking(LocalTranslucentPolicy.current, SonoraTheme.isDark)
    Box(if (backing != null) Modifier.background(backing, MenuShape) else Modifier) {
        TranslucentSurface(shape = MenuShape, elevation = 16.dp, backdrop = translucent, lightShift = lightShift) {
            Box(Modifier.clip(MenuShape)) { content() }
        }
    }
}

/**
 * iOS 26 context menu content: a row of equal icon-over-label buttons ([top]), then [sections] of icon + label rows
 * separated by thin lines. Scrolls when the screen is too short for it.
 */
@Composable
fun ContextMenuPanel(top: List<ContextMenuItem>, sections: List<List<ContextMenuItem>>) {
    val label = LocalTranslucentContentColors.current.label
    // A new scroll state each time the menu opens: it always starts at its top row.
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
        if (top.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (item in top) TopButton(item, label, Modifier.weight(1f))
            }
        }
        sections.forEachIndexed { index, section ->
            if (index > 0 || top.isNotEmpty()) Separator(label)
            for (item in section) MenuRow(item, label)
        }
    }
}

@Composable
private fun Separator(label: Color) {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 3.dp).fillMaxWidth().height(0.75.dp).background(label.copy(alpha = 0.16f)))
}

@Composable
private fun itemColor(item: ContextMenuItem, label: Color): Color =
    if (item.destructive) (if (SonoraTheme.isDark) DestructiveDark else DestructiveLight) else label

@Composable
private fun TopButton(item: ContextMenuItem, label: Color, modifier: Modifier) {
    val color = itemColor(item, label)
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = item.enabled, role = Role.Button, onClick = item.onClick)
            .alpha(if (item.enabled) 1f else DISABLED_ALPHA)
            .heightIn(min = RowMinHeight)
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(item.icon, contentDescription = null, tint = color, modifier = Modifier.size(IconSize))
        Spacer(Modifier.height(3.dp))
        Text(
            item.label,
            color = color,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MenuRow(item: ContextMenuItem, label: Color) {
    val color = itemColor(item, label)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .then(
                if (item.checked == null) {
                    Modifier.clickable(enabled = item.enabled, role = Role.Button, onClick = item.onClick)
                } else {
                    Modifier.toggleable(item.checked, enabled = item.enabled, role = Role.Switch) { item.onClick() }
                },
            )
            .alpha(if (item.enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(item.icon, contentDescription = null, tint = color, modifier = Modifier.size(IconSize))
        Spacer(Modifier.width(12.dp))
        Text(item.label, color = color, fontSize = RowLabelSize, lineHeight = RowLabelLineHeight, modifier = Modifier.weight(1f))
        if (item.checked == true) {
            Spacer(Modifier.width(8.dp))
            Icon(SonoraIcons.Checkmark, contentDescription = null, tint = color, modifier = Modifier.size(IconSize))
        }
    }
}

/**
 * The lifted preview card: artwork, bold title and up to three more lines (artist, "album · year"). The context menu
 * host lays it on the same translucent surface as the menu, so its text takes the translucent colours.
 */
@Composable
fun ContextMenuPreviewCard(
    artworkUri: String?,
    title: String,
    lines: List<String>,
    artworkShape: Shape = RoundedCornerShape(10.dp),
    artwork: (@Composable (Modifier) -> Unit)? = null,
) {
    val label = LocalTranslucentContentColors.current.label
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        val artModifier = Modifier.size(72.dp)
        if (artwork != null) artwork(artModifier) else Artwork(artworkUri, artModifier, artworkShape)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = label, fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            for (line in lines) {
                Text(line, color = label.copy(alpha = 0.62f), fontSize = 14.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
