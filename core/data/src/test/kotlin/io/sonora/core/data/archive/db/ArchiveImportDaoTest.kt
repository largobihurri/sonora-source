package io.sonora.core.data.archive.db

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.db.LibraryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ArchiveImportDaoTest {

    private lateinit var db: LibraryDatabase
    private lateinit var dao: ArchiveImportDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LibraryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.archiveImportDao()
    }

    @After
    fun tearDown() = db.close()

    private fun import(name: String, at: Long) = ArchiveImportEntity(
        archiveName = name,
        importedAtMs = at,
        destinationTreeUri = "content://p/tree/music",
        folderUri = "content://p/tree/music/document/music%2F$name",
        playlistId = null,
    )

    private fun song(title: String) = ArchiveImportSongEntity(importId = 0, position = 0, documentKey = "p/music/$title.flac", title = title, artist = "Band")

    @Test
    fun `imports are listed newest first with their song counts, their songs in order`() = runTest {
        val older = dao.insert(import("older", at = 1), listOf(song("A")))
        val newer = dao.insert(import("newer", at = 5), listOf(song("C"), song("B")))
        val empty = dao.insert(import("empty", at = 5), emptyList())

        assertThat(dao.imports().first().map { it.entry.archiveName to it.songCount })
            .containsExactly("empty" to 0, "newer" to 2, "older" to 1).inOrder()
        assertThat(dao.getSongs(newer).map { it.title to it.position }).containsExactly("C" to 0, "B" to 1).inOrder()
        assertThat(dao.songs(older).first().single().importId).isEqualTo(older)
        assertThat(dao.getImport(empty)!!.songCount).isEqualTo(0)
    }

    @Test
    fun `the recreated playlist is noted, and deleting an import takes its songs along`() = runTest {
        val id = dao.insert(import("mix", at = 1), listOf(song("A"), song("B")))
        val other = dao.insert(import("other", at = 2), listOf(song("C")))

        dao.setPlaylist(id, 9)
        assertThat(dao.observeImport(id).first()!!.entry.playlistId).isEqualTo(9L)

        dao.delete(id)

        assertThat(dao.getImport(id)).isNull()
        assertThat(dao.getSongs(id)).isEmpty()
        assertThat(dao.getSongs(other).map { it.title }).containsExactly("C")
    }
}
