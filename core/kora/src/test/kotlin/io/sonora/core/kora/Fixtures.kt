package io.sonora.core.kora

import io.sonora.core.domain.model.PlaybackState
import io.sonora.core.domain.model.Playlist
import io.sonora.core.domain.model.PlaylistEntry
import io.sonora.core.domain.model.PlaylistWithEntries
import io.sonora.core.domain.model.QueueItem
import io.sonora.core.domain.model.Track

internal fun track(
    id: Long,
    title: String = "Track $id",
    artist: String = "Artist",
    album: String = "Album",
    artworkUri: String? = "content://media/art/$id",
    durationMs: Long = 180_000,
    albumId: Long = 1L,
) = Track(
    id = id,
    uri = "content://media/audio/$id",
    title = title,
    artist = artist,
    album = album,
    albumArtist = null,
    durationMs = durationMs,
    trackNumber = null,
    discNumber = null,
    year = null,
    albumId = albumId,
    artworkUri = artworkUri,
    mimeType = "audio/flac",
    sizeBytes = 1L,
)

internal fun playlist(id: Long, name: String, vararg tracks: Track) = PlaylistWithEntries(
    Playlist(id = id, name = name),
    tracks.map { PlaylistEntry(id = 0L, trackUri = it.uri, title = it.title, artist = it.artist) },
)

internal fun playing(
    current: Track?,
    isPlaying: Boolean = true,
    positionMs: Long = 0L,
    shuffle: Boolean = false,
    connected: Boolean = true,
) = PlaybackState(
    currentTrack = current,
    queue = listOfNotNull(current).mapIndexed { index, track -> QueueItem(index.toLong() + 1, track) },
    isPlaying = isPlaying,
    positionMs = positionMs,
    durationMs = current?.durationMs ?: 0L,
    shuffleEnabled = shuffle,
    currentIndex = if (current == null) -1 else 0,
    isConnected = connected,
)
