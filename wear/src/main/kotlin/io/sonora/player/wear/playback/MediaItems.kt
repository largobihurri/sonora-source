package io.sonora.player.wear.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.sonora.player.wear.library.TrackFiles
import io.sonora.player.wear.library.WatchTrack

/** The player's item for a song: the song's id is the item's media id, so the screens can tell which one plays. */
fun WatchTrack.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setTrackNumber(trackNumber)
            .setDurationMs(durationMs)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build(),
    )
    .build()

/** The song a player item stands for: the inverse of [toMediaItem], for an item that came from the watch's own list. */
fun MediaItem.toWatchTrack(): WatchTrack = WatchTrack(
    id = mediaId,
    uri = localConfiguration?.uri?.toString().orEmpty(),
    title = mediaMetadata.title?.toString() ?: TrackFiles.titleFromFileName(mediaId),
    artist = mediaMetadata.artist?.toString(),
    album = mediaMetadata.albumTitle?.toString(),
    trackNumber = mediaMetadata.trackNumber,
    durationMs = mediaMetadata.durationMs,
)
