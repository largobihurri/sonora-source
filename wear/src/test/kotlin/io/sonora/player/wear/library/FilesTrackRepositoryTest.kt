package io.sonora.player.wear.library

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FilesTrackRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val noTags: (File) -> RawTags? = { null }

    private fun repository(readTags: (File) -> RawTags? = noTags) =
        FilesTrackRepository(directory = { folder.root }, readTags = readTags, dispatcher = Dispatchers.Unconfined)

    private fun file(name: String) = folder.newFile(name)

    @Test
    fun `before the first load there is no list at all`() {
        assertThat(repository().tracks.value).isNull()
    }

    @Test
    fun `an empty folder loads as an empty list`() = runBlocking<Unit> {
        val repository = repository()
        repository.refresh()
        assertThat(repository.tracks.value).isEmpty()
    }

    @Test
    fun `only playable files are songs`() = runBlocking<Unit> {
        file("a.opus")
        file("b.mp3")
        file("c.opus.part")
        file("cover.jpg")
        file(".hidden.opus")
        val repository = repository()
        repository.refresh()
        assertThat(repository.tracks.value!!.map { it.id }).containsExactly("a.opus", "b.mp3")
    }

    @Test
    fun `a song's tags describe it and its uri points at the file`() = runBlocking<Unit> {
        val song = file("m.opus")
        val repository = repository { RawTags("Midnight City", "M83", null, "Hurry Up", "3/12", "243000") }
        repository.refresh()
        val track = repository.tracks.value!!.single()
        assertThat(track.id).isEqualTo("m.opus")
        assertThat(track.title).isEqualTo("Midnight City")
        assertThat(track.artist).isEqualTo("M83")
        assertThat(track.trackNumber).isEqualTo(3)
        assertThat(track.uri).isEqualTo(song.toURI().toString())
    }

    @Test
    fun `a file whose tags cannot be read is still a song named after its file`() = runBlocking<Unit> {
        file("04 - Intro.opus")
        file("broken.opus")
        val repository = repository { f -> if (f.name == "broken.opus") error("unreadable") else null }
        repository.refresh()
        assertThat(repository.tracks.value!!.map { it.title }).containsExactly("Intro", "broken")
    }

    @Test
    fun `songs come in list order`() = runBlocking<Unit> {
        file("1.opus")
        file("2.opus")
        file("3.opus")
        val tags = mapOf(
            "1.opus" to RawTags("One", "Zed", null, null, null, null),
            "2.opus" to RawTags("Two", "Abe", null, null, null, null),
            "3.opus" to RawTags("Three", null, null, null, null, null),
        )
        val repository = repository { tags[it.name] }
        repository.refresh()
        assertThat(repository.tracks.value!!.map { it.title }).containsExactly("Two", "One", "Three").inOrder()
    }

    @Test
    fun `refreshing picks up files added since`() = runBlocking<Unit> {
        val repository = repository()
        repository.refresh()
        file("new.opus")
        repository.refresh()
        assertThat(repository.tracks.value!!.map { it.id }).containsExactly("new.opus")
    }

    @Test
    fun `a missing folder loads as an empty list`() = runBlocking<Unit> {
        val repository = FilesTrackRepository(directory = { File(folder.root, "nope") }, readTags = noTags, dispatcher = Dispatchers.Unconfined)
        repository.refresh()
        assertThat(repository.tracks.value).isEmpty()
    }
}
