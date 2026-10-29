package io.sonora.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.sonora.core.designsystem.component.BottomChromePadding
import io.sonora.core.designsystem.component.InsetDivider
import io.sonora.core.designsystem.text.rememberStrings
import io.sonora.core.domain.library.AlbumSummary
import io.sonora.core.domain.library.marks.Pinned
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Track
import io.sonora.feature.library.ui.AlbumCardRow
import io.sonora.feature.library.ui.AlbumDetailText
import io.sonora.feature.library.ui.AlbumSectionHeader
import io.sonora.feature.library.ui.DetailHeaderLayout
import io.sonora.feature.library.ui.PageColumn
import io.sonora.feature.library.ui.SideCover
import io.sonora.feature.library.ui.SideHeader
import io.sonora.feature.library.ui.SideMetadata
import io.sonora.feature.library.ui.SideSubtitle
import io.sonora.feature.library.ui.SideTitle
import io.sonora.feature.library.ui.albumGenre
import io.sonora.feature.library.ui.pageRowWidth
import io.sonora.feature.library.ui.rememberDetailHeaderLayout
import io.sonora.feature.library.ui.PageHeader
import io.sonora.feature.library.ui.PlayButtonsRow
import io.sonora.feature.library.ui.SectionTitle
import io.sonora.feature.library.ui.SingleSongRow
import io.sonora.feature.library.ui.SongRow
import io.sonora.feature.library.ui.PAGE_HEADER_KEY
import io.sonora.feature.library.ui.PageSelection
import io.sonora.feature.library.ui.keyedByUri
import io.sonora.feature.library.ui.dragSelectRow
import io.sonora.feature.library.ui.rememberSongSelection
import io.sonora.feature.library.ui.TintedPage

/**
 * iOS 26 Music album page tinted with the artwork's colour: header (artwork, title, artist opening the artist page,
 * format · year · quality), Shuffle / Play / Add to Playlist, the songs (tap plays from that song in the order
 * shown, long-press opens the song menu), the footer (release date, length, copyright) and the artist's other
 * albums on a darker band.
 */
@Composable
internal fun AlbumDetailPage(
    album: Album,
    pinnedTracks: Pinned<Track>,
    moreAlbums: List<Album>,
    onBack: () -> Unit,
    onMore: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onShuffle: (List<Track>) -> Unit,
    onAddToPlaylist: () -> Unit,
    onSongMenu: (Track) -> Unit,
    onOpenArtist: () -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onAlbumMenu: (Album) -> Unit,
) {
    val strings = rememberStrings()
    val tracks = pinnedTracks.items
    val summary = remember(tracks, album.year) { AlbumSummary.of(tracks, album.year) }
    val metadata = remember(summary, strings) { AlbumDetailText.metadataLine(summary, strings) }
    val footer = remember(summary, strings) { AlbumDetailText.footer(summary, strings) }
    val genreYear = remember(tracks, summary) { AlbumDetailText.genreYearLine(albumGenre(tracks), summary.year) }
    val technical = remember(summary, strings) { AlbumDetailText.technicalLine(summary, strings) }
    val selection = rememberSongSelection()
    val shown = remember(tracks) { tracks.keyedByUri() }
    TintedPage(album.artworkUri, onBack, onMore, select = selection?.let { PageSelection(it, shown, album.title) }) { colors ->
        item(key = PAGE_HEADER_KEY) {
            PageColumn {
                when (val layout = rememberDetailHeaderLayout()) {
                    DetailHeaderLayout.Centred -> {
                        PageHeader(album.artworkUri, album.title, album.artist, metadata, colors, onSubtitle = onOpenArtist)
                        PlayButtonsRow(
                            colors,
                            enabled = tracks.isNotEmpty(),
                            onShuffle = { onShuffle(tracks) },
                            onPlay = { onPlay(tracks, 0) },
                            onAdd = onAddToPlaylist,
                        )
                    }
                    is DetailHeaderLayout.Beside -> SideHeader(
                        layout.coverDp,
                        cover = { SideCover(album.artworkUri, layout.coverDp, album.title) },
                    ) {
                        SideTitle(album.title)
                        SideSubtitle(album.artist, onOpenArtist)
                        SideMetadata(genreYear, technical, colors)
                        PlayButtonsRow(
                            colors,
                            enabled = tracks.isNotEmpty(),
                            onShuffle = { onShuffle(tracks) },
                            onPlay = { onPlay(tracks, 0) },
                            onAdd = onAddToPlaylist,
                            beside = true,
                        )
                    }
                }
                InsetDivider(start = 20.dp)
            }
        }
        itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
            SongRow(
                track,
                modifier = Modifier.pageRowWidth().dragSelectRow(selection, track.uri),
                onClick = selection?.click(track.uri) { onPlay(tracks, index) } ?: { onPlay(tracks, index) },
                onLongClick = selection?.longClick(track.uri) { onSongMenu(track) } ?: { onSongMenu(track) },
                leadingNumber = track.trackNumber ?: (index + 1),
                pinned = pinnedTracks.isPinned(track.uri),
                selected = selection?.mark(track.uri),
            )
        }
        item(key = "footer") {
            PageColumn {
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp)) {
                    for (line in footer) {
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = colors.secondaryText)
                    }
                }
            }
        }
        item(key = "more") {
            if (moreAlbums.isEmpty()) {
                Spacer(Modifier.height(BottomChromePadding))
            } else {
                Column(Modifier.fillMaxWidth().background(colors.lowerBand).padding(top = 20.dp)) {
                    PageColumn { SectionTitle(stringResource(R.string.album_more_by, album.artist), colors, onClick = onOpenArtist) }
                    AlbumCardRow(moreAlbums, colors, onOpenAlbum, onAlbumMenu)
                    Spacer(Modifier.height(BottomChromePadding + 16.dp))
                }
            }
        }
    }
}

