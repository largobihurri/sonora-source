package io.sonora.core.playback.replaygain

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import io.sonora.core.domain.playback.AlbumPosition
import io.sonora.core.domain.playback.ReplayGain
import io.sonora.core.playback.MediaItemMapper

/**
 * Whether a stream plays as part of an album in order (for [io.sonora.core.domain.playback.ReplayGainMode.AUTO]),
 * from the queue around it: the player's timeline, whose window order is the play order (shuffle reorders the
 * playlist itself, see [io.sonora.core.playback.SessionPlayer]). Albums are told apart by the library's album id
 * ([MediaItemMapper.EXTRA_ALBUM_ID]), or by album title and album artist when an item has none.
 */
@OptIn(UnstableApi::class)
object QueueAlbumOrder {
    fun albumInOrder(timeline: Timeline, periodId: MediaSource.MediaPeriodId?): Boolean {
        if (periodId == null || timeline.isEmpty) return false
        val periodIndex = timeline.getIndexOfPeriod(periodId.periodUid)
        if (periodIndex == C.INDEX_UNSET) return false
        val windowIndex = timeline.getPeriod(periodIndex, Timeline.Period()).windowIndex
        val window = Timeline.Window()
        fun at(index: Int): AlbumPosition? =
            if (index in 0 until timeline.windowCount) position(timeline.getWindow(index, window).mediaItem) else null
        val current = at(windowIndex) ?: return false
        return ReplayGain.albumInOrder(at(windowIndex - 1), current, at(windowIndex + 1))
    }

    private fun position(item: MediaItem): AlbumPosition {
        val metadata = item.mediaMetadata
        val albumId = metadata.extras?.getLong(MediaItemMapper.EXTRA_ALBUM_ID) ?: 0L
        val title = metadata.albumTitle?.toString().orEmpty()
        val album = when {
            albumId > 0 -> "id:$albumId"
            title.isNotBlank() -> "title:$title\u0000${metadata.albumArtist ?: ""}"
            else -> ""
        }
        return AlbumPosition(album, metadata.discNumber, metadata.trackNumber)
    }
}
