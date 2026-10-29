package io.sonora.core.domain.car

import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Artist
import io.sonora.core.domain.model.LibraryQuery
import io.sonora.core.domain.model.Playlist
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.model.TrackSort
import io.sonora.core.domain.playlist.PlaylistItem
import io.sonora.core.domain.playlist.ResolvedPlaylist
import io.sonora.core.domain.port.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

internal fun song(
    id: Long,
    title: String = "Song $id",
    album: Long = 100,
    artist: Long = 200,
    artistName: String = "Artist $artist",
    albumTitle: String = "Album $album",
    track: Int? = null,
    added: Long = 0,
) = Track(
    id = id,
    uri = "content://music/$id",
    title = title,
    artist = artistName,
    album = albumTitle,
    albumArtist = null,
    durationMs = 1_000,
    trackNumber = track,
    discNumber = null,
    year = null,
    albumId = album,
    artistId = artist,
    artworkUri = "file:///cache/artwork/$album.jpg",
    mimeType = "audio/flac",
    sizeBytes = 1,
    dateAddedSec = added,
)

internal fun playlist(id: Long, name: String, vararg tracks: Track?) = ResolvedPlaylist(
    Playlist(id, name),
    tracks.mapIndexed { i, track ->
        PlaylistItem(PlaylistEntry(i + 1L, track?.uri ?: "content://gone/$i", track?.title ?: "Gone", ""), track)
    },
)

/** A library of [tracks] (hidden items already left out, as the Room queries do); albums and artists follow them. */
internal class FakeCarLibrary(tracks: List<Track>) : LibraryRepository {
    val tracks = MutableStateFlow(tracks)

    override fun tracks(sort: TrackSort): Flow<List<Track>> = tracks.map { LibraryQuery.sort(it, sort) }
    override fun albums(): Flow<List<Album>> = tracks.map(LibraryQuery::albums)
    override fun artists(): Flow<List<Artist>> = tracks.map { LibraryQuery.artists(it) }
    override fun recentlyAddedAlbums(limit: Int): Flow<List<Album>> =
        albums().map { LibraryQuery.recentlyAdded(it, limit) }
    override fun tracksForAlbum(albumId: Long): Flow<List<Track>> =
        tracks.map { all -> LibraryQuery.albumOrder(all.filter { it.albumId == albumId }) }
    override fun albumsForArtist(artistId: Long): Flow<List<Album>> =
        albums().map { all -> all.filter { it.artistId == artistId } }
    override fun search(query: String): Flow<List<Track>> = tracks.map { LibraryQuery.search(it, query) }
}
