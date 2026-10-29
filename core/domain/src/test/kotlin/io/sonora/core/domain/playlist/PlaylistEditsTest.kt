package io.sonora.core.domain.playlist

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaylistEditsTest {

    private val entries = listOf(entry(1, 10), entry(2, 20), entry(3, 30), entry(4, 40))

    private fun ids(list: List<io.sonora.core.domain.model.PlaylistEntry>) = list.map { it.id }

    @Test
    fun `append adds new unsaved entries at the end in the given order, with title and artist snapshots`() {
        val result = PlaylistEdits.append(entries.take(1), listOf(track(20), track(30)))

        assertThat(ids(result)).containsExactly(1L, 0L, 0L).inOrder()
        assertThat(result[1].trackUri).isEqualTo("content://music/20")
        assertThat(result[1].title).isEqualTo("Song 20")
        assertThat(result[1].artist).isEqualTo("Artist 20")
        assertThat(result[2].trackUri).isEqualTo("content://music/30")
    }

    @Test
    fun `append keeps duplicates, as the same song can be in a playlist more than once`() {
        val result = PlaylistEdits.append(listOf(entry(1, 10)), listOf(track(10), track(10)))

        assertThat(result.map { it.trackUri }).containsExactly(
            "content://music/10", "content://music/10", "content://music/10",
        )
    }

    @Test
    fun `remove drops only the entry with that id, even when its song is in the playlist twice`() {
        val withDuplicate = entries + entry(5, 10)

        val result = PlaylistEdits.remove(withDuplicate, entryId = 1)

        assertThat(ids(result)).containsExactly(2L, 3L, 4L, 5L).inOrder()
        assertThat(result.last().trackUri).isEqualTo("content://music/10")
    }

    @Test
    fun `remove of an unknown entry changes nothing`() {
        assertThat(PlaylistEdits.remove(entries, entryId = 99)).isEqualTo(entries)
    }

    @Test
    fun `move down places the entry at the target index`() {
        assertThat(ids(PlaylistEdits.move(entries, entryId = 1, toIndex = 2))).containsExactly(2L, 3L, 1L, 4L).inOrder()
    }

    @Test
    fun `move up places the entry at the target index`() {
        assertThat(ids(PlaylistEdits.move(entries, entryId = 4, toIndex = 0))).containsExactly(4L, 1L, 2L, 3L).inOrder()
    }

    @Test
    fun `move clamps an index past either end`() {
        assertThat(ids(PlaylistEdits.move(entries, entryId = 2, toIndex = 99))).containsExactly(1L, 3L, 4L, 2L).inOrder()
        assertThat(ids(PlaylistEdits.move(entries, entryId = 3, toIndex = -5))).containsExactly(3L, 1L, 2L, 4L).inOrder()
    }

    @Test
    fun `move to its own index or of an unknown entry changes nothing`() {
        assertThat(PlaylistEdits.move(entries, entryId = 2, toIndex = 1)).isEqualTo(entries)
        assertThat(PlaylistEdits.move(entries, entryId = 99, toIndex = 0)).isEqualTo(entries)
    }

    @Test
    fun `names are trimmed and a blank name is no name`() {
        assertThat(PlaylistEdits.cleanName("  Road Trip \n")).isEqualTo("Road Trip")
        assertThat(PlaylistEdits.cleanName("   ")).isNull()
        assertThat(PlaylistEdits.cleanName("")).isNull()
    }
}
