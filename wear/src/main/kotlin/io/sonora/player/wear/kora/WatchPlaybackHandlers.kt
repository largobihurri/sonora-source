package io.sonora.player.wear.kora

import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.PlayPlaylistRequest
import eus.bihurri.kora.contract.media.PlayTracksRequest
import eus.bihurri.kora.contract.media.SeekRequest
import eus.bihurri.kora.core.ProviderBuilder
import io.sonora.player.wear.library.TrackQueue
import io.sonora.player.wear.library.WatchTrack
import kotlin.random.Random
import eus.bihurri.kora.contract.media.PlaybackState as KoraPlaybackState

/**
 * `media.playback` over the watch's own player ([WatchPlayerPort]). It never speaks for the phone's player: when the
 * watch is a remote for the phone, this node still reports and drives the watch's player only.
 */
internal class WatchPlaybackHandlers(
    private val player: WatchPlayerPort,
    private val songs: suspend () -> List<WatchTrack>,
    private val clock: () -> Long,
    private val random: Random = Random.Default,
) {
    /** Registers every operation and event of the capability (the node rejects a partial provider at install). */
    fun register(builder: ProviderBuilder<MediaPlayback>) = with(builder) {
        on(MediaPlayback.Play) { player.play() }
        on(MediaPlayback.Pause) { player.pause() }
        on(MediaPlayback.Toggle) { player.togglePlayPause() }
        on(MediaPlayback.Next) { player.next() }
        on(MediaPlayback.Previous) { player.previous() }
        on(MediaPlayback.SeekTo) { request -> seekTo(request) }
        on(MediaPlayback.PlayPlaylist) { request -> playPlaylist(request) }
        on(MediaPlayback.PlayTracks) { request -> playTracks(request) }
        on(MediaPlayback.GetState) { state() }
        // One observation per subscriber, opened when it subscribes and closed when it leaves.
        emits(MediaPlayback.PlaybackStateChanged) { player.states().playbackStateChanges(clock) }
        emits(MediaPlayback.NowPlayingChanged) { player.states().nowPlayingChanges(clock) }
    }

    suspend fun seekTo(request: SeekRequest) {
        if (request.positionMs < 0) throw invalidPayload("positionMs must not be negative")
        player.seekTo(request.positionMs)
    }

    suspend fun playPlaylist(request: PlayPlaylistRequest) {
        if (request.playlistId != ALL_SONGS_ID) throw invalidPayload("Unknown playlist")
        val all = songs()
        if (all.isEmpty()) throw invalidPayload("The playlist has no playable tracks")
        start(playQueue(all, request.startIndex, request.shuffle, random))
    }

    suspend fun playTracks(request: PlayTracksRequest) {
        if (request.trackIds.isEmpty()) throw invalidPayload("trackIds must not be empty")
        if (request.trackIds.size > MAX_TRACK_IDS) throw invalidPayload("At most $MAX_TRACK_IDS tracks can be played at once")
        start(playQueue(tracksById(request.trackIds, songs()), request.startIndex, shuffle = false, random = random))
    }

    suspend fun state(): KoraPlaybackState = playbackState(player.state(), clock())

    private suspend fun start(queue: TrackQueue) = player.playQueue(queue.tracks, queue.startIndex)

    private companion object {
        /** As on the phone: no more than 500 ids in one command. */
        const val MAX_TRACK_IDS = 500
    }
}
