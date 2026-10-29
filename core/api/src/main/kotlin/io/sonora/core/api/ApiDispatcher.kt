package io.sonora.core.api

import io.sonora.core.domain.model.LibraryQuery
import io.sonora.core.domain.model.QueueAction
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playback.PlaybackTempo
import io.sonora.core.domain.playlist.PlaylistResolution
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Runs [ApiCommand]s over the domain [ApiPorts]. A command that names something absent, or an index the queue does
 * not have, changes nothing and comes back as a typed failure; an exception from a port becomes [ApiError.Failed]
 * with no detail, as it may name a private path.
 */
class ApiDispatcher(private val ports: ApiPorts) {

    /** Parses [method] with [extras] and runs the command; a parse error comes back as a failed [ApiResult]. */
    suspend fun call(method: String, extras: Map<String, Any?> = emptyMap()): ApiResult =
        when (val parsed = CommandParser.parse(method, extras)) {
            is ParseResult.Parsed -> execute(parsed.command)
            is ParseResult.Rejected -> ApiResult.Failed(parsed.error)
        }

    suspend fun execute(command: ApiCommand): ApiResult = try {
        ApiResult.Ok(run(command))
    } catch (failure: ApiFailure) {
        ApiResult.Failed(failure.error)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ApiResult.Failed(ApiError.Failed)
    }

    private suspend fun run(command: ApiCommand): Map<String, Any> {
        val playback = ports.playback
        return when (command) {
            ApiCommand.Play -> none { playback.play() }
            ApiCommand.Pause -> none { playback.pause() }
            ApiCommand.Toggle -> none { playback.togglePlayPause() }
            ApiCommand.Next -> none { playback.skipToNext() }
            ApiCommand.Previous -> none { playback.skipToPrevious() }
            is ApiCommand.Seek -> none { playback.seekTo(command.positionMs) }
            is ApiCommand.Shuffle -> none { playback.setShuffle(command.enabled) }
            is ApiCommand.Repeat -> none { playback.setRepeatMode(command.mode) }
            is ApiCommand.QueueAdd -> {
                val tracks = tracksById(command.trackIds)
                playback.enqueue(tracks, command.placement.action())
                mapOf("count" to tracks.size)
            }
            is ApiCommand.QueueRemove -> none {
                val state = playback.state.value
                requireInQueue("index", command.index, state.queue.size)
                if (command.index == state.currentIndex) {
                    throw ApiFailure(ApiError.BadArgumentValue("index", "the current item cannot be removed"))
                }
                playback.removeQueueItem(command.index)
            }
            is ApiCommand.QueueMove -> none {
                val state = playback.state.value
                requireUpcoming("from", command.from, state.currentIndex, state.queue.size)
                requireUpcoming("to", command.to, state.currentIndex, state.queue.size)
                playback.moveQueueItem(command.from, command.to)
            }
            ApiCommand.QueueClearUpcoming -> none { playback.clearUpcoming() }
            is ApiCommand.QueueSkipTo -> none {
                requireInQueue("index", command.index, playback.state.value.queue.size)
                playback.skipToQueueItem(command.index)
            }
            is ApiCommand.PlayAlbum -> playAll(albumTracks(command.albumId), command.options)
            is ApiCommand.PlayArtist -> playAll(artistTracks(command.artistId), command.options)
            is ApiCommand.PlayPlaylist -> playAll(playlistTracks(command.playlistId), command.options)
            is ApiCommand.PlaySearch -> {
                val found = ports.library.search(command.query).first()
                if (found.isEmpty()) throw ApiFailure(ApiError.NothingToDo("No track matches the search"))
                playAll(found, command.options)
            }
            is ApiCommand.PlayTracks -> playAll(tracksById(command.trackIds), command.options)
            is ApiCommand.SleepStart -> none { ports.sleepTimer.start(command.option) }
            is ApiCommand.SleepExtend -> none {
                if (ports.sleepTimer.state.value == null) throw ApiFailure(ApiError.NothingToDo("No sleep timer is running"))
                ports.sleepTimer.extend(command.minutes)
            }
            ApiCommand.SleepCancel -> none { ports.sleepTimer.cancel() }
            is ApiCommand.Crossfade -> {
                ports.crossfade.setSeconds(command.seconds)
                mapOf("seconds" to command.seconds)
            }
            is ApiCommand.Speed -> {
                var applied = PlaybackTempo.DEFAULT
                ports.tempo.update { current -> current.withSpeed(command.speed.toFloat()).also { applied = it } }
                mapOf("speed" to applied.speedBasisPoints / BASIS_POINTS_PER_UNIT)
            }
            is ApiCommand.DjMode -> none { ports.djMode.setEnabled(command.enabled) }
        }
    }

    private inline fun none(block: () -> Unit): Map<String, Any> {
        block()
        return emptyMap()
    }

    private fun QueuePlacement.action() = when (this) {
        QueuePlacement.END -> QueueAction.ADD_TO_QUEUE
        QueuePlacement.NEXT -> QueueAction.PLAY_NEXT
        QueuePlacement.NOW -> QueueAction.PLAY_NOW
    }

    private fun requireInQueue(name: String, index: Int, size: Int) {
        if (index >= size) throw ApiFailure(ApiError.ArgumentOutOfRange(name, "0..${size - 1}, the rows of the queue"))
    }

    private fun requireUpcoming(name: String, index: Int, currentIndex: Int, size: Int) {
        if (index <= currentIndex || index >= size) {
            throw ApiFailure(ApiError.ArgumentOutOfRange(name, "${currentIndex + 1}..${size - 1}, the upcoming items"))
        }
    }

    /** Starts [tracks] as a new queue. */
    private fun playAll(tracks: List<Track>, options: PlayOptions): Map<String, Any> {
        if (options.startIndex >= tracks.size) {
            throw ApiFailure(ApiError.ArgumentOutOfRange("start_index", "0..${tracks.size - 1}"))
        }
        ports.playback.playQueue(tracks, options.startIndex, options.shuffle)
        return mapOf("count" to tracks.size)
    }

    /** The library tracks with [ids], in that order (repeats kept); fails on the first id the library lacks. */
    private suspend fun tracksById(ids: List<Long>): List<Track> {
        val byId = ports.library.tracks().first().associateBy { it.id }
        return ids.map { byId[it] ?: throw ApiFailure(ApiError.NotFound("track", it)) }
    }

    private suspend fun albumTracks(albumId: Long): List<Track> =
        LibraryQuery.albumOrder(ports.library.tracksForAlbum(albumId).first())
            .ifEmpty { throw ApiFailure(ApiError.NotFound("album", albumId)) }

    private suspend fun artistTracks(artistId: Long): List<Track> =
        ports.library.tracksForArtist(artistId).first()
            .ifEmpty { throw ApiFailure(ApiError.NotFound("artist", artistId)) }

    private suspend fun playlistTracks(playlistId: Long): List<Track> {
        val playlist = ports.playlists.playlist(playlistId).first()
            ?: throw ApiFailure(ApiError.NotFound("playlist", playlistId))
        val byUri = ports.library.tracks().first().associateBy { it.uri }
        return PlaylistResolution.resolve(playlist, byUri).playableTracks
            .ifEmpty { throw ApiFailure(ApiError.NothingToDo("The playlist has no playable tracks")) }
    }

    private companion object {
        const val BASIS_POINTS_PER_UNIT = 10_000.0
    }
}
