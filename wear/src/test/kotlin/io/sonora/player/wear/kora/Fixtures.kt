package io.sonora.player.wear.kora

import io.sonora.player.wear.library.WatchTrack

internal fun song(
    id: String,
    title: String = "Title of $id",
    artist: String? = "Artist",
    album: String? = "Album",
    durationMs: Long? = 180_000,
) = WatchTrack(
    id = id,
    uri = "file:///data/user/0/io.sonora.player/files/music/$id",
    title = title,
    artist = artist,
    album = album,
    trackNumber = null,
    durationMs = durationMs,
)

internal fun playerState(
    track: WatchTrack?,
    status: WatchStatus = WatchStatus.PLAYING,
    positionMs: Long = 0L,
    shuffle: Boolean = false,
) = WatchPlayerState(status = status, track = track, positionMs = positionMs, shuffle = shuffle)
