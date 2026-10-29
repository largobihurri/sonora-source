package io.sonora.core.playback

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.sonora.core.domain.model.QueueItem
import io.sonora.core.domain.model.Track

/**
 * Track <-> MediaItem. [Track.uri] is a SAF document URI (`content://`), which ExoPlayer's DefaultDataSource reads
 * through ContentDataSource with the app's persisted tree grant. [Track.artworkUri] is either a SAF document URI
 * (folder image) or a `file://` URI in the app's artwork cache; the session loads both in-process. The media id is a
 * [QueueMediaId] so the queue entry survives the round trip through the session.
 */
internal object MediaItemMapper {
    const val EXTRA_ALBUM_ID = "sonora.albumId"

    fun toMediaItem(item: QueueItem): MediaItem {
        val track = item.track
        val uri = track.uri.toUri()
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setAlbumArtist(track.albumArtist)
            .setTrackNumber(track.trackNumber)
            .setDiscNumber(track.discNumber)
            .setRecordingYear(track.year)
            .setDurationMs(track.durationMs.takeIf { it > 0 })
            .setArtworkUri(track.artworkUri?.toUri())
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setExtras(Bundle().apply { putLong(EXTRA_ALBUM_ID, track.albumId) })
            .build()
        return MediaItem.Builder()
            .setMediaId(QueueMediaId.format(track.id, item.id))
            .setUri(uri)
            .setMimeType(track.mimeType.takeIf { it != "audio/*" })
            .setMediaMetadata(metadata)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .build()
    }

    /** The library album of [item] (0 when unknown or not enqueued by this process). */
    fun albumId(item: MediaItem): Long = item.mediaMetadata.extras?.getLong(EXTRA_ALBUM_ID) ?: 0L

    /** Fallback when the session holds items this process did not enqueue (e.g. after a restart). */
    fun toTrack(item: MediaItem): Track {
        val m = item.mediaMetadata
        return Track(
            id = QueueMediaId.parse(item.mediaId).trackId ?: -1L,
            uri = (item.requestMetadata.mediaUri ?: item.localConfiguration?.uri)?.toString().orEmpty(),
            title = m.title?.toString().orEmpty(),
            artist = m.artist?.toString().orEmpty(),
            album = m.albumTitle?.toString().orEmpty(),
            albumArtist = m.albumArtist?.toString(),
            durationMs = m.durationMs ?: 0L,
            trackNumber = m.trackNumber,
            discNumber = m.discNumber,
            year = m.recordingYear,
            albumId = m.extras?.getLong(EXTRA_ALBUM_ID) ?: 0L,
            artworkUri = m.artworkUri?.toString(),
            mimeType = item.localConfiguration?.mimeType ?: "audio/*",
            sizeBytes = 0L,
        )
    }
}
