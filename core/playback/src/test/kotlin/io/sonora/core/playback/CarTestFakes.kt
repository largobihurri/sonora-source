package io.sonora.core.playback

import io.sonora.core.domain.library.marks.HiddenItem
import io.sonora.core.domain.library.marks.ItemRef
import io.sonora.core.domain.library.marks.LibraryMarksRepository
import io.sonora.core.domain.library.marks.MarkKind
import io.sonora.core.domain.library.marks.PinContext
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Artist
import io.sonora.core.domain.model.LibraryQuery
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.model.TrackSort
import io.sonora.core.domain.port.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal fun carTrack(id: Long, title: String = "T$id", album: Long = 1, albumTitle: String = "Album $album", track: Int? = null) =
    Track(
        id = id, uri = "content://io.sonora.test/doc/$id", title = title, artist = "Artist", album = albumTitle,
        albumArtist = null, durationMs = 200_000, trackNumber = track, discNumber = null, year = null, albumId = album,
        artistId = 1, artworkUri = null, mimeType = "audio/flac", sizeBytes = 1234,
    )

/** A library of [tracks]; albums and artists follow them. */
internal class FakeCarLibrary(tracks: List<Track>) : LibraryRepository {
    val tracks = MutableStateFlow(tracks)

    override fun tracks(sort: TrackSort): Flow<List<Track>> = tracks.map { LibraryQuery.sort(it, sort) }
    override fun albums(): Flow<List<Album>> = tracks.map(LibraryQuery::albums)
    override fun artists(): Flow<List<Artist>> = tracks.map { LibraryQuery.artists(it) }
    override fun recentlyAddedAlbums(limit: Int): Flow<List<Album>> = albums().map { LibraryQuery.recentlyAdded(it, limit) }
    override fun tracksForAlbum(albumId: Long): Flow<List<Track>> =
        tracks.map { all -> LibraryQuery.albumOrder(all.filter { it.albumId == albumId }) }
    override fun albumsForArtist(artistId: Long): Flow<List<Album>> = albums().map { all -> all.filter { it.artistId == artistId } }
    override fun search(query: String): Flow<List<Track>> = tracks.map { LibraryQuery.search(it, query) }
}

/** Favourites only; nothing is hidden or pinned. */
internal class FakeCarMarks : LibraryMarksRepository {
    val favourites = MutableStateFlow<List<ItemRef>>(emptyList())

    override fun favourites(kind: MarkKind): Flow<List<String>> =
        favourites.map { all -> all.filter { it.kind == kind }.map { it.key } }
    override suspend fun setFavourite(ref: ItemRef, favourite: Boolean) {
        favourites.value = favourites.value - ref + listOfNotNull(ref.takeIf { favourite })
    }
    override fun pins(context: PinContext): Flow<List<String>> = flowOf(emptyList())
    override suspend fun setPinned(context: PinContext, ref: ItemRef, pinned: Boolean) = Unit
    override fun hidden(): Flow<List<HiddenItem>> = flowOf(emptyList())
    override suspend fun hide(item: HiddenItem) = Unit
    override suspend fun restore(ref: ItemRef) = Unit
    override suspend fun forgetSongs(uris: Collection<String>) = Unit
}
