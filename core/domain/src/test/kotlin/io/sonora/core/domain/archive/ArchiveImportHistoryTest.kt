package io.sonora.core.domain.archive

import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ArchiveImportHistoryTest {

    private val provider = "content://com.android.externalstorage.documents"
    private val musicTree = "$provider/tree/primary%3AMusic"
    private val folderUri = "$musicTree/document/primary%3AMusic%2FRoad%20Trip"
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_759_000_000_000), ZoneOffset.UTC)

    private fun extracted(name: String) = ExtractedFile(name, "$folderUri%2F${name.replace(" ", "%20")}")
    private fun key(name: String) = "com.android.externalstorage.documents/primary:Music/Road Trip/$name"

    private val imports = FakeArchiveImports()

    /** Tags by file name; a name missing here reads as unreadable (`null`). */
    private class FakeTags(private val tags: Map<String, SongCredits>) : ExtractedSongTags {
        val read = mutableListOf<String>()
        override suspend fun read(file: ExtractedFile): SongCredits? {
            read += file.path
            return tags[file.path]
        }
    }

    private fun recorder(folder: ImportedFolder, tags: FakeTags = FakeTags(emptyMap())) =
        RecordArchiveImport(imports, { folder }, tags, clock)

    @Test
    fun `a Sonora archive's songs are recorded in its order with its titles, and a file it does not name from its tags`() = runBlocking<Unit> {
        val manifest = ArchiveManifest(
            ArchiveKind.PLAYLIST,
            "Road Trip",
            listOf(ArchiveEntry("02 - B - Two.flac", "Two", "B", "Road Trip"), ArchiveEntry("01 - A - One.flac", "One", "A", "Road Trip")),
        )
        val tags = FakeTags(mapOf("Bonus.mp3" to SongCredits("Bonus Track", "C")))
        val folder = ImportedFolder(listOf(extracted("Bonus.mp3"), extracted("01 - a - one.FLAC"), extracted("02 - B - Two.flac")), manifest)

        val recorded = recorder(folder, tags).record("Road Trip.zip", musicTree, folderUri)

        val songs = listOf(
            ImportedSong(key("02 - B - Two.flac"), "Two", "B"),
            ImportedSong(key("01 - a - one.FLAC"), "One", "A"),
            ImportedSong(key("Bonus.mp3"), "Bonus Track", "C"),
        )
        assertThat(recorded).isEqualTo(RecordedImport(1, songs))
        assertThat(tags.read).containsExactly("Bonus.mp3")
        assertThat(imports.imports().first()).containsExactly(
            ArchiveImportEntry(1, "Road Trip.zip", 1_759_000_000_000, musicTree, folderUri, playlistId = null, songCount = 3),
        )
        assertThat(imports.getSongs(1)).isEqualTo(songs)
    }

    @Test
    fun `a plain ZIP's songs are recorded by name from their tags, an untagged or unreadable one under its file name`() = runBlocking<Unit> {
        val tags = FakeTags(mapOf("b.flac" to SongCredits("Bee", "Band"), "c.ogg" to SongCredits(title = " ", artist = null)))
        val folder = ImportedFolder(listOf(extracted("c.ogg"), extracted("B.flac").copy(path = "b.flac"), extracted("A song.mp3")), manifest = null)

        val recorded = recorder(folder, tags).record("mix.zip", musicTree, folderUri)

        assertThat(recorded.songs.map { it.title to it.artist }).containsExactly("A song" to "", "Bee" to "Band", "c" to "").inOrder()
    }

    @Test
    fun `a tag reader failure names the song after its file`() = runBlocking<Unit> {
        val folder = ImportedFolder(listOf(extracted("a.flac")), manifest = null)
        val broken = RecordArchiveImport(imports, { folder }, { error("provider died") }, clock)

        val recorded = broken.record("mix.zip", musicTree, folderUri)

        assertThat(recorded.songs).containsExactly(ImportedSong(key("a.flac"), "a", ""))
    }

    @Test
    fun `the playlist an import recreated is kept with its record`() = runBlocking<Unit> {
        val recorder = recorder(ImportedFolder(listOf(extracted("a.flac")), manifest = null))
        val recorded = recorder.record("mix.zip", musicTree, folderUri)

        recorder.playlistMade(recorded.id, 42)

        assertThat(imports.get(recorded.id)!!.playlistId).isEqualTo(42L)
    }

    @Test
    fun `newest imports come first and removing one from the history forgets only its record`() = runBlocking<Unit> {
        val first = recorder(ImportedFolder(listOf(extracted("a.flac")), null)).record("first.zip", musicTree, folderUri)
        val later = RecordArchiveImport(imports, { ImportedFolder(listOf(extracted("b.flac")), null) }, { null }, Clock.offset(clock, java.time.Duration.ofMinutes(1)))
            .record("later.zip", musicTree, folderUri)
        assertThat(imports.imports().first().map { it.archiveName }).containsExactly("later.zip", "first.zip").inOrder()

        ForgetArchiveImport(imports)(first.id)

        assertThat(imports.imports().first().map { it.id }).containsExactly(later.id)
        assertThat(imports.getSongs(first.id)).isEmpty()
    }
}
