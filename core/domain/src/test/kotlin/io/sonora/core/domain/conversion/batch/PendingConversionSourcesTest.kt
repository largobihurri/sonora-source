package io.sonora.core.domain.conversion.batch

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.model.PickedSource
import org.junit.Test

class PendingConversionSourcesTest {

    private val pending = PendingConversionSources()
    private val folder = PendingSource(PickedSource.Folder("tree:dj"), "DJ")
    private val song = PendingSource(PickedSource.Track("doc:a"), "Song A", detail = "Artist")
    private val file = PendingSource(PickedSource.File("doc:b"), "b.mp3")

    @Test
    fun `sources added anywhere are listed in the order they were added`() {
        pending.add(listOf(folder, song))
        pending.add(listOf(file))

        assertThat(pending.sources.value).containsExactly(folder, song, file).inOrder()
    }

    @Test
    fun `a source already listed (same URI) stays as it was`() {
        pending.add(listOf(song))
        pending.add(listOf(song.copy(displayName = "Renamed"), file))

        assertThat(pending.sources.value).containsExactly(song, file).inOrder()
    }

    @Test
    fun `removing drops one source, or the ones a batch took`() {
        pending.add(listOf(folder, song, file))

        pending.remove("doc:a")
        assertThat(pending.sources.value).containsExactly(folder, file).inOrder()

        pending.removeAll(setOf("tree:dj", "doc:b"))
        assertThat(pending.sources.value).isEmpty()
    }

    @Test
    fun `only folders are folders`() {
        assertThat(folder.isFolder).isTrue()
        assertThat(song.isFolder).isFalse()
        assertThat(file.isFolder).isFalse()
    }
}
