package io.sonora.core.kora

import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlaybackStatus
import eus.bihurri.kora.contract.media.PlaylistSummary
import eus.bihurri.kora.contract.media.TrackSummary
import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playlist.ResolvedPlaylist
import eus.bihurri.kora.contract.media.PlaybackState as KoraPlaybackState

/** The most items one page carries, whatever `limit` asks for (the old external API held pages to the same 200). */
internal const val MAX_PAGE_SIZE = 200

/**
 * The opaque artwork reference of a track: `a:<albumId>`, the key of its album's cover, so the tracks of one album share
 * it and a consumer can cache by it. Never a URI or a path: [albumIdOfArtwork] is the only way back, and it answers
 * `media.artwork` from Sonora's own cover source.
 */
private fun artworkId(track: Track): String = "$ARTWORK_PREFIX${track.albumId}"

private const val ARTWORK_PREFIX = "a:"

/** The album id of an artwork key made by [artworkId]; anything else is an invalid payload. */
internal fun albumIdOfArtwork(artworkId: String): Long {
    if (!artworkId.startsWith(ARTWORK_PREFIX)) throw invalidPayload("Unknown artwork")
    return parseId(artworkId.removePrefix(ARTWORK_PREFIX), "artwork")
}

/** Sonora's tags are never null; a blank one is unknown, which Kora spells as absent. */
internal fun trackSummary(track: Track): TrackSummary = TrackSummary(
    id = track.id.toString(),
    title = track.title,
    artist = track.artist.takeIf { it.isNotBlank() },
    album = track.album.takeIf { it.isNotBlank() },
    durationMs = track.durationMs,
    artworkId = track.artworkUri?.let { artworkId(track) },
)

/**
 * The count is of the playable tracks, so it equals the `total` of `get_playlist_tracks`; entries whose file left the
 * library are not counted. The artwork is the first playable track's.
 */
internal fun playlistSummary(resolved: ResolvedPlaylist): PlaylistSummary = PlaylistSummary(
    id = resolved.playlist.id.toString(),
    name = resolved.playlist.name,
    trackCount = resolved.playableTracks.size,
    artworkId = resolved.playableTracks.firstOrNull { it.artworkUri != null }?.let(::artworkId),
)

/**
 * A snapshot of the player at [nowMs] (the time of capture: the position is accurate then). Idle when the player is
 * not connected or has no current track; Sonora's "playing" includes a track that is still buffering.
 */
internal fun playbackState(state: PlaybackState, nowMs: Long): KoraPlaybackState = KoraPlaybackState(
    status = when {
        !state.isConnected || state.currentTrack == null -> PlaybackStatus.IDLE
        state.isPlaying -> PlaybackStatus.PLAYING
        else -> PlaybackStatus.PAUSED
    },
    positionMs = state.positionMs,
    updatedAtEpochMs = nowMs,
    track = state.currentTrack?.let(::trackSummary),
    shuffle = state.shuffleEnabled,
)

/** A caller mistake, answered `invalid_payload`. The message never echoes caller input. */
internal fun invalidPayload(message: String): KoraException = KoraException(KoraError(ErrorCode.INVALID_PAYLOAD, message))

/** Parses a library id: digits only. Anything else (a URI, a path, a sign, a decimal) is an invalid payload. */
internal fun parseId(value: String, what: String): Long {
    if (value.isEmpty() || value.length > MAX_ID_DIGITS || !value.all { it in '0'..'9' }) throw invalidPayload("Invalid $what id")
    return value.toLongOrNull() ?: throw invalidPayload("Invalid $what id")
}

private const val MAX_ID_DIGITS = 18

internal fun <T> pageOf(all: List<T>, request: PageRequest): Page<T> =
    Page(all.drop(request.offset).take(minOf(request.limit, MAX_PAGE_SIZE)), total = all.size, offset = request.offset)
