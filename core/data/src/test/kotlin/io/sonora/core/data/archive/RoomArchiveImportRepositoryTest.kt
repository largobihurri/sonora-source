package io.sonora.core.data.archive

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.sonora.core.data.library.db.LibraryDatabase
import io.sonora.core.domain.archive.ArchiveImportEntry
import io.sonora.core.domain.archive.ImportedSong
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomArchiveImportRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LibraryDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val repository = RoomArchiveImportRepository(db.archiveImportDao())

    @After
    fun tearDown() = db.close()

    private val songs = listOf(ImportedSong("p/Music/Mix/b.flac", "Bee", "Band"), ImportedSong("p/Music/Mix/a.flac", "Ay", ""))

    @Test
    fun `an import comes back as it was stored, with its songs in order`() = runTest {
        val entry = ArchiveImportEntry(
            id = 99,
            archiveName = "Mix.zip",
            importedAtMs = 1_759_000_000_000,
            destinationTreeUri = "content://p/tree/primary%3AMusic",
            folderUri = "content://p/tree/primary%3AMusic/document/primary%3AMusic%2FMix",
            songCount = 7,
        )

        val id = repository.add(entry, songs)

        val stored = entry.copy(id = id, songCount = 2)
        assertThat(repository.get(id)).isEqualTo(stored)
        assertThat(repository.imports().first()).containsExactly(stored)
        assertThat(repository.import(id).first()).isEqualTo(stored)
        assertThat(repository.getSongs(id)).isEqualTo(songs)
        assertThat(repository.songs(id).first()).isEqualTo(songs)
    }

    @Test
    fun `the playlist is noted and cleared, and a removed import is gone with its songs`() = runTest {
        val id = repository.add(ArchiveImportEntry(0, "Mix.zip", 1, "content://p/tree/t", "content://p/tree/t/document/d"), songs)

        repository.setPlaylist(id, 4)
        assertThat(repository.get(id)!!.playlistId).isEqualTo(4L)
        repository.setPlaylist(id, null)
        assertThat(repository.get(id)!!.playlistId).isNull()

        repository.remove(id)

        assertThat(repository.get(id)).isNull()
        assertThat(repository.imports().first()).isEmpty()
        assertThat(repository.getSongs(id)).isEmpty()
    }
}
