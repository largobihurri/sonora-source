package io.sonora.core.api

import io.sonora.core.domain.model.LibraryQuery
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.model.TrackSort
import io.sonora.core.domain.playback.SleepTimerOption
import io.sonora.core.domain.playlist.PlaylistResolution
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Answers [ApiQuery]s from the domain [ApiPorts]. Every row type is plain data without URIs or paths. An exception
 * from a port becomes [ApiError.Failed] with no detail.
 */
class ApiQueryEngine(private val ports: ApiPorts) {

    /** Parses the path and parameters and answers the query; a parse error comes back as [ApiQueryResult.Failed]. */
    suspend fun query(segments: List<String>, params: Map<String, Any?> = emptyMap()): ApiQueryResult =
        when (val parsed = QueryParser.parse(segments, params)) {
            is QueryParseResult.Parsed -> execute(parsed.query)
            is QueryParseResult.Rejected -> ApiQueryResult.Failed(parsed.error)
        }

    suspend fun execute(query: ApiQuery): ApiQueryResult = try {
        answer(query)
    } catch (failure: ApiFailure) {
        ApiQueryResult.Failed(failure.error)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ApiQueryResult.Failed(ApiError.Failed)
    }

    private suspend fun answer(query: ApiQuery): ApiQueryResult = when (query) {
        ApiQuery.State -> ApiQueryResult.State(state())
        is ApiQuery.Queue -> ApiQueryResult.Queue(queue(query.page))
        is ApiQuery.Tracks -> ApiQueryResult.Tracks(tracks(query).paged(query.page).mapRows { it.toRow() })
        is ApiQuery.Albums -> ApiQueryResult.Albums(
            ports.library.albums().first()
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }).paged(query.page)
                .mapRows { AlbumRow(it.id, it.title, it.artist, it.artistId, it.year, it.trackCount) },
        )
        is ApiQuery.Artists -> ApiQueryResult.Artists(
            ports.library.artists().first()
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).paged(query.page)
                .mapRows { ArtistRow(it.id, it.name, it.albumCount, it.trackCount) },
        )
        is ApiQuery.Playlists -> ApiQueryResult.Playlists(
            ports.playlists.playlists().first().paged(query.page)
                .mapRows { PlaylistRow(it.playlist.id, it.playlist.name, it.playlist.description, it.entries.size) },
        )
        is ApiQuery.AlbumTracks -> ApiQueryResult.Tracks(albumTracks(query.albumId).paged(query.page).mapRows { it.toRow() })
        is ApiQuery.ArtistTracks -> ApiQueryResult.Tracks(artistTracks(query.artistId).paged(query.page).mapRows { it.toRow() })
        is ApiQuery.PlaylistTracks ->
            ApiQueryResult.Tracks(playlistTracks(query.playlistId).paged(query.page).mapRows { it.toRow() })
    }

    private suspend fun state(): StateRow {
        val playback = ports.playback.state.value
        val tempo = ports.tempo.tempo.first()
        return StateRow(
            apiVersion = API_VERSION,
            isPlaying = playback.isPlaying,
            positionMs = playback.positionMs,
            durationMs = playback.durationMs,
            shuffle = playback.shuffleEnabled,
            repeat = when (playback.repeatMode) {
                RepeatMode.OFF -> "off"
                RepeatMode.ALL -> "all"
                RepeatMode.ONE -> "one"
            },
            currentIndex = playback.currentIndex,
            queueSize = playback.queue.size,
            currentTrack = playback.currentTrack?.toRow(),
            sleepTimer = ports.sleepTimer.state.value?.let { SleepTimerRow(it.option.mode(), it.remainingMs) },
            crossfadeSeconds = ports.crossfade.seconds.first(),
            speed = tempo.speedBasisPoints / BASIS_POINTS_PER_UNIT,
            djMode = ports.djMode.enabled.value,
        )
    }

    private fun SleepTimerOption.mode() = when (this) {
        is SleepTimerOption.Minutes -> "minutes"
        SleepTimerOption.EndOfSong -> "end_of_song"
        SleepTimerOption.EndOfAlbum -> "end_of_album"
    }

    private fun queue(request: PageRequest): Page<QueueRow> {
        val playback = ports.playback.state.value
        val rows = playback.queue.mapIndexed { index, item ->
            QueueRow(index, item.id, isCurrent = index == playback.currentIndex, track = item.track.toRow())
        }
        return rows.paged(request)
    }

    private suspend fun tracks(query: ApiQuery.Tracks): List<Track> =
        if (query.text == null) {
            ports.library.tracks(TrackSort.TITLE).first()
        } else {
            LibraryQuery.sort(ports.library.search(query.text).first(), TrackSort.TITLE)
        }

    private suspend fun albumTracks(albumId: Long): List<Track> {
        val tracks = LibraryQuery.albumOrder(ports.library.tracksForAlbum(albumId).first())
        if (tracks.isEmpty() && ports.library.albums().first().none { it.id == albumId }) {
            throw ApiFailure(ApiError.NotFound("album", albumId))
        }
        return tracks
    }

    private suspend fun artistTracks(artistId: Long): List<Track> {
        val tracks = ports.library.tracksForArtist(artistId).first()
        if (tracks.isEmpty() && ports.library.artists().first().none { it.id == artistId }) {
            throw ApiFailure(ApiError.NotFound("artist", artistId))
        }
        return tracks
    }

    private suspend fun playlistTracks(playlistId: Long): List<Track> {
        val playlist = ports.playlists.playlist(playlistId).first() ?: throw ApiFailure(ApiError.NotFound("playlist", playlistId))
        val byUri = ports.library.tracks().first().associateBy { it.uri }
        return PlaylistResolution.resolve(playlist, byUri).playableTracks
    }

    private fun <T> List<T>.paged(request: PageRequest): Page<T> =
        Page(drop(request.offset).take(request.limit), size, request.limit, request.offset)

    private fun <T, R> Page<T>.mapRows(transform: (T) -> R) = Page(rows.map(transform), total, limit, offset)

    private fun Track.toRow() = TrackRow(
        id = id, title = title, artist = artist, album = album, albumId = albumId, artistId = artistId,
        durationMs = durationMs, trackNumber = trackNumber, discNumber = discNumber, year = year,
    )

    private companion object {
        const val BASIS_POINTS_PER_UNIT = 10_000.0
    }
}
