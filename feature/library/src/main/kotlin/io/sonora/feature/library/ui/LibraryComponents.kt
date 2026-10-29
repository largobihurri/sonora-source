package io.sonora.feature.library.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.component.Artwork
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.component.rememberMenuAnchorSource
import io.sonora.core.designsystem.icon.SonoraIcons
import io.sonora.core.designsystem.layout.LocalContentArea
import io.sonora.core.designsystem.motion.LocalStackPush
import io.sonora.core.designsystem.text.LocaleFormats
import io.sonora.core.designsystem.text.currentLocale
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Track
import io.sonora.feature.library.R

/** A library home row; with [pushTo] set, dragging its chevron to the left drags that page in (see [ChevronPush]). */
@Composable
internal fun NavigationRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    showDivider: Boolean = true,
    pushTo: Any? = null,
) {
    // The chevron sits in or next to the system's right-edge back zone: while the drag is offered, the part of the zone
    // around it is the app's, sized from the measured inset and within the per-edge budget. Never the whole row.
    val offered = pushTo != null && LocalStackPush.current != null
    Column(Modifier.chevronPush(pushTo).clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().chevronBackExclusion(offered).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(16.dp))
            Text(title, fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
            Icon(
                SonoraIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showDivider) InsetDivider(start = 62.dp)
    }
}

/**
 * Tap plays; long-press (with haptic feedback) opens the queue menu. With [selected] set (a picker), it shows a check
 * mark at the trailing edge and reads as selectable; the caller makes tap toggle it.
 */
@Composable
internal fun SongRow(
    track: Track,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    leadingNumber: Int? = null,
    selected: Boolean? = null,
    pinned: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // On a large screen a wide list lays the row out as a table (artist, album, length); a phone's is [SongTable.None].
    val table = SongTable.of(LocalContentArea.current.width?.value, artistAndAlbum = leadingNumber == null)
    val selection = if (selected == null) {
        Modifier
    } else {
        val state = stringResource(if (selected) R.string.picker_selected_state else R.string.picker_not_selected_state)
        Modifier.semantics {
            this.selected = selected
            stateDescription = state
        }
    }
    Column(modifier.pressable(onClick, onLongClick).then(selection)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingNumber != null) {
                Text(
                    text = leadingNumber.toString(),
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(28.dp),
                )
            } else {
                Artwork(track.artworkUri, Modifier.size(48.dp), RoundedCornerShape(6.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(
                    track.title,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (leadingNumber == null && !table.artist) {
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            SongTableCells(table, track.artist, track.album)
            if (table.duration) DurationCell(track.durationMs)
            if (pinned) PinnedGlyph(Modifier.padding(start = 8.dp))
            if (selected != null) {
                Spacer(Modifier.width(12.dp))
                SelectionMark(selected)
            }
        }
        InsetDivider(start = if (leadingNumber != null) 48.dp else 80.dp)
    }
}

/** iOS-style selection circle: filled with a check mark when [selected], an empty ring otherwise. */
@Composable
internal fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = CircleShape
    Box(
        modifier
            .size(24.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(colors.primary) else Modifier.border(1.5.dp, colors.onSurfaceVariant, shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(SonoraIcons.Checkmark, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(16.dp))
    }
}

/** The small pin of an item pinned to the top of its list. */
@Composable
internal fun PinnedGlyph(modifier: Modifier = Modifier) {
    Icon(
        SonoraIcons.PinFilled,
        contentDescription = stringResource(R.string.menu_pinned),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(16.dp),
    )
}

/** A folder: tap opens it, long-press opens the context menu for everything inside. */
@Composable
internal fun FolderRow(label: String, trackCount: Int, onClick: () -> Unit, onLongClick: () -> Unit, pinned: Boolean = false) {
    Column(Modifier.pressable(onClick, onLongClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(SonoraIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Text(
                label,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (pinned) PinnedGlyph(Modifier.padding(start = 8.dp))
            Spacer(Modifier.width(8.dp))
            Text(LocaleFormats.integer(trackCount, currentLocale()), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(SonoraIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        InsetDivider(start = 64.dp)
    }
}

@Composable
internal fun AlbumTile(album: Album, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier, pinned: Boolean = false) {
    Column(modifier.pressable(onClick, onLongClick), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Artwork(album.artworkUri, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(10.dp))
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                album.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (pinned) PinnedGlyph(Modifier.padding(start = 4.dp))
        }
        Text(
            album.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Click plus a labelled long-press (also offered to accessibility services) with the long-press haptic. The
 * long-press also marks this element (and the finger on it) as where the context menu grows from.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.pressable(onClick: () -> Unit, onLongClick: () -> Unit): Modifier {
    val anchor = rememberMenuAnchorSource()
    return this.then(anchor.modifier).combinedClickable(
        onLongClickLabel = stringResource(R.string.queue_options),
        hapticFeedbackEnabled = true,
        onLongClick = {
            anchor.capture()
            onLongClick()
        },
        onClick = onClick,
    )
}

@Composable
internal fun EmptyMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(20.dp),
    )
}
