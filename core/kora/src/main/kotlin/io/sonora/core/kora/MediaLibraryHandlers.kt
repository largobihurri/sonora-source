package io.sonora.core.kora

import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlaylistSummary
import eus.bihurri.kora.contract.media.PlaylistTracksRequest
import eus.bihurri.kora.contract.media.SearchRequest
import eus.bihurri.kora.contract.media.TrackSummary
import eus.bihurri.kora.core.ProviderBuilder
import io.sonora.core.domain.model.LibraryQuery
import io.sonora.core.domain.model.TrackSort
import io.sonora.core.domain.playlist.PlaylistResolution
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.port.PlaylistRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * `media.library` over Sonora's library and playlists. The ports are taken lazily: nothing heavy is built until the
 * first call that needs it (the node is installed at every process start, calls are rare).
 */
internal class MediaLibraryHandlers(
    private val library: () -> LibraryRepository,
    private val playlists: () -> PlaylistRepository,
) {
    @Inject
    constructor(library: dagger.Lazy<LibraryRepository>, playlists: dagger.Lazy<PlaylistRepository>) :
        this({ library.get() }, { playlists.get() })

    /** Registers every operation of the capability (the node rejects a partial provider at install). */
    fun register(builder: ProviderBuilder<MediaLibrary>) = with(builder) {
        on(MediaLibrary.ListPlaylists) { page -> listPlaylists(page) }
        on(MediaLibrary.GetPlaylistTracks) { request -> playlistTracks(request) }
        on(MediaLibrary.SearchTracks) { request -> searchTracks(request) }
    }

    suspend fun listPlaylists(request: PageRequest): Page<PlaylistSummary> {
        val stored = pageOf(playlists().playlists().first(), request)
        if (stored.items.isEmpty()) return Page(emptyList(), stored.total, stored.offset)
        val byUri = library().byUri()
        return Page(
            stored.items.map { playlistSummary(PlaylistResolution.resolve(it, byUri)) },
            stored.total,
            stored.offset,
        )
    }

    suspend fun playlistTracks(request: PlaylistTracksRequest): Page<TrackSummary> {
        val tracks = pageOf(playableTracksOf(request.playlistId, library(), playlists()), request.page)
        return Page(tracks.items.map(::trackSummary), tracks.total, tracks.offset)
    }

    /** As the old external API: the library search (title, artist, album), by title. */
    suspend fun searchTracks(request: SearchRequest): Page<TrackSummary> {
        if (request.query.length > MAX_QUERY_LENGTH) throw invalidPayload("The search text is too long")
        val found = LibraryQuery.sort(library().search(request.query).first(), TrackSort.TITLE)
        val tracks = pageOf(found, request.page)
        return Page(tracks.items.map(::trackSummary), tracks.total, tracks.offset)
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 200
    }
}
