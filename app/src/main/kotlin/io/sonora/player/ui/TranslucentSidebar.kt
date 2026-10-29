package io.sonora.player.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.TranslucentSurface
import io.sonora.core.designsystem.translucent.TranslucentTier
import io.sonora.core.designsystem.translucent.LocalTranslucentBackdrop
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.feature.library.LibraryPage
import io.sonora.player.R

/** The sidebar's rounded corners. */
private val SidebarCorner = 28.dp

/** A row's height: above the 44 dp touch target. */
private val RowHeight = 44.dp

/** How far the Library's pages sit in from the Library row. */
private val PageIndent = 20.dp

/**
 * The large-screen sidebar, as a floating translucent panel (liquid or, see [sidebarTranslucentLiquid], flat; it fills the size its parent gives it): the shell's
 * sections with the Library's pages under Library, the button that hides it, and Settings at the bottom. [selected] is
 * the row of the screen on show (see [sidebarSelected]); each row is a tab for TalkBack. Its translucency
 * samples `LocalTranslucentBackdrop`.
 */
@Composable
internal fun TranslucentSidebar(
    selected: SidebarDestination,
    onSelect: (SidebarDestination) -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    /** Liquid translucency over `LocalTranslucentBackdrop` (at rest, see [sidebarTranslucentLiquid]); else flat, which samples nothing. */
    liquid: Boolean = true,
) {
    val wash by chromeWash()
    val title = stringResource(R.string.sidebar_title)
    TranslucentSurface(
        modifier = modifier.semantics {
            paneTitle = title
            isTraversalGroup = true
        },
        shape = RoundedCornerShape(SidebarCorner),
        backdrop = if (liquid) LocalTranslucentBackdrop.current else null,
        wash = { wash },
    ) {
        Column(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
            SidebarToggle(onHide, Modifier.padding(start = 8.dp))
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            ) {
                SidebarDestinations.forEach { destination ->
                    SidebarRow(destination, destination == selected, onSelect)
                }
            }
            Box(
                Modifier
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(LocalTranslucentContentColors.current.label.copy(alpha = 0.2f)),
            )
            Column(Modifier.padding(horizontal = 8.dp)) {
                SidebarRow(SidebarSettings, SidebarSettings == selected, onSelect)
            }
        }
    }
}

/** The button that hides the sidebar: an accent icon on a 44 dp touch target. */
@Composable
private fun SidebarToggle(onHide: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(RowHeight)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onHide),
        contentAlignment = Alignment.Center,
    ) {
        Icon(SidebarIcon, contentDescription = stringResource(R.string.sidebar_hide), tint = LocalTranslucentContentColors.current.accent)
    }
}

@Composable
private fun SidebarRow(destination: SidebarDestination, selected: Boolean, onSelect: (SidebarDestination) -> Unit) {
    val colors = LocalTranslucentContentColors.current
    val tint by animateColorAsState(if (selected) colors.accent else colors.label, label = "sidebarTint")
    // The same fill as the tab bar's selection pill.
    val fill = if (selected) colors.label.copy(alpha = if (colors.tier == TranslucentTier.FLAT) 0.08f else 0.12f) else Color.Transparent
    val page = destination.isLibraryPage
    Row(
        Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .padding(start = if (page) PageIndent else 0.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(destination) })
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(destination.icon, contentDescription = null, tint = tint, modifier = Modifier.size(if (page) 20.dp else 24.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            destination.label,
            style = if (page) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The Library's pages sit under the Library row; Settings has a row of its own. */
private val SidebarDestination.isLibraryPage: Boolean
    get() = this is SidebarDestination.Page && page != LibraryPage.Settings

private val SidebarDestination.icon: ImageVector
    get() = when (this) {
        is SidebarDestination.Tab -> route.icon
        is SidebarDestination.Page -> when (page) {
            LibraryPage.Playlists -> SonoraIcons.Queue
            LibraryPage.Songs -> SonoraIcons.MusicNote
            LibraryPage.Albums -> SonoraIcons.Album
            LibraryPage.Artists -> SonoraIcons.Person
            LibraryPage.Folders -> SonoraIcons.Folder
            LibraryPage.Converted -> SonoraIcons.Convert
            LibraryPage.Statistics -> SonoraIcons.History
            LibraryPage.Settings -> SonoraIcons.Settings
        }
    }

private val SidebarDestination.label: String
    @Composable get() = when (this) {
        is SidebarDestination.Tab -> stringResource(route.label)
        is SidebarDestination.Page -> stringResource(
            when (page) {
                LibraryPage.Playlists -> R.string.sidebar_playlists
                LibraryPage.Songs -> R.string.sidebar_songs
                LibraryPage.Albums -> R.string.sidebar_albums
                LibraryPage.Artists -> R.string.sidebar_artists
                LibraryPage.Folders -> R.string.sidebar_folders
                LibraryPage.Converted -> R.string.sidebar_converted
                LibraryPage.Statistics -> R.string.sidebar_statistics
                LibraryPage.Settings -> R.string.sidebar_settings
            },
        )
    }
