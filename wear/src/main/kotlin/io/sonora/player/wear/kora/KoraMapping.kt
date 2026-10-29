package io.sonora.player.wear.kora

import eus.bihurri.kora.contract.KoraError
import eus.bihurri.kora.contract.KoraException
import eus.bihurri.kora.contract.ErrorCode
import eus.bihurri.kora.contract.media.Page
import eus.bihurri.kora.contract.media.PageRequest
import eus.bihurri.kora.contract.media.PlaybackStatus
import eus.bihurri.kora.contract.media.TrackSummary
import io.sonora.player.wear.library.TrackQueue
import io.sonora.player.wear.library.WatchTrack
import java.security.MessageDigest
import kotlin.random.Random
import eus.bihurri.kora.contract.media.PlaybackState as KoraPlaybackState

/** The most items one page carries, whatever `limit` asks for (the same cap as Sonora's phone node). */
internal const val MAX_PAGE_SIZE = 200

/** The id of the one playlist the watch has: all the songs on it. The watch keeps no playlists of its own. */
internal const val ALL_SONGS_ID = "all"

/**
 * The id a caller sees for a song: `t` and 16 hex digits of the SHA-256 of the song's id (its file name). Opaque and
 * stable across scans, and it never carries a file name, a path or a URI, which the contract forbids.
 */
internal fun koraTrackId(trackId: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(trackId.toByteArray(Charsets.UTF_8))
    return "t" + digest.take(ID_BYTES).joinToString("") { "%02x".format(it) }
}

private const val ID_BYTES = 8

/** The watch's songs have no artwork, so there is never an `artworkId`. A blank tag is unknown, which Kora spells as absent. */
internal fun trackSummary(track: WatchTrack): TrackSummary = TrackSummary(
    id = koraTrackId(track.id),
    title = track.title,
    artist = track.artist?.takeIf { it.isNotBlank() },
    album = track.album?.takeIf { it.isNotBlank() },
    durationMs = track.durationMs ?: 0L,
)

/**
 * Whether sound is being produced or about to be: the player is playing, or the user wants sound and the next song is
 * loading (as the screens show it), unless playback is held back ([suppressed], for instance while waiting for headphones),
 * when nothing is about to sound.
 */
internal fun soundExpected(isPlaying: Boolean, playWhenReady: Boolean, buffering: Boolean, suppressed: Boolean): Boolean =
    isPlaying || (playWhenReady && buffering && !suppressed)

/** What the watch's player is doing, as far as the contract tells. */
internal enum class WatchStatus { IDLE, PLAYING, PAUSED, ENDED }

/**
 * Nothing queued is idle; a finished queue is ended; sound being produced (or about to be, while the next song loads)
 * is playing; anything else, including playback waiting for headphones, is paused.
 */
internal fun watchStatus(hasMedia: Boolean, ended: Boolean, playing: Boolean): WatchStatus = when {
    !hasMedia -> WatchStatus.IDLE
    ended -> WatchStatus.ENDED
    playing -> WatchStatus.PLAYING
    else -> WatchStatus.PAUSED
}

/** A snapshot of the watch's player. [positionMs] is accurate at the moment the snapshot was taken. */
internal data class WatchPlayerState(
    val status: WatchStatus,
    val track: WatchTrack?,
    val positionMs: Long,
    val shuffle: Boolean,
)

/** The snapshot as the contract's state, stamped with [nowMs], the time of capture. */
internal fun playbackState(state: WatchPlayerState, nowMs: Long): KoraPlaybackState = KoraPlaybackState(
    status = when (state.status) {
        WatchStatus.IDLE -> PlaybackStatus.IDLE
        WatchStatus.PLAYING -> PlaybackStatus.PLAYING
        WatchStatus.PAUSED -> PlaybackStatus.PAUSED
        WatchStatus.ENDED -> PlaybackStatus.ENDED
    },
    positionMs = state.positionMs,
    updatedAtEpochMs = nowMs,
    track = state.track?.let(::trackSummary),
    shuffle = state.shuffle,
)

/** A caller mistake, answered `invalid_payload`. The message never echoes caller input. */
internal fun invalidPayload(message: String): KoraException = KoraException(KoraError(ErrorCode.INVALID_PAYLOAD, message))

internal fun <T> pageOf(all: List<T>, request: PageRequest): Page<T> =
    Page(all.drop(request.offset).take(minOf(request.limit, MAX_PAGE_SIZE)), total = all.size, offset = request.offset)

/** Case-insensitive "contains" on title, artist or album, sorted by title. A blank query matches nothing. */
internal fun searchTracks(tracks: List<WatchTrack>, query: String): List<WatchTrack> {
    val text = query.trim()
    if (text.isEmpty()) return emptyList()
    return tracks
        .filter { listOfNotNull(it.title, it.artist, it.album).any { field -> field.contains(text, ignoreCase = true) } }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
}

/**
 * The queue for "play these songs from [startIndex]". Shuffling reorders the list itself and starts at its first song,
 * so the watch's queue screen keeps showing what plays next (the watch has no shuffle mode of its own, and ignores
 * [startIndex] when shuffling, as Sonora's phone does). [startIndex] is checked in both cases.
 */
internal fun playQueue(tracks: List<WatchTrack>, startIndex: Int, shuffle: Boolean, random: Random): TrackQueue {
    if (startIndex !in tracks.indices) throw invalidPayload("startIndex is outside the ${tracks.size} tracks")
    return if (shuffle) TrackQueue(tracks.shuffled(random), startIndex = 0) else TrackQueue(tracks, startIndex)
}

/** The songs named by [ids], in that order (repeats included). An id that names no song is an invalid payload. */
internal fun tracksById(ids: List<String>, songs: List<WatchTrack>): List<WatchTrack> {
    val byId = songs.associateBy { koraTrackId(it.id) }
    return ids.map { byId[it] ?: throw invalidPayload("Unknown track") }
}
