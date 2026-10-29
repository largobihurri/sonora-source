package io.sonora.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.sonora.core.domain.playback.SleepEntry
import io.sonora.core.domain.playback.SleepPlayhead

/**
 * The service's [ExoPlayer] as the sleep timer drives it. The fade moves the player's own volume (the system volume
 * is the user's and stays as it is); a song or album timer uses ExoPlayer's pause at the end of a media item, which
 * stops exactly at the entry's end and resumes with the next entry on the next play.
 *
 * The playhead lists the queue only from the entry playing to the end of its album's run (at most [MAX_RUN] entries):
 * all the timer reads, without walking a long queue on every fade tick.
 */
internal class PlayerSleepPlayback(private val player: ExoPlayer) : SleepPlayback {

    private var listener: Player.Listener? = null

    override var volume: Float
        get() = player.volume
        set(value) {
            player.volume = value
        }

    override fun playhead(): SleepPlayhead {
        val count = player.mediaItemCount
        val index = player.currentMediaItemIndex
        if (index !in 0 until count) return SleepPlayhead(emptyList(), 0, 0L)
        val current = player.getMediaItemAt(index)
        val album = albumKey(current)
        val known = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        val entries = mutableListOf(SleepEntry(album, known ?: current.durationMs()))
        if (album != null) {
            var next = index + 1
            while (next < count && entries.size < MAX_RUN) {
                val item = player.getMediaItemAt(next)
                if (albumKey(item) != album) break
                entries += SleepEntry(album, item.durationMs())
                next++
            }
        }
        return SleepPlayhead(entries, 0, player.currentPosition)
    }

    override fun pause() = player.pause()

    @OptIn(UnstableApi::class) // setPauseAtEndOfMediaItems
    override fun setPauseAtEndOfEntry(pause: Boolean) = player.setPauseAtEndOfMediaItems(pause)

    override fun setOnEntryEnded(listener: (() -> Unit)?) {
        this.listener?.let(player::removeListener)
        this.listener = listener?.let { ended ->
            object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) ended()
                }

                // The last entry of the queue may end the playlist rather than pause in it.
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) ended()
                }
            }.also(player::addListener)
        }
    }

    private fun MediaItem.durationMs(): Long? = mediaMetadata.durationMs?.takeIf { it > 0L }

    /** The entry's album: the library's id, else its title and album artist; null for a song with no album. */
    private fun albumKey(item: MediaItem): String? {
        val id = MediaItemMapper.albumId(item)
        if (id > 0L) return "id:$id"
        val title = item.mediaMetadata.albumTitle?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return "title:$title\u0000${item.mediaMetadata.albumArtist ?: ""}"
    }

    private companion object {
        /** More songs than any album has: the run's end is found well before. */
        const val MAX_RUN = 500
    }
}
