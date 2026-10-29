package io.sonora.feature.library.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.text.LocaleFormats

/**
 * The columns a song row shows beside its title on a large screen, like the Songs table of Apple Music on the iPad:
 * the artist, the album and the length. A phone, and any list too narrow for them, keeps the phone row (title with the
 * artist under it), which is [None].
 */
internal data class SongTable(val artist: Boolean, val album: Boolean, val duration: Boolean) {
    /** Whether any column is shown, so the row is laid out as a table. */
    val isTable: Boolean get() = artist || album || duration

    companion object {
        val None = SongTable(artist = false, album = false, duration = false)

        /** The narrowest list that shows the artist (and the length) as columns. */
        const val ARTIST_MIN_DP = 560

        /** The narrowest list that shows the album too. */
        const val ALBUM_MIN_DP = 760

        /** The narrowest list of numbered rows (an album's songs) that shows the length. */
        const val NUMBERED_MIN_DP = 360

        /**
         * The columns of a row in a list [widthDp] wide (`null` on a phone: none). [artistAndAlbum] is true for rows
         * that carry their own cover (Songs, search, folders, playlists), whose artist and album are not known from the
         * page; an album's numbered rows pass false and only gain the length.
         */
        fun of(widthDp: Float?, artistAndAlbum: Boolean): SongTable = when {
            widthDp == null -> None
            !artistAndAlbum -> if (widthDp >= NUMBERED_MIN_DP) SongTable(artist = false, album = false, duration = true) else None
            widthDp >= ALBUM_MIN_DP -> SongTable(artist = true, album = true, duration = true)
            widthDp >= ARTIST_MIN_DP -> SongTable(artist = true, album = false, duration = true)
            else -> None
        }
    }
}

/** The share of a table row's free width each text column takes, the title's being 1. */
private const val SECONDARY_WEIGHT = 0.8f

private val ColumnGap = 16.dp
private val DurationWidth = 56.dp

/** The artist and album columns of [table] for a song, after the title; the caller adds [DurationCell] at the end. */
@Composable
internal fun RowScope.SongTableCells(table: SongTable, artist: String, album: String) {
    if (table.artist) TableTextCell(artist)
    if (table.album) TableTextCell(album)
}

@Composable
private fun RowScope.TableTextCell(text: String) {
    Spacer(Modifier.width(ColumnGap))
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(SECONDARY_WEIGHT),
    )
}

/** A song's length, right-aligned in a fixed width so the lengths line up down the table; blank when it is not known. */
@Composable
internal fun RowScope.DurationCell(durationMs: Long?) {
    Text(
        durationMs?.let(LocaleFormats::duration).orEmpty(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        textAlign = TextAlign.End,
        modifier = Modifier.padding(start = ColumnGap).width(DurationWidth),
    )
}
