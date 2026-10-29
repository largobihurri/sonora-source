package io.sonora.core.watchphone

import io.sonora.core.domain.conversion.ConversionProfiles
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.playlist.ResolvedPlaylist
import io.sonora.core.watchsync.TrackIds
import io.sonora.core.watchsync.sender.SendCandidate

/** From library songs to what the watch sender takes, and in what order. Pure. */
object SendSelection {
    /** What every song sent to the watch is encoded with. */
    val PROFILE = ConversionProfiles.WATCH_OPUS

    fun candidateOf(track: Track) = SendCandidate(
        id = TrackIds.of(track.uri, PROFILE.id),
        sourceUri = track.uri,
        title = track.title,
        artist = track.artist,
        albumArtist = track.albumArtist,
        album = track.album,
        trackNumber = track.trackNumber,
        discNumber = track.discNumber,
        durationMs = track.durationMs,
        sourceVersion = track.sizeBytes,
    )

    /** An album's songs in disc and track order; songs without a number follow, by title. */
    fun forAlbum(tracks: List<Track>): List<SendCandidate> = tracks
        .sortedWith(
            compareBy<Track> { it.discNumber ?: 1 }
                .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                .thenBy { it.title.lowercase() },
        )
        .map(::candidateOf)

    /** A playlist's songs in playlist order; entries whose file left the library are skipped. */
    fun forPlaylist(playlist: ResolvedPlaylist): List<SendCandidate> = playlist.playableTracks.map(::candidateOf)

    fun forTracks(tracks: List<Track>): List<SendCandidate> = tracks.map(::candidateOf)
}
