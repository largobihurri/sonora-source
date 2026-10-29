package io.sonora.core.domain.car

import io.sonora.core.domain.library.marks.LibraryMarksRepository
import io.sonora.core.domain.library.marks.MarkKind
import io.sonora.core.domain.model.Album
import io.sonora.core.domain.model.Artist
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.model.TrackSort
import io.sonora.core.domain.playlist.ResolvedPlaylist
import io.sonora.core.domain.port.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first

/**
 * The library as Android Auto browses it: four tabs (Playlists, Albums, Artists, Songs), folders down to songs, and
 * the queue a tapped song plays. Hidden items are already left out of [library]'s lists; playlists removed from
 * Sonora are left out here. Converted music outside the library, folders and editing never show in the car.
 *
 * Every call reads the current library once; an id that names nothing any more gives `null`, never an exception.
 */
class CarLibraryTree(
    private val library: LibraryRepository,
    private val marks: LibraryMarksRepository,
    private val playlists: () -> Flow<List<ResolvedPlaylist>>,
    private val bucketThreshold: Int = BUCKET_THRESHOLD,
) {
    /** Emits each time what the car lists may have changed (songs, playlists, favourites, removed items). */
    fun changes(): Flow<Unit> = combine(
        library.tracks(TrackSort.TITLE),
        playlists(),
        marks.hidden(),
        marks.favourites(MarkKind.SONG),
        marks.favourites(MarkKind.ALBUM),
        marks.favourites(MarkKind.PLAYLIST),
    ) { }.drop(1)

    /** The children of [id]; `null` when [id] is not a folder or names nothing any more. */
    suspend fun children(id: CarMediaId): List<CarNode>? = when (id) {
        CarMediaId.Root -> CarTab.entries.map(::tabNode)
        is CarMediaId.Tab -> tabChildren(id.tab)
        is CarMediaId.Bucket -> bucketChildren(id)
        CarMediaId.Favourites -> favourites()
        CarMediaId.RecentlyAdded -> library.recentlyAddedAlbums(RECENT_ALBUMS).first().map(::albumNode)
        is CarMediaId.Playlist -> visiblePlaylist(id.id)?.let { songNodes(it.playableTracks, id) }
        is CarMediaId.Album -> library.tracksForAlbum(id.id).first().takeIf { it.isNotEmpty() }?.let { songNodes(it, id) }
        is CarMediaId.Artist -> artist(id.id)?.let {
            listOf(artistSongsNode(id.id)) + library.albumsForArtist(id.id).first().map(::albumNode)
        }
        is CarMediaId.ArtistSongs -> artist(id.id)?.let { songNodes(library.tracksForArtist(id.id).first(), id) }
        is CarMediaId.Search -> searchNodes(id)
        is CarMediaId.Song -> null
    }

    /** The node [id] names, as its parent lists it; `null` when it names nothing any more. */
    suspend fun item(id: CarMediaId): CarNode? = when (id) {
        CarMediaId.Root -> CarNode(id, CarTitle.Text(""))
        is CarMediaId.Tab -> tabNode(id.tab)
        is CarMediaId.Bucket -> bucketNode(id)
        CarMediaId.Favourites -> CarNode(id, CarTitle.Label(CarLabel.FAVOURITES))
        CarMediaId.RecentlyAdded -> CarNode(id, CarTitle.Label(CarLabel.RECENTLY_ADDED))
        is CarMediaId.Playlist -> visiblePlaylist(id.id)?.let(::playlistNode)
        is CarMediaId.Album -> library.albums().first().firstOrNull { it.id == id.id }?.let(::albumNode)
        is CarMediaId.Artist -> artist(id.id)?.let(::artistNode)
        is CarMediaId.ArtistSongs -> artist(id.id)?.let { artistSongsNode(id.id) }
        is CarMediaId.Search -> CarNode(id, CarTitle.Text(id.query))
        is CarMediaId.Song -> queue(id)?.let { songNode(it.tracks[it.startIndex], it.startIndex, id.context) }
    }

    /** Whether there is anything to play; [isReadable] tells songs whose folder grant is gone. */
    suspend fun status(isReadable: (Track) -> Boolean): CarLibraryStatus {
        val songs = allSongs()
        return when {
            songs.isEmpty() -> CarLibraryStatus.EMPTY
            songs.none(isReadable) -> CarLibraryStatus.NO_ACCESS
            else -> CarLibraryStatus.READY
        }
    }

    /** What a voice request plays (see [CarVoicePlan]). */
    suspend fun voice(request: CarVoiceRequest): CarVoicePlan {
        val queue = when (request.focus) {
            CarVoiceFocus.ARTIST -> search(request.artist ?: request.query).artists.firstOrNull()?.let { artistSongs(it.id) }
            CarVoiceFocus.ALBUM -> search(request.album ?: request.query).albums.firstOrNull()?.let { albumSongs(it.id) }
            CarVoiceFocus.SONG -> search(request.title ?: request.query).songs
            null -> if (request.query.isBlank()) return CarVoicePlan.Anything(allSongs()) else closest(request.query)
        }
        return queue?.takeIf { it.isNotEmpty() }?.let { CarVoicePlan.Play(CarQueue(it, 0)) } ?: CarVoicePlan.NoMatch
    }

    /** Songs, albums and artists matching [query] ([CarSearch]). */
    suspend fun search(query: String): CarSearchResults =
        CarSearch.rank(query, allSongs(), library.albums().first(), library.artists().first())

    /** An open query plays the best-named artist, album or song, in that order on a tie. */
    private suspend fun closest(query: String): List<Track>? {
        val found = search(query)
        val q = CarSearch.normalize(query)
        val artist = found.artists.firstOrNull()?.let { CarSearch.score(it.name, q) to it }
        val album = found.albums.firstOrNull()?.let { CarSearch.score(it.title, q) to it }
        val song = found.songs.firstOrNull()?.let { CarSearch.score(it.title, q) }
        val best = listOfNotNull(artist?.first, album?.first, song).minOrNull() ?: return found.songs.ifEmpty { null }
        return when (best) {
            artist?.first -> artistSongs(artist.second.id)
            album?.first -> albumSongs(album.second.id)
            else -> found.songs
        }
    }

    private suspend fun artistSongs(artistId: Long) = library.tracksForArtist(artistId).first()

    private suspend fun albumSongs(albumId: Long) = library.tracksForAlbum(albumId).first()

    /** What [song] plays: its context's songs, from the tapped entry; `null` when the song is no longer there. */
    suspend fun queue(song: CarMediaId.Song): CarQueue? {
        val tracks = songsOf(song.context) ?: return null
        val start = song.position.takeIf { tracks.getOrNull(it)?.id == song.trackId }
            ?: tracks.indexOfFirst { it.id == song.trackId }.takeIf { it >= 0 }
            ?: return null
        return CarQueue(tracks, start)
    }

    /** The songs of a song list ([CarMediaId.isSongContext]); `null` when it names nothing any more. */
    private suspend fun songsOf(context: CarMediaId): List<Track>? = when (context) {
        is CarMediaId.Tab -> if (context.tab == CarTab.SONGS) allSongs() else null
        CarMediaId.Favourites -> favouriteSongs()
        is CarMediaId.Playlist -> visiblePlaylist(context.id)?.playableTracks
        is CarMediaId.Album -> library.tracksForAlbum(context.id).first()
        is CarMediaId.ArtistSongs -> library.tracksForArtist(context.id).first()
        is CarMediaId.Search -> search(context.query).songs
        else -> null
    }

    /** Search results: matching songs (playing the matches), then albums, then artists. */
    private suspend fun searchNodes(id: CarMediaId.Search): List<CarNode> {
        val found = search(id.query)
        return songNodes(found.songs, id).map { it.copy(group = CarGroup.SONGS) } +
            found.albums.map { albumNode(it).copy(group = CarGroup.ALBUMS) } +
            found.artists.map { artistNode(it).copy(group = CarGroup.ARTISTS) }
    }

    private suspend fun tabChildren(tab: CarTab): List<CarNode> = when (tab) {
        CarTab.PLAYLISTS -> listOf(
            CarNode(CarMediaId.Favourites, CarTitle.Label(CarLabel.FAVOURITES)),
            CarNode(CarMediaId.RecentlyAdded, CarTitle.Label(CarLabel.RECENTLY_ADDED)),
        ) + visiblePlaylists().map(::playlistNode)
        CarTab.ALBUMS -> library.albums().first().let { albums ->
            bucketedOr(tab, albums.map { it.title }) { albums.map(::albumNode) }
        }
        CarTab.ARTISTS -> library.artists().first().let { artists ->
            bucketedOr(tab, artists.map { it.name }) { artists.map(::artistNode) }
        }
        CarTab.SONGS -> allSongs().let { songs ->
            bucketedOr(tab, songs.map { it.title }) { songNodes(songs, CarMediaId.Tab(CarTab.SONGS)) }
        }
    }

    /** Letters when [names] are too many for one list, else the whole list. */
    private inline fun bucketedOr(tab: CarTab, names: List<String>, whole: () -> List<CarNode>): List<CarNode> =
        if (names.size > bucketThreshold) {
            CarBuckets.keys(names).map { bucketNode(CarMediaId.Bucket(tab, it)) }
        } else {
            whole()
        }

    private suspend fun bucketChildren(bucket: CarMediaId.Bucket): List<CarNode> = when (bucket.tab) {
        CarTab.ALBUMS -> library.albums().first().filter { CarBuckets.keyOf(it.title) == bucket.key }.map(::albumNode)
        CarTab.ARTISTS -> library.artists().first().filter { CarBuckets.keyOf(it.name) == bucket.key }.map(::artistNode)
        // A song in a letter plays the whole song list from itself, so it keeps its position there.
        CarTab.SONGS -> songNodes(allSongs(), CarMediaId.Tab(CarTab.SONGS))
            .filter { CarBuckets.keyOf(it.track!!.title) == bucket.key }
        CarTab.PLAYLISTS -> emptyList()
    }

    private suspend fun favourites(): List<CarNode> {
        val albums = library.albums().first().associateBy { it.id.toString() }
        val playlists = visiblePlaylists().associateBy { it.playlist.id.toString() }
        return songNodes(favouriteSongs(), CarMediaId.Favourites).map { it.copy(group = CarGroup.SONGS) } +
            marks.favourites(MarkKind.ALBUM).first().mapNotNull(albums::get)
                .map { albumNode(it).copy(group = CarGroup.ALBUMS) } +
            marks.favourites(MarkKind.PLAYLIST).first().mapNotNull(playlists::get)
                .map { playlistNode(it).copy(group = CarGroup.PLAYLISTS) }
    }

    /** Favourite songs still in the library, the most recently added favourite first. */
    private suspend fun favouriteSongs(): List<Track> {
        val byUri = allSongs().associateBy { it.uri }
        return marks.favourites(MarkKind.SONG).first().mapNotNull(byUri::get)
    }

    private suspend fun allSongs(): List<Track> = library.tracks(TrackSort.TITLE).first()

    private suspend fun artist(id: Long): Artist? = library.artists().first().firstOrNull { it.id == id }

    /** Playlists not removed from Sonora, by name. */
    private suspend fun visiblePlaylists(): List<ResolvedPlaylist> {
        val hidden = marks.hidden().first().filter { it.ref.kind == MarkKind.PLAYLIST }.mapTo(HashSet()) { it.ref.key }
        return playlists().first().filterNot { it.playlist.id.toString() in hidden }
    }

    private suspend fun visiblePlaylist(id: Long): ResolvedPlaylist? = visiblePlaylists().firstOrNull { it.playlist.id == id }

    private fun tabNode(tab: CarTab) = CarNode(CarMediaId.Tab(tab), CarTitle.Label(tab.label))

    private fun bucketNode(bucket: CarMediaId.Bucket) = CarNode(bucket, CarTitle.Text(bucket.key))

    private fun artistSongsNode(artistId: Long) = CarNode(CarMediaId.ArtistSongs(artistId), CarTitle.Label(CarLabel.ALL_SONGS))

    private fun playlistNode(playlist: ResolvedPlaylist) = CarNode(
        id = CarMediaId.Playlist(playlist.playlist.id),
        title = CarTitle.Text(playlist.playlist.name),
        artworkUri = playlist.coverArtworkUris.firstOrNull(),
    )

    private fun albumNode(album: Album) = CarNode(
        id = CarMediaId.Album(album.id),
        title = CarTitle.Text(album.title),
        subtitle = album.artist,
        artworkUri = album.artworkUri,
    )

    private fun artistNode(artist: Artist) = CarNode(
        id = CarMediaId.Artist(artist.id),
        title = CarTitle.Text(artist.name),
        artworkUri = artist.artworkUris.firstOrNull(),
    )

    private fun songNodes(tracks: List<Track>, context: CarMediaId) =
        tracks.mapIndexed { position, track -> songNode(track, position, context) }

    private fun songNode(track: Track, position: Int, context: CarMediaId) = CarNode(
        id = CarMediaId.Song(track.id, position, context),
        title = CarTitle.Text(track.title),
        subtitle = track.artist,
        artworkUri = track.artworkUri,
        track = track,
    )

    private val CarTab.label: CarLabel
        get() = when (this) {
            CarTab.PLAYLISTS -> CarLabel.PLAYLISTS
            CarTab.ALBUMS -> CarLabel.ALBUMS
            CarTab.ARTISTS -> CarLabel.ARTISTS
            CarTab.SONGS -> CarLabel.SONGS
        }

    companion object {
        /** Android Auto has no paging: longer tabs are split into letters. */
        const val BUCKET_THRESHOLD = 500

        /** Albums under Playlists > Recently Added. */
        const val RECENT_ALBUMS = 24
    }
}
