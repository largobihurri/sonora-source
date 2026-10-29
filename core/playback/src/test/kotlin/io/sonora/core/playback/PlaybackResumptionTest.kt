package io.sonora.core.playback

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.QueueItem
import io.sonora.core.domain.model.RepeatMode
import io.sonora.core.domain.model.Track
import io.sonora.core.domain.port.PlaybackSnapshotStore
import io.sonora.core.domain.queue.PlaybackSnapshot
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlaybackResumptionTest {

    private class Store(val load: () -> PlaybackSnapshot?) : PlaybackSnapshotStore {
        override suspend fun load() = load.invoke()
        override suspend fun save(snapshot: PlaybackSnapshot) = Unit
        override suspend fun savePosition(itemId: Long, positionMs: Long) = Unit
    }

    private fun track(id: Long) = Track(
        id = id, uri = "content://doc/$id", title = "T$id", artist = "A", album = "B", albumArtist = null,
        durationMs = 1_000, trackNumber = null, discNumber = null, year = null, albumId = 1, artworkUri = null,
        mimeType = "audio/flac", sizeBytes = 1,
    )

    private val saved = PlaybackSnapshot(
        items = (1L..5L).map { QueueItem(it, track(it)) },
        currentIndex = 1,
        positionMs = 9_000,
        shuffled = false,
        unshuffledIds = (1L..5L).toList(),
        repeatMode = RepeatMode.OFF,
    )

    private fun resumption(
        isAccessible: (Track) -> Boolean = { true },
        exists: (Track) -> Boolean = { true },
        load: () -> PlaybackSnapshot? = { saved },
    ) = PlaybackResumption(Store(load), { isAccessible }, exists, Dispatchers.Unconfined)

    @Test
    fun `entries whose folder access was revoked are dropped`() = runTest {
        val restored = resumption(isAccessible = { it.id % 2 == 0L }).load()!!

        assertThat(restored.items.map { it.id }).containsExactly(2L, 4L).inOrder()
        assertThat(restored.items[restored.currentIndex].id).isEqualTo(2L)
        assertThat(restored.positionMs).isEqualTo(9_000L)
    }

    @Test
    fun `a deleted current file is skipped for the next entry from its start`() = runTest {
        val checked = mutableListOf<Long>()
        val restored = resumption(exists = { checked += it.id; it.id > 3 }).load()!!

        assertThat(restored.items.map { it.id }).containsExactly(1L, 4L, 5L).inOrder()
        assertThat(restored.items[restored.currentIndex].id).isEqualTo(4L)
        assertThat(restored.positionMs).isEqualTo(0L)
        // Only the entry about to play is checked, not the whole queue.
        assertThat(checked).containsExactly(2L, 3L, 4L).inOrder()
    }

    @Test
    fun `nothing saved, nothing readable or a failing store resume nothing`() = runTest {
        assertThat(resumption(load = { null }).load()).isNull()
        assertThat(resumption(isAccessible = { false }).load()).isNull()
        assertThat(resumption(exists = { false }).load()).isNull()
        assertThat(resumption(load = { throw IOException("unreadable") }).load()).isNull()
    }

    @Test
    fun `a document is readable only under a folder the app still holds a grant for`() {
        val trees = listOf("content://com.android.externalstorage.documents/tree/primary%3AMusic")
        val base = "content://com.android.externalstorage.documents/tree"

        assertThat(SafTrackAccess.hasGrant("$base/primary%3AMusic/document/primary%3AMusic%2Fa.flac", trees)).isTrue()
        assertThat(SafTrackAccess.hasGrant("$base/primary%3AMusic2/document/primary%3AMusic2%2Fa.flac", trees)).isFalse()
        assertThat(SafTrackAccess.hasGrant("$base/primary%3APodcasts/document/primary%3APodcasts%2Fa.mp3", trees)).isFalse()
        // Not a document of a picked folder (e.g. a MediaStore item): no folder grant applies.
        assertThat(SafTrackAccess.hasGrant("content://media/external/audio/media/12", trees)).isTrue()
    }
}
