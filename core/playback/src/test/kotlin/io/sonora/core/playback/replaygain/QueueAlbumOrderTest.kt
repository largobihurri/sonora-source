package io.sonora.core.playback.replaygain

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import io.sonora.core.playback.MediaItemMapper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Whether a stream plays as part of an album in order, from the player's queue (its timeline). */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QueueAlbumOrderTest {

    private fun song(albumId: Long, track: Int?, disc: Int? = 1, album: String = "Album $albumId") = MediaItem.Builder()
        .setMediaId("song-$albumId-$disc-$track")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setAlbumTitle(album)
                .setTrackNumber(track)
                .setDiscNumber(disc)
                .setExtras(Bundle().apply { putLong(MediaItemMapper.EXTRA_ALBUM_ID, albumId) })
                .build(),
        )
        .build()

    /** A timeline of one period per queued item, as the player builds for a playlist. */
    private fun queue(vararg items: MediaItem): Timeline {
        val windows = items.mapIndexed { i, item ->
            Timeline.Window().set(
                "window-$i", item, null, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, true, false, null, 0, C.TIME_UNSET, i, i, 0,
            )
        }
        val periods = items.indices.map { i -> Timeline.Period().set("window-$i", "period-$i", i, C.TIME_UNSET, 0) }
        return Timeline.RemotableTimeline(ImmutableList.copyOf(windows), ImmutableList.copyOf(periods), IntArray(items.size) { it })
    }

    private fun period(index: Int) = MediaSource.MediaPeriodId("period-$index")

    @Test
    fun `an album queued in order plays in order, first and last songs included`() {
        val timeline = queue(song(1, 1), song(1, 2), song(1, 3))

        for (i in 0..2) assertThat(QueueAlbumOrder.albumInOrder(timeline, period(i))).isTrue()
    }

    @Test
    fun `shuffled songs or a mix of albums do not`() {
        val shuffled = queue(song(1, 3), song(1, 1), song(1, 5))
        val mixed = queue(song(1, 1), song(2, 2), song(3, 3))

        assertThat(QueueAlbumOrder.albumInOrder(shuffled, period(1))).isFalse()
        assertThat(QueueAlbumOrder.albumInOrder(mixed, period(1))).isFalse()
    }

    @Test
    fun `songs without an album id are matched by album title`() {
        val timeline = queue(song(0, 4, album = "Live"), song(0, 5, album = "Live"))

        assertThat(QueueAlbumOrder.albumInOrder(timeline, period(1))).isTrue()
    }

    @Test
    fun `an unknown stream or an empty queue is not an album in order`() {
        val timeline = queue(song(1, 1), song(1, 2))

        assertThat(QueueAlbumOrder.albumInOrder(timeline, null)).isFalse()
        assertThat(QueueAlbumOrder.albumInOrder(timeline, MediaSource.MediaPeriodId("elsewhere"))).isFalse()
        assertThat(QueueAlbumOrder.albumInOrder(Timeline.EMPTY, period(0))).isFalse()
    }
}
