package io.sonora.core.kora

import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.media.MediaPlayback
import eus.bihurri.kora.contract.media.PlayPlaylistRequest
import eus.bihurri.kora.contract.media.PlayTracksRequest
import eus.bihurri.kora.contract.media.SeekRequest
import eus.bihurri.kora.core.ProviderBuilder
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.LibraryRepository
import io.sonora.core.domain.port.PlaybackController
import io.sonora.core.domain.port.PlaylistRepository
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import eus.bihurri.kora.contract.media.PlaybackState as KoraPlaybackState

/**
 * `media.playback` over Sonora's [PlaybackController]. The controller is built on first use, and every call that
 * reaches it runs on [mainDispatcher] (the controller drives a Media3 controller, which needs the main thread). While
 * the player has not connected to the playback service yet (a cold start by the first call) a call waits for
 * [connectWaitMs]; past that it is answered `provider_unavailable` rather than acting on, or reporting, a player
 * that is not there.
 */
internal class MediaPlaybackHandlers(
    private val playback: () -> PlaybackController,
    private val library: () -> LibraryRepository,
    private val playlists: () -> PlaylistRepository,
    private val mainDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val connectWaitMs: Long,
) {
    @Inject
    constructor(
        playback: dagger.Lazy<PlaybackController>,
        library: dagger.Lazy<LibraryRepository>,
        playlists: dagger.Lazy<PlaylistRepository>,
    ) : this(
        playback = { playback.get() },
        library = { library.get() },
        playlists = { playlists.get() },
        mainDispatcher = Dispatchers.Main,
        clock = System::currentTimeMillis,
        connectWaitMs = DEFAULT_CONNECT_WAIT.inWholeMilliseconds,
    )

    /** Registers every operation and event of the capability (the node rejects a partial provider at install). */
    fun register(builder: ProviderBuilder<MediaPlayback>) = with(builder) {
        on(MediaPlayback.Play) { onPlayer { play() } }
        on(MediaPlayback.Pause) { onPlayer { pause() } }
        on(MediaPlayback.Toggle) { onPlayer { togglePlayPause() } }
        on(MediaPlayback.Next) { onPlayer { skipToNext() } }
        on(MediaPlayback.Previous) { onPlayer { skipToPrevious() } }
        on(MediaPlayback.SeekTo) { request -> seekTo(request) }
        on(MediaPlayback.PlayPlaylist) { request -> playPlaylist(request) }
        on(MediaPlayback.PlayTracks) { request -> playTracks(request) }
        on(MediaPlayback.GetState) { state() }
        emits(MediaPlayback.PlaybackStateChanged) { playback().state.playbackStateChanges(clock) }
        emits(MediaPlayback.NowPlayingChanged) { playback().state.nowPlayingChanges(clock) }
    }

    suspend fun seekTo(request: SeekRequest) {
        if (request.positionMs < 0) throw invalidPayload("positionMs must not be negative")
        onPlayer { seekTo(request.positionMs) }
    }

    /** `startIndex` is checked against the playlist even when shuffled, where Sonora ignores it (a random track first). */
    suspend fun playPlaylist(request: PlayPlaylistRequest) {
        val tracks = playableTracksOf(request.playlistId, library(), playlists())
        if (tracks.isEmpty()) throw invalidPayload("The playlist has no playable tracks")
        play(tracks, request.startIndex, request.shuffle)
    }

    suspend fun playTracks(request: PlayTracksRequest) {
        if (request.trackIds.isEmpty()) throw invalidPayload("trackIds must not be empty")
        if (request.trackIds.size > MAX_TRACK_IDS) throw invalidPayload("At most $MAX_TRACK_IDS tracks can be played at once")
        val ids = request.trackIds.map { parseId(it, "track") }
        val byId = library().tracks().first().associateBy { it.id }
        val tracks = ids.map { byId[it] ?: throw invalidPayload("Unknown track") }
        play(tracks, request.startIndex, shuffle = false)
    }

    suspend fun state(): KoraPlaybackState = onPlayer { playbackState(state.value, clock()) }

    private suspend fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean) {
        if (startIndex !in tracks.indices) throw invalidPayload("startIndex is outside the ${tracks.size} tracks")
        onPlayer { playQueue(tracks, startIndex, shuffle) }
    }

    private suspend fun <T> onPlayer(block: PlaybackController.() -> T): T = withContext(mainDispatcher) {
        val player = playback()
        val connected = withTimeoutOrNull(connectWaitMs) { player.state.map { it.isConnected }.first { it } }
        if (connected == null) throw KoraException(KoraError(ErrorCode.PROVIDER_UNAVAILABLE, "The player is not available"))
        player.block()
    }

    private companion object {
        /** A cold start connects the player to its service in well under a second; the node's call timeout is 5 s. */
        val DEFAULT_CONNECT_WAIT = 3.seconds

        /** As the old external API: no more than 500 ids in one command. */
        const val MAX_TRACK_IDS = 500
    }
}
