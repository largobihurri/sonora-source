package io.sonora.player.wear.kora

import eus.bihurri.kora.contract.media.MediaLibrary
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlaylistSummary
import eus.bihurri.kora.contract.media.PlaylistTracksRequest
import eus.bihurri.kora.contract.media.SearchRequest
import eus.bihurri.kora.contract.media.TrackSummary
import eus.bihurri.kora.core.ProviderBuilder
import io.sonora.player.wear.library.WatchTrack

/**
 * `media.library` over the songs stored on the watch. The watch keeps no playlists, so its library is one playlist,
 * [ALL_SONGS_ID], named [allSongsName], holding every song in the list's order. [songs] is read on each call and not
 * at registration: the node is installed at every process start, calls are rare.
 */
internal class WatchLibraryHandlers(
    private val songs: suspend () -> List<WatchTrack>,
    private val allSongsName: () -> String,
) {
    /** Registers every operation of the capability (the node rejects a partial provider at install). */
    fun register(builder: ProviderBuilder<MediaLibrary>) = with(builder) {
        on(MediaLibrary.ListPlaylists) { page -> listPlaylists(page) }
        on(MediaLibrary.GetPlaylistTracks) { request -> playlistTracks(request) }
        on(MediaLibrary.SearchTracks) { request -> searchTracks(request) }
    }

    suspend fun listPlaylists(request: PageRequest): Page<PlaylistSummary> {
        val all = listOf(PlaylistSummary(id = ALL_SONGS_ID, name = allSongsName(), trackCount = songs().size))
        return pageOf(all, request)
    }

    suspend fun playlistTracks(request: PlaylistTracksRequest): Page<TrackSummary> {
        if (request.playlistId != ALL_SONGS_ID) throw invalidPayload("Unknown playlist")
        return summaries(pageOf(songs(), request.page))
    }

    suspend fun searchTracks(request: SearchRequest): Page<TrackSummary> {
        if (request.query.length > MAX_QUERY_LENGTH) throw invalidPayload("The search text is too long")
        return summaries(pageOf(searchTracks(songs(), request.query), request.page))
    }

    private fun summaries(page: Page<WatchTrack>) = Page(page.items.map(::trackSummary), page.total, page.offset)

    private companion object {
        const val MAX_QUERY_LENGTH = 200
    }
}
