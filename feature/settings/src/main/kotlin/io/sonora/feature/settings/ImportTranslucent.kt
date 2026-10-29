package io.sonora.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.sonora.core.designsystem.component.TranslucentPanelDivider
import io.sonora.core.designsystem.component.TranslucentPanelGroup
import io.sonora.core.designsystem.translucent.LocalTranslucentContentColors
import io.sonora.core.domain.archive.ImportedSong

/*
 * Pieces of the import's translucent panels (the result of an import, an entry of the import history), in the type
 * and spacing of the designsystem's TranslucentPanel content and in the translucency's own label colours.
 */

private const val SECONDARY_ALPHA = 0.62f

/** The panel's heading: a [title] and an optional [subtitle], read out as one. */
@Composable
internal fun ImportPanelTitle(title: String, subtitle: String?) {
    val label = LocalTranslucentContentColors.current.label
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() }.padding(start = 28.dp, end = 28.dp, top = 18.dp, bottom = 6.dp)) {
        Text(title, color = label, fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        subtitle?.let {
            Text(it, color = label.copy(alpha = SECONDARY_ALPHA), fontSize = 15.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A translucent action row: [icon] and [label]; [destructive] ones in the theme's error red. */
@Composable
internal fun ImportPanelAction(label: String, icon: ImageVector, onClick: () -> Unit, destructive: Boolean = false) {
    val color = if (destructive) MaterialTheme.colorScheme.error else LocalTranslucentContentColors.current.label
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = color, fontSize = 17.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f))
    }
}

/** Every song of an import, title over artist, in a titled translucent group. Nothing when there are none. */
@Composable
internal fun ImportedSongsGroup(songs: List<ImportedSong>) {
    if (songs.isEmpty()) return
    TranslucentPanelGroup(title = stringResource(R.string.import_songs_header)) {
        songs.forEachIndexed { index, song ->
            if (index > 0) TranslucentPanelDivider()
            ImportedSongRow(song, LocalTranslucentContentColors.current.label)
        }
    }
}

@Composable
private fun ImportedSongRow(song: ImportedSong, label: Color) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(song.title, color = label, fontSize = 16.sp, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (song.artist.isNotBlank()) {
            Text(song.artist, color = label.copy(alpha = SECONDARY_ALPHA), fontSize = 14.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