/**
 * The artist page with the same tint, from the newest album's cover: cover, large name, Shuffle / Play / Add to
 * Playlist for the artist's songs, the albums (newest first) and the songs by release ([ArtistSections]): the Singles,
 * then each album under a header that opens it. Tapping a song plays the songs in the order shown from it.
 */
@Composable
internal fun ArtistDetailPage(
    name: String,
    albums: List<Album>,
    pinnedTracks: Pinned<Track>,
    onBack: () -> Unit,
    onMore: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onShuffle: (List<Track>) -> Unit,
    onAddToPlaylist: () -> Unit,
    onSongMenu: (Track) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onAlbumMenu: (Album) -> Unit,
) {
    val cover = albums.firstOrNull { it.artworkUri != null }?.artworkUri
    val tracks = pinnedTracks.items
    val songs = remember(albums, pinnedTracks) { ArtistSections.of(albums, pinnedTracks) }
    val selection = rememberSongSelection()
    // In the order shown: the Singles, then each album's songs, then the others.
    val shown = remember(songs) { songs.queue.keyedByUri() }
    TintedPage(cover, onBack, onMore, select = selection?.let { PageSelection(it, shown, name) }) { colors ->
        item(key = PAGE_HEADER_KEY) {
            PageColumn {
                when (val layout = rememberDetailHeaderLayout()) {
                    DetailHeaderLayout.Centred -> {
                        PageHeader(cover, name, subtitle = null, metadata = null, colors = colors, onSubtitle = null, titleStyleLarge = true)
                        PlayButtonsRow(
                            colors,
                            enabled = tracks.isNotEmpty(),
                            onShuffle = { onShuffle(tracks) },
                            onPlay = { onPlay(songs.queue, 0) },
                            onAdd = onAddToPlaylist,
                        )
                    }
                    is DetailHeaderLayout.Beside -> SideHeader(layout.coverDp, cover = { SideCover(cover, layout.coverDp, name) }) {
                        SideTitle(name, large = true)
                        PlayButtonsRow(
                            colors,
                            enabled = tracks.isNotEmpty(),
                            onShuffle = { onShuffle(tracks) },
                            onPlay = { onPlay(songs.queue, 0) },
                            onAdd = onAddToPlaylist,
                            beside = true,
                        )
                    }
                }
            }
        }
        if (albums.isNotEmpty()) {
            item(key = "albums") {
                PageColumn { SectionTitle(stringResource(R.string.library_albums), colors) }
                AlbumCardRow(albums, colors, onOpenAlbum, onAlbumMenu)
                Spacer(Modifier.height(20.dp))
            }
        }
        songs.sections.forEachIndexed { sectionIndex, section ->
            val play = { row: Int -> onPlay(songs.queue, songs.queueIndex(sectionIndex, row)) }
            when (section) {
                is ArtistSection.Singles -> {
                    item(key = "singlesTitle") { PageColumn { SectionTitle(stringResource(R.string.artist_singles), colors) } }
                    itemsIndexed(section.entries, key = { _, e -> e.track.id }) { row, entry ->
                        val track = entry.track
                        SingleSongRow(
                            track.title, track.artworkUri, entry.year,
                            pinned = pinnedTracks.isPinned(track.uri),
                            onClick = selection?.click(track.uri) { play(row) } ?: { play(row) },
                            onLongClick = selection?.longClick(track.uri) { onSongMenu(track) } ?: { onSongMenu(track) },
                            selected = selection?.mark(track.uri),
                            modifier = Modifier.pageRowWidth().dragSelectRow(selection, track.uri),
                        )
                    }
                }
                is ArtistSection.AlbumSongs -> {
                    val album = section.album
                    item(key = "album:${album.id}") {
                        PageColumn { AlbumSectionHeader(album, colors, onOpen = { onOpenAlbum(album) }, onMenu = { onAlbumMenu(album) }) }
                    }
                    itemsIndexed(section.tracks, key = { _, t -> t.id }) { row, track ->
                        SongRow(
                            track,
                            modifier = Modifier.pageRowWidth().dragSelectRow(selection, track.uri),
                            onClick = selection?.click(track.uri) { play(row) } ?: { play(row) },
                            onLongClick = selection?.longClick(track.uri) { onSongMenu(track) } ?: { onSongMenu(track) },
                            leadingNumber = track.trackNumber ?: (row + 1),
                            pinned = pinnedTracks.isPinned(track.uri),
                            selected = selection?.mark(track.uri),
                        )
                    }
                }
                is ArtistSection.OtherSongs -> {
                    item(key = "otherTitle") { PageColumn { SectionTitle(stringResource(R.string.artist_other_songs), colors) } }
                    itemsIndexed(section.tracks, key = { _, t -> t.id }) { row, track ->
                        SongRow(
                            track,
                            modifier = Modifier.pageRowWidth().dragSelectRow(selection, track.uri),
                            onClick = selection?.click(track.uri) { play(row) } ?: { play(row) },
                            onLongClick = selection?.longClick(track.uri) { onSongMenu(track) } ?: { onSongMenu(track) },
                            pinned = pinnedTracks.isPinned(track.uri),
                            selected = selection?.mark(track.uri),
                        )
                    }
                }
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(BottomChromePadding)) }
    }
}
