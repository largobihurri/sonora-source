package io.sonora.core.domain.library

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.FolderEntry
import io.sonora.core.domain.model.FolderRef
import io.sonora.core.domain.model.Track
import org.junit.Test

class FolderTreeTest {

    private val tree = "content://tree/primary%3AMusic"
    private val music = FolderRef(tree, "Music")

    private fun track(
        id: Long,
        folderPath: String,
        album: String = "Record",
        disc: Int? = null,
        number: Int? = null,
        title: String = "T$id",
    ) = Track(
        id = id, uri = "content://doc/$id", title = title, artist = "A", album = album, albumArtist = null,
        durationMs = 1_000, trackNumber = number, discNumber = disc, year = null, albumId = 1, artworkUri = null,
        mimeType = "audio/flac", sizeBytes = 1, folderPath = folderPath,
    )

    @Test
    fun `subfolders are the next level below the folder with every nested track counted`() {
        val counts = mapOf("Music" to 2, "Music/Rock" to 3, "Music/Rock/Live" to 1, "Music/Jazz/Bebop/1959" to 4)

        assertThat(FolderTree.subfolders(music, counts)).containsExactly(
            FolderEntry(FolderRef(tree, "Music/Jazz"), 4),
            FolderEntry(FolderRef(tree, "Music/Rock"), 4),
        ).inOrder()
        assertThat(FolderTree.subfolders(FolderRef(tree, "Music/Rock"), counts))
            .containsExactly(FolderEntry(FolderRef(tree, "Music/Rock/Live"), 1))
        assertThat(FolderTree.subfolders(FolderRef(tree, "Music/Rock/Live"), counts)).isEmpty()
    }

    @Test
    fun `a library folder whose display path has slashes lists its own subfolders by name`() {
        val root = FolderRef(tree, "Music/Rock")
        val counts = mapOf("Music/Rock/b side" to 1, "Music/Rock/A/C" to 2)

        val subfolders = FolderTree.subfolders(root, counts)

        assertThat(subfolders.map { it.folder.name }).containsExactly("A", "b side").inOrder()
        assertThat(subfolders.map { it.trackCount }).containsExactly(2, 1).inOrder()
        assertThat(root.name).isEqualTo("Rock")
    }

    @Test
    fun `a sibling that only shares the name prefix is neither inside nor listed`() {
        val rock = FolderRef(tree, "Music/Rock")
        val counts = mapOf("Music/Rock/Live" to 1, "Music/Rock Classics" to 5)

        assertThat(FolderTree.contains(rock, "Music/Rock Classics")).isFalse()
        assertThat(FolderTree.contains(rock, "Music/Rock/Live")).isTrue()
        assertThat(FolderTree.contains(rock, "Music/Rock")).isTrue()
        assertThat(FolderTree.contains(rock, null)).isFalse()
        assertThat(FolderTree.subfolders(rock, counts).map { it.folder.name }).containsExactly("Live")
    }

    @Test
    fun `same-named folders under different library folders stay apart`() {
        val internal = FolderRef("content://tree/primary%3AMusic", "Music")
        val sdCard = FolderRef("content://tree/1234-ABCD%3AMusic", "Music")

        assertThat(internal.child("Rock")).isNotEqualTo(sdCard.child("Rock"))
        assertThat(sdCard.child("Rock")).isEqualTo(FolderRef("content://tree/1234-ABCD%3AMusic", "Music/Rock"))
    }

    @Test
    fun `folders differing only in case are both listed in a stable order`() {
        val counts = mapOf("Music/rock" to 1, "Music/Rock" to 2, "Music/Ambient" to 1)

        assertThat(FolderTree.subfolders(music, counts).map { it.folder.name })
            .containsExactly("Ambient", "Rock", "rock").inOrder()
    }

    @Test
    fun `play order walks folders path by path, a folder's own tracks before its subfolders`() {
        val tracks = listOf(
            track(1, "Music/Rock Classics", number = 1),
            track(2, "Music/Rock/Live", number = 1),
            track(3, "Music/Rock", number = 2),
            track(4, "Music/Rock", number = 1),
            track(5, "Music/ambient", number = 1),
        )

        assertThat(FolderTree.playOrder(tracks).map { it.id }).containsExactly(5L, 4L, 3L, 2L, 1L).inOrder()
    }

    @Test
    fun `within one folder tracks keep album, disc, track number and title order`() {
        val tracks = listOf(
            track(1, "Music/Mixed", album = "Second", number = 1),
            track(2, "Music/Mixed", album = "First", disc = 2, number = 1),
            track(3, "Music/Mixed", album = "First", disc = 1, number = 2),
            track(4, "Music/Mixed", album = "First", disc = 1, number = 1),
            track(5, "Music/Mixed", album = "First", disc = 1, number = null, title = "b"),
            track(6, "Music/Mixed", album = "First", disc = 1, number = null, title = "A"),
        )

        assertThat(FolderTree.playOrder(tracks).map { it.id }).containsExactly(4L, 3L, 6L, 5L, 2L, 1L).inOrder()
    }

    @Test
    fun `contents lists subfolders then the folder's own tracks in play order`() {
        val own = listOf(track(2, "Music", number = 2), track(1, "Music", number = 1))
        val counts = mapOf("Music" to 2, "Music/Rock" to 3)

        val contents = FolderTree.contents(music, counts, own)

        assertThat(contents.subfolders).containsExactly(FolderEntry(FolderRef(tree, "Music/Rock"), 3))
        assertThat(contents.tracks.map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(contents.trackCount).isEqualTo(5)
    }
}
